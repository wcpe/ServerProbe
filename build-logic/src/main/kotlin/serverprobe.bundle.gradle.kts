/**
 * ServerProbe 主插件（plugin 模块）的打包与校验约定：
 * - 二合一 jar：agent manifest（Premain/Agent）、全部子模块输出合流、Arthas 双运行时闭包注入
 * - Java 8 Paper 验收专用瘦 Agent（mcpJava8Agent）及其结构校验
 * - 发行 Jar 的四项校验挂入 check（CI 链）：Arthas 闭包/业务集成/Velocity 描述符/SQLite 内置
 *
 * convention 应用顺序：plugins 块声明序（base 先于 bundle），脚本体直配（早于 TabooLib 的 afterEvaluate 读取 archiveBaseName）。
 */

import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.Manifest
import java.util.zip.ZipFile

plugins {
    java
}

fun extractArthasRuntime(archive: java.io.File, output: java.io.File) {
    val jarNames = listOf("arthas-core.jar", "arthas-boot.jar", "arthas-agent.jar", "arthas-spy.jar")
    // async-profiler 原生库（仅 4.x 含）：Arthas 运行期按 `libasyncProfiler.so` 查找，
    // 官方包内为平台后缀命名，提取时统一改名为 Arthas 期望的文件名。
    val asyncProfilerLibraries = listOf(
        "async-profiler/libasyncProfiler-linux-x64.so" to "libasyncProfiler-linux-x64.so",
        "async-profiler/libasyncProfiler-linux-arm64.so" to "libasyncProfiler-linux-arm64.so",
        "async-profiler/libasyncProfiler-mac.dylib" to "libasyncProfiler-mac.dylib",
    )
    ZipFile(archive).use { packaging ->
        jarNames.forEach { name ->
            val entry = packaging.getEntry(name) ?: error("官方 Arthas 发行包缺少：$name")
            packaging.getInputStream(entry).use { input ->
                Files.copy(input, output.toPath().resolve(name))
            }
        }
        asyncProfilerLibraries.forEach { (source, target) ->
            packaging.getEntry(source)?.let { entry ->
                packaging.getInputStream(entry).use { input ->
                    Files.copy(input, output.toPath().resolve(target))
                }
            }
        }
    }
    ZipFile(output.toPath().resolve("arthas-core.jar").toFile()).use { core ->
        mapOf("META-INF/LICENSE" to "LICENSE", "META-INF/NOTICE" to "NOTICE").forEach { (source, target) ->
            val entry = core.getEntry(source) ?: error("Arthas Core 缺少许可证文件：$source")
            core.getInputStream(entry).use { input ->
                Files.copy(input, output.toPath().resolve(target))
            }
        }
    }
    // checksum manifest：4 个 jar + 实际提取到的原生库（存在才写；3.1.1 无原生库则跳过）
    val checksummed = jarNames + asyncProfilerLibraries.map { it.second }.filter { Files.isRegularFile(output.toPath().resolve(it)) }
    val manifest = checksummed.joinToString("\n") { name -> "$name=${sha256(output.toPath().resolve(name))}" }
    output.resolve("sha256.properties").writeText(manifest + "\n")
}

fun sha256(file: java.nio.file.Path): String = MessageDigest.getInstance("SHA-256")
    .digest(Files.readAllBytes(file))
    .joinToString("") { byte -> "%02x".format(byte) }

val arthasLegacyVersion = providers.gradleProperty("arthasLegacyVersion").orElse("3.1.1")
    val arthasModernVersion = providers.gradleProperty("arthasModernVersion").orElse("4.3.2")
    val arthasLegacyPackaging = configurations.create("arthasLegacyPackaging") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    val arthasModernPackaging = configurations.create("arthasModernPackaging") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    dependencies {
        arthasLegacyPackaging("com.taobao.arthas:arthas-packaging:${arthasLegacyVersion.get()}:bin@zip")
        arthasModernPackaging("com.taobao.arthas:arthas-packaging:${arthasModernVersion.get()}:bin@zip")
    }
    val arthasDescriptorDirectory = layout.buildDirectory.dir("generated/arthas-runtime")

    val prepareArthasRuntime by tasks.registering {
        group = "build"
        description = "提取并校验 Java 8–16 与现代 JVM 的 Arthas 最小运行闭包"
        inputs.files(arthasLegacyPackaging, arthasModernPackaging)
        outputs.dir(arthasDescriptorDirectory)
        doLast {
            val runtimes = listOf(
                arthasLegacyVersion.get() to arthasLegacyPackaging.singleFile,
                arthasModernVersion.get() to arthasModernPackaging.singleFile,
            ).distinctBy { it.first }
            runtimes.forEach { (version, archive) ->
                val output = arthasDescriptorDirectory.get().file(version).asFile
                project.delete(output)
                output.mkdirs()
                extractArthasRuntime(archive, output)
            }
            arthasDescriptorDirectory.get().asFile.mkdirs()
            arthasDescriptorDirectory.get().file("runtime.properties").asFile.writeText(
                "legacy-version=${arthasLegacyVersion.get()}\nmodern-version=${arthasModernVersion.get()}\n",
            )
        }
    }

    // ── 二合一 jar：agent 入口 + 子模块合流 + Arthas 闭包注入 ──
    // 跨项目产物一律用 root 目录路径 + 字符串任务依赖（避免配置期递归跨项目 sourceSets 访问）。
    tasks.named<Jar>("jar") {
        archiveBaseName.set(rootProject.name)
        // 声明二合一 jar 的 agent 入口。这些属性经 TabooLib 打包(taboolibMainTask)原样保留(实测):
        // MANIFEST.MF 作为非 class 资源直接透传,不被 ASM 改写。
        manifest {
            attributes(
                "Premain-Class" to "top.wcpe.mc.plugin.serverprobe.agent.ProbeAgent",
                "Agent-Class" to "top.wcpe.mc.plugin.serverprobe.agent.ProbeAgent",
                "Can-Retransform-Classes" to "true",
                "Can-Redefine-Classes" to "true",
            )
        }
        // 将所有 project:* 子模块和 api 的编译输出打包进最终 JAR。
        // 依赖方向：显式 dependsOn 各子模块 classes（字符串路径，惰性解析）。
        val moduleClassDirs = listOf(
            ":api",
            ":project:core",
            ":platform:platform-bukkit",
            ":platform:platform-bungee",
            ":platform:platform-velocity",
            ":integration:integration-multicurrencyeconomy",
            ":integration:integration-allininventorysync",
        )
        dependsOn(moduleClassDirs.map { "$it:classes" })
        moduleClassDirs.forEach { path ->
            val projectDirName = path.split(":").filter(String::isNotBlank).joinToString("/")
            from(rootProject.layout.projectDirectory.dir("$projectDirName/build/classes/java/main"))
            from(rootProject.layout.projectDirectory.dir("$projectDirName/build/classes/kotlin/main"))
        }
        from(rootProject.layout.projectDirectory.dir("diagnostics/diagnostics-arthas/build/classes/kotlin/main"))
        dependsOn(":diagnostics:diagnostics-arthas:classes")
        dependsOn(prepareArthasRuntime)
        listOf(arthasLegacyVersion, arthasModernVersion).forEach { version ->
            from(arthasDescriptorDirectory.map { it.dir(version.get()) }) {
                into("META-INF/serverprobe/arthas/${version.get()}")
            }
        }
        from(arthasDescriptorDirectory.map { it.file("runtime.properties") }) {
            into("META-INF/serverprobe/arthas")
        }
    }

    // ── Java 8 Paper 验收专用的瘦 Agent ──
    //
    // 旧版 Paper 的父优先类加载会从完整 ServerProbe Agent jar 的 system ClassLoader 解析 BukkitPlugin，
    // 进而破坏插件初始化；此产物只保留 Instrumentation 数据桥所需入口，正式单 Jar 分发策略不变。
    val mcpJava8Agent by tasks.registering(Jar::class) {
        group = "verification"
        description = "构建 FR14 Java 8 Paper 验收所需的最小 Instrumentation Agent"
        archiveFileName.set("serverprobe-mcp-java8-agent.jar")
        from(project.extensions.getByType(org.gradle.api.tasks.SourceSetContainer::class.java).getByName("main").output) {
            include("top/wcpe/mc/plugin/serverprobe/agent/ProbeAgent.class")
            include("top/wcpe/mc/plugin/serverprobe/agent/ProbeAgentBridge*.class")
            include("top/wcpe/mc/plugin/serverprobe/agent/BootstrapBridgeInstaller*.class")
        }
        manifest {
            attributes(
                "Premain-Class" to "top.wcpe.mc.plugin.serverprobe.agent.ProbeAgent",
                "Agent-Class" to "top.wcpe.mc.plugin.serverprobe.agent.ProbeAgent",
                "Can-Retransform-Classes" to "true",
                "Can-Redefine-Classes" to "true",
            )
        }
    }

    val verifyMcpJava8Agent by tasks.registering {
        group = "verification"
        description = "校验 FR14 Java 8 瘦 Agent 的类边界与 Agent 清单"
        dependsOn(mcpJava8Agent)
        doLast {
            val artifact = mcpJava8Agent.get().archiveFile.get().asFile
            ZipFile(artifact).use { archive ->
                val entries = buildSet {
                    val iterator = archive.entries()
                    while (iterator.hasMoreElements()) add(iterator.nextElement().name)
                }
                val required = setOf(
                    "top/wcpe/mc/plugin/serverprobe/agent/ProbeAgent.class",
                    "top/wcpe/mc/plugin/serverprobe/agent/ProbeAgentBridge.class",
                    "top/wcpe/mc/plugin/serverprobe/agent/BootstrapBridgeInstaller.class",
                    "META-INF/MANIFEST.MF",
                )
                check(entries.containsAll(required)) { "Java 8 瘦 Agent 缺少必要入口：${required - entries}" }
                check(entries.none { it == "plugin.yml" || it.startsWith("top/wcpe/mc/plugin/serverprobe/taboolib/") }) {
                    "Java 8 瘦 Agent 不得包含 Bukkit 插件实现"
                }
                check(entries.none { it.contains("StartupProfilingTransformer") || it.contains("StartupStackSampler") }) {
                    "Java 8 瘦 Agent 不得携带启动期采集实现"
                }
                val manifest = archive.getInputStream(archive.getEntry("META-INF/MANIFEST.MF")).use { input ->
                    Manifest(input).mainAttributes
                }
                check(manifest.getValue("Premain-Class") == "top.wcpe.mc.plugin.serverprobe.agent.ProbeAgent") {
                    "Java 8 瘦 Agent 缺少 Premain-Class"
                }
                check(manifest.getValue("Agent-Class") == "top.wcpe.mc.plugin.serverprobe.agent.ProbeAgent") {
                    "Java 8 瘦 Agent 缺少 Agent-Class"
                }
                check(manifest.getValue("Can-Retransform-Classes") == "true" && manifest.getValue("Can-Redefine-Classes") == "true") {
                    "Java 8 瘦 Agent 未声明类重转换与重定义能力"
                }
            }
        }
    }

    // ── 发行 Jar 四项校验（挂 check，CI 链） ──

    val verifyArthasRuntimeClosure by tasks.registering {
        group = "verification"
        description = "校验发行 Jar 仅包含经许可的 Arthas 双运行时最小闭包"
        dependsOn(tasks.named("jar"))
        doLast {
            val artifact = tasks.named<Jar>("jar").get().archiveFile.get().asFile
            ZipFile(artifact).use { archive ->
                val iterator = archive.entries()
                val entries = buildSet {
                    while (iterator.hasMoreElements()) add(iterator.nextElement().name)
                }
                val prefixes = setOf(arthasLegacyVersion.get(), arthasModernVersion.get())
                    .map { version -> "META-INF/serverprobe/arthas/$version/" }
                val required = prefixes.flatMap { prefix ->
                    listOf(
                        "${prefix}arthas-core.jar",
                        "${prefix}arthas-boot.jar",
                        "${prefix}arthas-agent.jar",
                        "${prefix}arthas-spy.jar",
                        "${prefix}LICENSE",
                        "${prefix}NOTICE",
                        "${prefix}sha256.properties",
                    )
                }.toSet() + "META-INF/serverprobe/arthas/runtime.properties"
                check(entries.containsAll(required)) { "发行 Jar 缺少 Arthas 运行闭包：${required - entries}" }
                // async-profiler 原生库：仅现代闭包（4.x）应含 Linux x64 库
                val modernPrefix = "META-INF/serverprobe/arthas/${arthasModernVersion.get()}/"
                check(entries.contains("${modernPrefix}libasyncProfiler-linux-x64.so")) {
                    "发行 Jar 现代 Arthas 闭包缺少 libasyncProfiler-linux-x64.so"
                }
                val forbidden = entries.filter { entry ->
                    prefixes.any(entry::startsWith) && (
                        entry.contains("arthas-client", ignoreCase = true) ||
                            entry.contains("arthas-demo", ignoreCase = true) ||
                            entry.contains("math-game", ignoreCase = true) ||
                            entry.endsWith(".bat") || entry.endsWith(".sh") ||
                            entry.contains("/mcp/", ignoreCase = true)
                        )
                }
                check(forbidden.isEmpty()) { "发行 Jar 包含被禁止的 Arthas 上游组件：${forbidden.joinToString()}" }
            }
        }
    }

    val verifyBusinessIntegrationJar by tasks.registering {
        group = "verification"
        description = "校验业务集成实现已合入发行 Jar 且未打包外部 API"
        dependsOn(tasks.named("jar"))
        doLast {
            val artifact = tasks.named<Jar>("jar").get().archiveFile.get().asFile
            val entries = ZipFile(artifact).use { archive ->
                buildSet {
                    val iterator = archive.entries()
                    while (iterator.hasMoreElements()) add(iterator.nextElement().name)
                }
            }
            val required = setOf(
                "top/wcpe/mc/plugin/serverprobe/integration/multicurrencyeconomy/EconomyProvider.class",
                "top/wcpe/mc/plugin/serverprobe/integration/allininventorysync/InventoryProvider.class",
            )
            val missing = required.filterNot(entries::contains)
            check(missing.isEmpty()) { "发行 Jar 缺少业务集成实现：${missing.joinToString()}" }
            val forbidden = entries.filter {
                it.startsWith("top/wcpe/mc/plugin/multicurrencyeconomy/") ||
                    it.startsWith("top/wcpe/mc/plugin/allininventorysync/")
            }
            check(forbidden.isEmpty()) { "发行 Jar 不得打包外部业务 API：${forbidden.joinToString()}" }
            val legacy = entries.filter {
                it.startsWith("top/wcpe/mc/plugin/serverprobe/bukkit/business/") && it.endsWith(".class")
            }
            check(legacy.isEmpty()) { "发行 Jar 不得保留旧 Bukkit 业务实现：${legacy.joinToString()}" }
        }
    }

    val verifyVelocityDescriptorDependencies by tasks.registering {
        group = "verification"
        description = "校验 Velocity 描述符不声明 Bukkit 专属业务插件依赖"
        dependsOn(tasks.named("jar"))
        doLast {
            val artifact = tasks.named<Jar>("jar").get().archiveFile.get().asFile
            val descriptor = ZipFile(artifact).use { archive ->
                val entry = archive.getEntry("velocity-plugin.json") ?: error("发行 Jar 缺少 velocity-plugin.json")
                archive.getInputStream(entry).bufferedReader().use { it.readText() }
            }
            val dependencyIds = Regex("\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .findAll(descriptor)
                .map { it.groupValues[1] }
                .toList()
            check(dependencyIds.all { it == it.lowercase() }) {
                "Velocity 依赖 ID 必须全小写：${dependencyIds.joinToString()}"
            }
            check("MultiCurrencyEconomy" !in dependencyIds && "AllinInventorySync" !in dependencyIds) {
                "Velocity 描述符不得声明 Bukkit 专属业务插件依赖：${dependencyIds.joinToString()}"
            }
        }
    }

    val verifySqliteJdbcPackaging by tasks.registering {
        group = "verification"
        description = "校验发行 Jar 内置 SQLite JDBC 驱动及其原生库，避免运行期联网下载"
        dependsOn(tasks.named("jar"))
        doLast {
            val artifact = tasks.named<Jar>("jar").get().archiveFile.get().asFile
            val entries = ZipFile(artifact).use { archive ->
                buildSet {
                    val iterator = archive.entries()
                    while (iterator.hasMoreElements()) add(iterator.nextElement().name)
                }
            }
            check("org/sqlite/JDBC.class" in entries) { "发行 Jar 缺少 SQLite JDBC 驱动" }
            check(entries.any { it.startsWith("org/sqlite/native/") }) { "发行 Jar 缺少 SQLite 原生库" }
        }
    }

    tasks.named("check") {
        dependsOn(verifyBusinessIntegrationJar)
        dependsOn(verifyArthasRuntimeClosure)
        dependsOn(verifySqliteJdbcPackaging)
        dependsOn(verifyVelocityDescriptorDependencies)
}
