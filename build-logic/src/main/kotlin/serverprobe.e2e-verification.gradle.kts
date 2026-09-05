/**
 * ServerProbe 的 mc-testkit E2E 编排约定（仅 root 应用）。
 *
 * 承载：
 * - 应用 top.wcpe.mc-testkit 并声明全部 mcTestkit{} 拓扑（backends/proxies/scenarios/serve）
 * - e2e 场景配置注入：模板文件(e2e/templates/configs/<scenario>/) + 占位符替换，脚本内禁止内嵌 YAML
 * - FR9/FR10/FR12/FR14 的 prepare 系列任务与 e2e 任务接线
 * - FR10 离线 TabooLib 运行时的组装与校验
 * - 被测发行 jar 文件名与 mc-testkit 运行目录的统一定义（本插件唯一真源）
 *
 * 跨项目任务引用一律用路径字符串，避免 root 配置期跨项目 tasks.named 的时序坑。
 */
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties

apply(plugin = "top.wcpe.mc-testkit")

// ── 被测发行 jar 与运行目录（本插件唯一真源，供拓扑与 prepare 任务共用）──
// 文件名由 archiveBaseName(rootProject.name) + "-" + version(gradle.properties) 组成，禁止硬编码版本号。
val pluginJarName = "${project.name}-${version}.jar"
val pluginJarPath = "plugin/build/libs/$pluginJarName"
val pluginJarFileProvider = project.layout.projectDirectory.file(pluginJarPath)
val pluginJarFile: File = pluginJarFileProvider.asFile
val e2eRunDirectory = project.layout.buildDirectory.dir("mc-testkit/run")

// ── mc-testkit 拓扑声明 ──
// harness 产物由收编后的子项目任务构建（原 9 个 Exec 调子 gradlew 的 hack 已删除）：
//   :e2e:harness:jar、:e2e:harness-network-*:jar、:e2e:harness-velocity-matrix:jar、:e2e:harness-mcp-*:jar
// 产物路径不变（e2e/<dir>/build/libs/mc-testkit-*-harness-1.0.0-SNAPSHOT.jar），mcTestkit 路径引用不受影响。

val mcTestkit = extensions.getByType(top.wcpe.mc.testkit.dsl.McTestkitExtension::class.java)
mcTestkit.apply {
    backend("paper") {
        platform = paper
        version = "1.20.1"
        // 25575 与用户测试服(luminol)冲突,改用 25620。
        port = 25620
    }
    backend("paper-mcp") {
        platform = paper
        version = "1.20.1"
        port = 25577
        javaAgent("plugin/build/libs/$pluginJarName")
    }
    backend("paper-mcp-java21") {
        platform = paper
        version = "1.20.1"
        port = 25601
        javaAgent("plugin/build/libs/$pluginJarName")
        templateDirectory("e2e/templates/mcp-paper")
        env("SERVERPROBE_MCP_PORT", "19876")
    }
    backend("folia-mcp") {
        platform = folia
        version = "1.21.4"
        port = 25602
        templateDirectory("e2e/templates/mcp-folia")
        env("SERVERPROBE_MCP_PORT", "19877")
    }
    backend("paper-mcp-java8") {
        platform = paper
        version = "1.16.5"
        port = 25603
        // Paper 1.16 的父优先类加载会把完整 Jar 内的 BukkitPlugin 当作系统类加载；此处仅使用瘦 Agent。
        javaAgent("plugin/build/libs/serverprobe-mcp-java8-agent.jar")
        templateDirectory("e2e/templates/mcp-java8")
        env("SERVERPROBE_MCP_PORT", "19880")
    }
    backend("paper-mcp-proxy") {
        platform = paper
        version = "1.20.1"
        port = 25604
    }
    backend("paper-integrations") {
        platform = paper
        version = "1.20.1"
        port = 25591
        // FR10 离线运行验收：阻断插件运行期经 HTTP/HTTPS 访问远端仓库。
        jvmArg("-Dhttp.proxyHost=127.0.0.1")
        jvmArg("-Dhttp.proxyPort=9")
        jvmArg("-Dhttps.proxyHost=127.0.0.1")
        jvmArg("-Dhttps.proxyPort=9")
    }
    backend("folia") {
        platform = folia
        version = "1.21.4"
        port = 25576
    }
    backend("spigot-network") {
        platform = spigot
        version = "1.20.1"
        port = 25578
        templateDirectory("e2e/templates/network-bukkit")
    }
    backend("spigot-mcp") {
        platform = spigot
        version = "1.20.1"
        port = 25607
        templateDirectory("e2e/templates/mcp-spigot")
        env("SERVERPROBE_MCP_PORT", "19881")
    }
    backend("paper-network") {
        platform = paper
        version = "1.20.1"
        port = 25592
        templateDirectory("e2e/templates/network-bukkit")
    }
    backend("folia-network") {
        platform = folia
        version = "1.21.4"
        port = 25593
        // Folia 1.21.4 运行时为 Mojang 映射，入站自定义载荷类名与 Spigot/Paper 1.20 不同。
        templateDirectory("e2e/templates/network-folia")
    }
    // ── 多版本矩阵（matrix-*）：read-api 只读冒烟 × 版本 × Java 运行时，逐版本验证单 jar 加载与采集 ──
    backend("matrix-paper-1-8-8") {
        platform = paper
        version = "1.8.8"
        port = 25610
    }
    backend("matrix-paper-1-12-2") {
        platform = paper
        version = "1.12.2"
        port = 25611
    }
    backend("matrix-paper-1-16-5") {
        platform = paper
        version = "1.16.5"
        port = 25612
    }
    backend("matrix-paper-1-17-1") {
        platform = paper
        version = "1.17.1"
        port = 25613
    }
    backend("matrix-paper-1-18-2") {
        platform = paper
        version = "1.18.2"
        port = 25614
    }
    backend("matrix-paper-1-19-4") {
        platform = paper
        version = "1.19.4"
        port = 25615
    }
    backend("matrix-paper-1-20-4") {
        platform = paper
        version = "1.20.4"
        port = 25616
    }
    backend("matrix-paper-1-21-1") {
        platform = paper
        version = "1.21.1"
        port = 25617
    }
    backend("matrix-spigot-1-8-8") {
        platform = spigot
        version = "1.8.8"
        port = 25618
    }
    backend("matrix-spigot-1-16-5") {
        platform = spigot
        version = "1.16.5"
        port = 25619
    }
    backend("paper-network-velocity") {
        platform = paper
        version = "1.20.1"
        port = 25581
        templateDirectory("e2e/templates/network-bukkit")
    }
    backend("paper-velocity-matrix-v31-a") {
        platform = paper
        version = "1.18.1"
        port = 25582
    }
    backend("paper-velocity-matrix-v31-b") {
        platform = paper
        version = "1.18.1"
        port = 25583
    }
    backend("paper-velocity-matrix-v35-a") {
        platform = paper
        version = "1.20.1"
        port = 25585
    }
    backend("paper-velocity-matrix-v35-b") {
        platform = paper
        version = "1.20.1"
        port = 25586
    }
    backend("paper-velocity-matrix-v41-a") {
        platform = paper
        version = "1.20.1"
        port = 25588
    }
    backend("paper-velocity-matrix-v41-b") {
        platform = paper
        version = "1.20.1"
        port = 25589
    }
    proxy("bungee-network") {
        platform = bungeecord
        port = 25579
        routesTo("spigot-network")
        templateDirectory("e2e/templates/network-bungee")
        plugin("plugin/build/libs/$pluginJarName")
        plugin("e2e/harness-network-bungee/build/libs/mc-testkit-network-bungee-harness-1.0.0-SNAPSHOT.jar")
    }
    proxy("velocity-network") {
        platform = velocity
        port = 25580
        routesTo("paper-network-velocity")
        templateDirectory("e2e/templates/network-velocity")
        plugin("plugin/build/libs/$pluginJarName")
        plugin("e2e/harness-network-velocity/build/libs/mc-testkit-network-velocity-harness-1.0.0-SNAPSHOT.jar")
    }
    proxy("bungee-mcp") {
        platform = bungeecord
        port = 25605
        routesTo("paper-mcp-proxy")
        templateDirectory("e2e/templates/mcp-bungee")
        env("SERVERPROBE_MCP_PORT", "19878")
        plugin("plugin/build/libs/$pluginJarName")
        plugin("e2e/harness-mcp-bungee/build/libs/mc-testkit-mcp-bungee-harness-1.0.0-SNAPSHOT.jar")
    }
    proxy("velocity-mcp") {
        platform = velocity
        version = "3.1.1"
        port = 25606
        routesTo("paper-mcp-proxy")
        templateDirectory("e2e/templates/mcp-velocity")
        env("SERVERPROBE_MCP_PORT", "19879")
        plugin("plugin/build/libs/$pluginJarName")
        plugin("e2e/harness-mcp-velocity/build/libs/mc-testkit-mcp-velocity-harness-1.0.0-SNAPSHOT.jar")
    }
    proxy("velocity-mcp-java25") {
        platform = velocity
        version = "4.1.0"
        javaVersion = 25
        port = 25608
        routesTo("paper-mcp-proxy")
        templateDirectory("e2e/templates/mcp-velocity-java25")
        env("SERVERPROBE_MCP_PORT", "19882")
        javaAgent("plugin/build/libs/$pluginJarName")
        plugin("plugin/build/libs/$pluginJarName")
        plugin("e2e/harness-mcp-velocity/build/libs/mc-testkit-mcp-velocity-harness-1.0.0-SNAPSHOT.jar")
    }
    proxy("velocity-matrix-v31") {
        platform = velocity
        version = "3.1.1"
        port = 25584
        routesTo("paper-velocity-matrix-v31-a", "paper-velocity-matrix-v31-b")
        plugin("plugin/build/libs/$pluginJarName")
        plugin("e2e/harness-velocity-matrix/build/libs/mc-testkit-velocity-matrix-harness-1.0.0-SNAPSHOT.jar")
    }
    proxy("velocity-matrix-v35") {
        platform = velocity
        version = "3.5.1"
        port = 25587
        routesTo("paper-velocity-matrix-v35-a", "paper-velocity-matrix-v35-b")
        plugin("plugin/build/libs/$pluginJarName")
        plugin("e2e/harness-velocity-matrix/build/libs/mc-testkit-velocity-matrix-harness-1.0.0-SNAPSHOT.jar")
    }
    proxy("velocity-matrix-v41") {
        platform = velocity
        version = "4.1.0"
        javaVersion = 25
        port = 25590
        routesTo("paper-velocity-matrix-v41-a", "paper-velocity-matrix-v41-b")
        plugin("plugin/build/libs/$pluginJarName")
        plugin("e2e/harness-velocity-matrix/build/libs/mc-testkit-velocity-matrix-harness-1.0.0-SNAPSHOT.jar")
    }
    scenario("read-api") { backend = "paper" }
    scenario("storage-spi") { backend = "paper" }
    scenario("bridge-fixture") { backend = "paper" }
    scenario("integrations-both") {
        backend = "paper-integrations"
        bot {
            username = "SpInventory"
            action = "integrations"
        }
    }
    scenario("integrations-mce-only") { backend = "paper-integrations" }
    scenario("integrations-ais-only") {
        backend = "paper-integrations"
        bot {
            username = "SpInventory"
            action = "integrations"
        }
    }
    scenario("integrations-none") { backend = "paper-integrations" }
    scenario("mcp-diagnostics-paper") { backend = "paper-mcp" }
    scenario("mcp-diagnostics-paper-java21") { backend = "paper-mcp-java21" }
    scenario("mcp-diagnostics-folia") { backend = "folia-mcp" }
    scenario("mcp-diagnostics-java8") { backend = "paper-mcp-java8" }
    scenario("mcp-diagnostics-spigot") { backend = "spigot-mcp" }
    scenario("mcp-diagnostics-bungee") {
        backend = "paper-mcp-proxy"
        via = "bungee-mcp"
    }
    scenario("mcp-diagnostics-velocity") {
        backend = "paper-mcp-proxy"
        via = "velocity-mcp"
    }
    scenario("mcp-diagnostics-velocity-java25") {
        backend = "paper-mcp-proxy"
        via = "velocity-mcp-java25"
    }
    scenario("folia-observed-regions") {
        backend = "folia"
        bot {
            username = "FoliaProbe"
            action = "folia-observed-regions"
            count = 2
        }
    }
    // 多版本矩阵场景：与 FR8 read-api 同判据（真实快照 + 启动画像），由 harness 的 matrix- 前缀识别。
    scenario("matrix-paper-1-8-8") { backend = "matrix-paper-1-8-8" }
    scenario("matrix-paper-1-12-2") { backend = "matrix-paper-1-12-2" }
    scenario("matrix-paper-1-16-5") { backend = "matrix-paper-1-16-5" }
    scenario("matrix-paper-1-17-1") { backend = "matrix-paper-1-17-1" }
    scenario("matrix-paper-1-18-2") { backend = "matrix-paper-1-18-2" }
    scenario("matrix-paper-1-19-4") { backend = "matrix-paper-1-19-4" }
    scenario("matrix-paper-1-20-4") { backend = "matrix-paper-1-20-4" }
    scenario("matrix-paper-1-21-1") { backend = "matrix-paper-1-21-1" }
    scenario("matrix-spigot-1-8-8") { backend = "matrix-spigot-1-8-8" }
    scenario("matrix-spigot-1-16-5") { backend = "matrix-spigot-1-16-5" }
    scenario("network-forensics-bukkit") {
        // Bukkit 平台的既有权威结果实际运行于 Spigot 后端。
        backend = "spigot-network"
        bot {
            username = "Fr11Probe"
            action = "network-forensics"
        }
    }
    scenario("network-forensics-paper") {
        backend = "paper-network"
        bot {
            username = "Fr11Probe"
            action = "network-forensics"
        }
    }
    scenario("network-forensics-folia") {
        backend = "folia-network"
        bot {
            username = "Fr11Probe"
            action = "network-forensics"
        }
    }
    scenario("network-forensics-bungee") {
        backend = "spigot-network"
        via = "bungee-network"
        bot {
            username = "Fr11Probe"
            action = "network-forensics"
        }
    }
    scenario("network-forensics-velocity") {
        backend = "paper-network-velocity"
        via = "velocity-network"
        bot {
            username = "Fr11Probe"
            action = "network-forensics"
        }
    }
    scenario("velocity-matrix-v31") {
        backends("paper-velocity-matrix-v31-a", "paper-velocity-matrix-v31-b")
        via = "velocity-matrix-v31"
        bot("switcher") {
            username = "Vv31Switcher"
            action = "velocity-matrix"
            env("SP_MATRIX_ROLE", "switcher")
            env("SP_MATRIX_TARGET_BACKEND", "paper-velocity-matrix-v31-b")
        }
        bot("observer") {
            username = "Vv31Observer"
            action = "velocity-matrix"
            env("SP_MATRIX_ROLE", "observer")
        }
    }
    scenario("velocity-matrix-v35") {
        backends("paper-velocity-matrix-v35-a", "paper-velocity-matrix-v35-b")
        via = "velocity-matrix-v35"
        bot("switcher") {
            username = "Vv35Switcher"
            action = "velocity-matrix"
            env("SP_MATRIX_ROLE", "switcher")
            env("SP_MATRIX_TARGET_BACKEND", "paper-velocity-matrix-v35-b")
        }
        bot("observer") {
            username = "Vv35Observer"
            action = "velocity-matrix"
            env("SP_MATRIX_ROLE", "observer")
        }
    }
    scenario("velocity-matrix-v41") {
        backends("paper-velocity-matrix-v41-a", "paper-velocity-matrix-v41-b")
        via = "velocity-matrix-v41"
        bot("switcher") {
            username = "Vv41Switcher"
            action = "velocity-matrix"
            env("SP_MATRIX_ROLE", "switcher")
            env("SP_MATRIX_TARGET_BACKEND", "paper-velocity-matrix-v41-b")
        }
        bot("observer") {
            username = "Vv41Observer"
            action = "velocity-matrix"
            env("SP_MATRIX_ROLE", "observer")
        }
    }
    // 开发期手动起服（等价原 run-paper runServer）：Paper 1.21.4 + javaagent 自挂载 + Xmx2G
    backend("paper-dev") {
        platform = paper
        version = "1.21.4"
        port = 25630
        javaAgent("plugin/build/libs/$pluginJarName")
        jvmArg("-Xmx2G")
    }
    serve("dev") { backend = "paper-dev" }

    dependencies {
        pluginUnderTest = "plugin/build/libs/$pluginJarName"
        plugin("e2e/harness/build/libs/mc-testkit-e2e-harness-1.0.0-SNAPSHOT.jar")
        plugin("e2e/harness-network-bukkit/build/libs/mc-testkit-network-bukkit-harness-1.0.0-SNAPSHOT.jar")
    }
}

// ── e2e 场景配置注入统一工具：模板文件 + 占位符替换，禁止在脚本内嵌 YAML 字符串 ──

/** 读取 e2e/templates/configs/<scenario>/ 下的配置模板，替换占位符后写入 mc-testkit 运行目录。 */
private fun Project.installE2eConfig(
    scenario: String,
    targetRelativePath: String,
    replacements: Map<String, String> = emptyMap(),
) {
    val template = rootProject.layout.projectDirectory.file("e2e/templates/configs/$scenario/$targetRelativePath").asFile
    check(template.isFile) { "e2e 配置模板缺失：${template.relativeTo(rootProject.layout.projectDirectory.asFile)}" }
    val target = e2eRunDirectory.get().file(targetRelativePath).asFile
    target.parentFile.mkdirs()
    var content = template.readText()
    replacements.forEach { (key, value) -> content = content.replace(key, value) }
    target.writeText(content)
}

/** 为 e2e 任务统一挂 prepare 依赖（支持任务路径字符串与 TaskProvider）。 */
private fun Project.wireE2eDependency(e2eTaskName: String, vararg prepare: Any) {
    tasks.matching { it.name == e2eTaskName }.configureEach { dependsOn(*prepare) }
}

// ── harness 产物接线：prepareE2e* 任务依赖收编后的子项目 jar 任务 ──
// （原 9 个 Exec 调子 gradlew 的 hack 已删除，产物由子项目任务直接构建）

tasks.matching { it.name.startsWith("prepareE2e") }.configureEach {
    dependsOn(":e2e:harness:jar")
}

// 清理共享 run/libraries 中的离线闭包残留：FR10 集成场景会把 TabooLib 运行库注入标准
// libraries/（mc-testkit 仅扫描该目录），而 RunLayout 将其列为保留缓存、场景间不清除，
// 残留库会被后续 spigot 等场景的 classpath 扫描到并破坏启动（如 org.bukkit.Registry 冲突）。
// 非集成场景 prepare 前先清空，避免污染；集成场景自身仍注入标准目录。
val cleanupIntegrationsLibraries by tasks.registering {
    group = "verification"
    description = "清理共享 run/libraries 中 FR10 离线闭包残留，防止污染其他场景"
    outputs.upToDateWhen { false }
    doLast {
        val librariesDir = e2eRunDirectory.get().file("libraries").asFile
        if (librariesDir.isDirectory && !librariesDir.deleteRecursively()) {
            logger.warn("无法清理残留运行库目录：${librariesDir.absolutePath}")
        }
    }
}
tasks.matching {
    it.name.startsWith("prepareE2e") && !it.name.contains("Integrations")
}.configureEach {
    dependsOn(cleanupIntegrationsLibraries)
}
tasks.matching { it.name.startsWith("prepareE2eFr11") }.configureEach {
    dependsOn(":e2e:harness-network-bukkit:jar", ":e2e:harness-network-bungee:jar", ":e2e:harness-network-velocity:jar")
}
tasks.matching { it.name.startsWith("prepareE2eFr13") }.configureEach {
    dependsOn(":e2e:harness-velocity-matrix:jar")
}

// ── FR9 桥 fixture ──

val prepareBridgeFixture by tasks.registering {
    group = "verification"
    description = "为 FR9 回环 Worker fixture 生成一次性本机桥配置"
    dependsOn("prepareE2eBridgeFixture")
    outputs.upToDateWhen { false }
    doLast {
        val tokenBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        installE2eConfig(
            "bridge-fixture",
            "plugins/ServerProbe/config.yml",
            mapOf("__PORT__" to port.toString(), "__TOKEN__" to token),
        )
    }
}

wireE2eDependency("e2eBridgeFixture", prepareBridgeFixture)

// ── FR10 离线 TabooLib 运行时（真实业务插件 + 运行库离线闭包）──

/** MultiCurrencyEconomy 运行期表达式引擎的精确 Maven 闭包。 */
private data class OfflineMavenArtifact(
    val group: String,
    val name: String,
    val version: String,
    val extension: String,
) {
    val coordinate: String = "$group:$name:$version@$extension"
    val relativePath: String = "${group.replace('.', '/')}/$name/$version/$name-$version.$extension"
}

private val offlineMceRuntimeArtifacts = listOf(
    OfflineMavenArtifact("org.apache.commons", "commons-jexl3", "3.2.1", "jar"),
    OfflineMavenArtifact("org.apache.commons", "commons-jexl3", "3.2.1", "pom"),
    OfflineMavenArtifact("commons-logging", "commons-logging", "1.2", "jar"),
    OfflineMavenArtifact("commons-logging", "commons-logging", "1.2", "pom"),
    OfflineMavenArtifact("org.apache.commons", "commons-parent", "52", "pom"),
    OfflineMavenArtifact("org.apache", "apache", "23", "pom"),
    OfflineMavenArtifact("org.apache.commons", "commons-parent", "34", "pom"),
    OfflineMavenArtifact("org.apache", "apache", "13", "pom"),
)

/* 父 POM 存在多个版本，分别解析以避免 Gradle 冲突裁决遗漏历史父链。 */
private val offlineMceRuntimeClosures = offlineMceRuntimeArtifacts.associateWith { artifact ->
    configurations.detachedConfiguration(dependencies.create(artifact.coordinate)).apply {
        isTransitive = false
    }
}

val offlineTabooRuntimeDirectory = rootProject.layout.buildDirectory.dir("offline-taboolib-runtime")

/** 将已解析的运行库和真实业务插件整理为可直接解压的离线运行时包。 */
val prepareOfflineTabooRuntime by tasks.registering {
    group = "verification"
    description = "生成含真实业务插件与 TabooLib 运行库的离线 mc-testkit 运行时包"
    dependsOn(":plugin:jar")
    outputs.dir(offlineTabooRuntimeDirectory)
    outputs.upToDateWhen { false }
    doLast {
        val externalPlugins = listOf(
            requiredE2eJar("SERVERPROBE_E2E_CORELIB_JAR"),
            requiredE2eJar("SERVERPROBE_E2E_MCE_JAR"),
            requiredE2eJar("SERVERPROBE_E2E_AIS_JAR"),
        )
        val output = offlineTabooRuntimeDirectory.get().asFile
        if (output.exists() && !output.deleteRecursively()) {
            error("无法清理离线 TabooLib 运行时目录：${output.absolutePath}")
        }
        val plugins = File(output, "plugins").apply { mkdirs() }
        externalPlugins.forEach { plugin -> plugin.copyTo(File(plugins, plugin.name), overwrite = true) }

        val libraries = File(output, "libraries")
        copyOfflineRuntimeLibraries(offlineTabooRuntimeLibrarySource(), libraries)
        copyRequiredTabooLibModules(libraries)
        copyReflexClosure(libraries)
        copyMceRuntimeClosure(libraries)
        writeOfflineRuntimeManifest(output)
    }
}

/** 验证 MCE 运行期依赖及其 POM 父链均由离线包明确提供。 */
val verifyOfflineMceRuntimeClosure by tasks.registering {
    group = "verification"
    description = "验证离线运行时包含 MultiCurrencyEconomy 的完整表达式引擎闭包"
    dependsOn(prepareOfflineTabooRuntime)
    doLast {
        val libraries = File(offlineTabooRuntimeDirectory.get().asFile, "libraries")
        offlineMceRuntimeArtifacts.forEach { artifact ->
            val target = File(libraries, artifact.relativePath)
            check(target.isFile) { "离线运行时缺少 MCE 依赖：${artifact.coordinate}" }
            val sidecar = File("${target.absolutePath}.sha1")
            check(sidecar.readText().trim() == target.sha1()) {
                "离线运行时校验和不匹配：${artifact.coordinate}"
            }
        }
    }
}

/** 运行库源可显式覆盖，默认复用 mc-testkit 已解析的本地运行库缓存。 */
private fun Project.offlineTabooRuntimeLibrarySource(): File {
    val configured = providers.gradleProperty("offlineTaboolibRuntimeLibrariesDir").orNull
    val defaultSource = rootProject.layout.buildDirectory.dir("mc-testkit/run/libraries").get().asFile.absolutePath
    val source = File(configured ?: defaultSource).canonicalFile
    return source.takeIf(File::isDirectory)
        ?: error("离线 TabooLib 运行库目录不存在：${source.absolutePath}；请先提供 -PofflineTaboolibRuntimeLibrariesDir")
}

/** 覆盖运行目录中的旧缓存，确保验收从离线包而不是历史下载缓存启动。 */
private fun copyOfflineRuntimeLibraries(source: File, target: File) {
    if (target.exists() && !target.deleteRecursively()) {
        error("无法清理离线运行库目标目录：${target.absolutePath}")
    }
    check(source.copyRecursively(target, overwrite = true)) {
        "无法复制离线运行库：${source.absolutePath} → ${target.absolutePath}"
    }
}

/** 离线闭包统一使用的 TabooLib 版本（与 ServerProbe 自身编译版本一致）。 */
private val OFFLINE_TABOOLIB_VERSION = "6.3.0-wcpe.1"

/** 离线闭包统一收集的 TabooLib 模块清单（wcpe.1 全套，含隐式基础模块）。 */
private val offlineTabooLibModules = listOf(
    "basic-configuration", "basic-submit-chain", "bukkit-hook", "bukkit-nms", "bukkit-nms-legacy",
    "bukkit-nms-stable", "bukkit-nms-tag", "bukkit-nms-tag-legacy", "bukkit-nms-tag-modern",
    "bukkit-ui", "bukkit-ui-12100", "bukkit-ui-legacy", "bukkit-util", "bukkit-xseries",
    "common", "common-env", "common-legacy-api", "common-platform-api", "common-reflex", "common-util",
    "incision", "minecraft-chat", "minecraft-command-helper", "minecraft-i18n",
    "platform-bukkit", "platform-bukkit-impl", "platform-bungee", "platform-bungee-impl",
    "platform-velocity", "platform-velocity-impl",
)

/** 从 Gradle 本地缓存复制统一版本（wcpe.1）的 TabooLib 全套模块到标准 libraries 路径。 */
private fun Project.copyRequiredTabooLibModules(libraries: File) {
    // 先清掉 run/libraries 源带来的多版本残留，只保留 wcpe.1 一套。
    File(libraries, "io/izzel/taboolib").takeIf(File::isDirectory)?.deleteRecursively()
    offlineTabooLibModules.forEach { module ->
        val artifactName = "$module-$OFFLINE_TABOOLIB_VERSION.jar"
        val cacheRoot = File(
            gradle.gradleUserHomeDir,
            "caches/modules-2/files-2.1/io.izzel.taboolib/$module/$OFFLINE_TABOOLIB_VERSION",
        )
        val artifact = cacheRoot.walkTopDown()
            .filter { it.isFile && it.name == artifactName }
            .sortedBy { it.absolutePath }
            .firstOrNull()
            ?: error("本机 Gradle 缓存缺少 TabooLib 离线模块：io.izzel.taboolib:$module:$OFFLINE_TABOOLIB_VERSION")
        val target = File(libraries, "io/izzel/taboolib/$module/$OFFLINE_TABOOLIB_VERSION/$artifactName")
        target.parentFile.mkdirs()
        artifact.copyTo(target, overwrite = true)
    }
}

/** 只从构建依赖解析结果复制 MCE 必需闭包，运行期不再解析仓库。 */
private fun copyMceRuntimeClosure(libraries: File) {
    offlineMceRuntimeArtifacts.forEach { artifact ->
        val sourceName = "${artifact.name}-${artifact.version}.${artifact.extension}"
        val source = offlineMceRuntimeClosures.getValue(artifact).resolve()
            .singleOrNull { it.name == sourceName }
            ?: error("构建依赖缺少 MCE 离线闭包：${artifact.coordinate}")
        val target = File(libraries, artifact.relativePath)
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
    }
}

/**
 * 统一收集与 TabooLib 6.3.0-wcpe.1 配套的 reflex（reflex + analyser，版本 1.2.5-wcpe.1），
 * 只放一套。jar 来自 mc-testkit 持久缓存（首次由 wcpe.top 仓库下载后缓存）。
 */
private fun copyReflexClosure(libraries: File) {
    val reflexVersion = "1.2.5-wcpe.1"
    // 先清掉 run/libraries 源带来的多版本残留，只保留 wcpe.1 配套的 reflex 一套。
    File(libraries, "org/tabooproject/reflex").takeIf(File::isDirectory)?.deleteRecursively()
    listOf("reflex", "analyser").forEach { artifactName ->
        val cacheDir = File(
            gradle.gradleUserHomeDir,
            "caches/mc-testkit-jars/reflex/$reflexVersion",
        )
        val jar = File(cacheDir, "$artifactName-$reflexVersion.jar")
            .takeIf(File::isFile)
            ?: error("缺少 reflex 离线闭包：org.tabooproject.reflex:$artifactName:$reflexVersion（请从 wcpe.top 仓库下载到 ${cacheDir.absolutePath}）")
        val target = File(libraries, "org/tabooproject/reflex/$artifactName/$reflexVersion/${jar.name}")
        target.parentFile.mkdirs()
        jar.copyTo(target, overwrite = true)
    }
}

/** 为每个归档与 Maven 描述生成 SHA-1 旁车文件，并输出便于审计的离线清单。 */
private fun writeOfflineRuntimeManifest(output: File) {
    val archives = output.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in setOf("jar", "pom") }
        .sortedBy { it.relativeTo(output).invariantSeparatorsPath }
        .toList()
    val entries = archives.map { archive ->
        val relative = archive.relativeTo(output).invariantSeparatorsPath
        val sha1 = archive.sha1()
        File("${archive.absolutePath}.sha1").writeText("$sha1\n")
        "$relative\t$sha1"
    }
    File(output, "MANIFEST.tsv").writeText((listOf("路径\tSHA-1") + entries).joinToString("\n") + "\n")
}

private fun File.sha1(): String = MessageDigest.getInstance("SHA-1").run {
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            update(buffer, 0, read)
        }
    }
    digest().joinToString("") { byte -> "%02x".format(byte) }
}

// ── FR10 集成场景配置注入 ──

/** FR10 各组合保留的真实外部插件；所有输入仅由运行环境变量提供。 */
private data class IntegrationsPluginSet(
    val mce: Boolean,
    val ais: Boolean,
)

private fun Project.prepareIntegrationsRuntime(
    taskName: String,
    e2eTaskName: String,
    plugins: IntegrationsPluginSet,
) = tasks.register(taskName) {
    group = "verification"
    description = "为 $e2eTaskName 准备真实业务插件组合与一次性回环桥配置"
    dependsOn("prepareE2e$e2eTaskName", prepareOfflineTabooRuntime)
    outputs.upToDateWhen { false }
    doLast {
        // 离线闭包注入标准 libraries/（mc-testkit ServerLauncher 仅扫描该目录加载 TabooLib 模块）。
        // 非集成场景 prepare 前会经 cleanupIntegrationsLibraries 清空此处残留，避免污染。
        copyOfflineRuntimeLibraries(
            File(offlineTabooRuntimeDirectory.get().asFile, "libraries"),
            e2eRunDirectory.get().file("libraries").asFile,
        )
        val pluginsDirectory = e2eRunDirectory.get().file("plugins").asFile
        if (plugins.mce || plugins.ais) {
            copyE2ePlugin(requiredE2eJar("SERVERPROBE_E2E_CORELIB_JAR"), pluginsDirectory)
        }
        if (plugins.mce) {
            copyE2ePlugin(requiredE2eJar("SERVERPROBE_E2E_MCE_JAR"), pluginsDirectory)
        }
        if (plugins.ais) {
            copyE2ePlugin(requiredE2eJar("SERVERPROBE_E2E_AIS_JAR"), pluginsDirectory)
        }
        writeIntegrationsConfigurations()
    }
}

/** 真实外部插件仅复制到其对应集成场景，不能成为其他 E2E 的全局前置条件。 */
private fun copyE2ePlugin(source: File, pluginsDirectory: File) {
    source.copyTo(File(pluginsDirectory, source.name), overwrite = true)
}

/** 缺少真实插件构件时在服务端启动前明确失败，避免用替身或旧文件悄然通过。 */
private fun Project.requiredE2eJar(name: String): File {
    // 用 System.getenv 而非 providers.environmentVariable：included build 的 ProviderFactory
    // 在 --no-daemon 单次构建中可能快照到 shell export 前的环境，System.getenv 直接读当前进程。
    val location = System.getenv(name) ?: error("缺少真实插件环境变量:$name")
    return File(location).takeIf(File::isFile) ?: error("真实插件构件不存在:$name")
}

/** 只写入可公开的 H2 测试配置与随机回环凭据；不落盘任何真实地址或密钥。 */
private fun Project.writeIntegrationsConfigurations() {
    val tokenBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
    val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
    val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    listOf(
        "plugins/ServerProbe/config.yml",
        "plugins/CoreLib/config.yml",
        "plugins/MultiCurrencyEconomy/config.yml",
        "plugins/AllinInventorySync/config.yml",
    ).forEach { relative ->
        installE2eConfig("integrations", relative, mapOf("__PORT__" to port.toString(), "__TOKEN__" to token))
    }
}

val prepareIntegrationsBoth by prepareIntegrationsRuntime(
    "prepareIntegrationsBoth",
    "IntegrationsBoth",
    IntegrationsPluginSet(mce = true, ais = true),
)
val prepareIntegrationsMceOnly by prepareIntegrationsRuntime(
    "prepareIntegrationsMceOnly",
    "IntegrationsMceOnly",
    IntegrationsPluginSet(mce = true, ais = false),
)
val prepareIntegrationsAisOnly by prepareIntegrationsRuntime(
    "prepareIntegrationsAisOnly",
    "IntegrationsAisOnly",
    IntegrationsPluginSet(mce = false, ais = true),
)
val prepareIntegrationsNone by prepareIntegrationsRuntime(
    "prepareIntegrationsNone",
    "IntegrationsNone",
    IntegrationsPluginSet(mce = false, ais = false),
)

listOf(
    "e2eIntegrationsBoth" to prepareIntegrationsBoth,
    "e2eIntegrationsMceOnly" to prepareIntegrationsMceOnly,
    "e2eIntegrationsAisOnly" to prepareIntegrationsAisOnly,
    "e2eIntegrationsNone" to prepareIntegrationsNone,
).forEach { (taskName, prepare) -> wireE2eDependency(taskName, prepare) }

/** FR10 的 AIS 验收依赖真实在线玩家，默认 E2E 入口必须先启动其声明的 bot。 */
wireE2eDependency("e2eIntegrationsBoth", "launchIntegrationsBothBot")
wireE2eDependency("e2eIntegrationsAisOnly", "launchIntegrationsAisOnlyBot")

// ── FR12 Folia 场景配置注入 ──

val prepareFoliaObservedRegionsConfiguration by tasks.registering {
    group = "verification"
    description = "为 FR12 Folia 场景写入快速采样与 region 过期配置"
    dependsOn("prepareE2eFoliaObservedRegions")
    outputs.upToDateWhen { false }
    doLast {
        installE2eConfig("folia-observed-regions", "plugins/ServerProbe/config.yml")
    }
}

wireE2eDependency("e2eFoliaObservedRegions", prepareFoliaObservedRegionsConfiguration)

// ── FR14 MCP 场景配置注入 ──

val prepareMcpDiagnosticsConfiguration by tasks.registering {
    group = "verification"
    description = "在 Paper 启动前写入 FR14 MCP 场景配置"
    dependsOn("prepareE2eMcpDiagnosticsPaper")
    outputs.upToDateWhen { false }
    doLast {
        installE2eConfig("mcp-diagnostics", "plugins/ServerProbe/config.yml")
    }
}

wireE2eDependency("e2eMcpDiagnosticsPaper", prepareMcpDiagnosticsConfiguration)

/** 直接后端场景在 mc-testkit 铺设完运行目录后才注入专属验收桩，避免污染 Java8 场景。 */
private fun Project.prepareDirectHarness(
    name: String,
    prepareTask: String,
    harnessProjectPath: String,
    harnessJarName: String,
) = tasks.register(name) {
    group = "verification"
    dependsOn(prepareTask, "$harnessProjectPath:jar")
    inputs.file(rootProject.layout.projectDirectory.file("e2e/${harnessProjectPath.removePrefix(":e2e:").replace(':', '/')}/build/libs/$harnessJarName"))
    outputs.upToDateWhen { false }
    doLast {
        val source = rootProject.layout.projectDirectory.file(
            "e2e/${harnessProjectPath.removePrefix(":e2e:").replace(':', '/')}/build/libs/$harnessJarName",
        ).asFile
        check(source.isFile) { "harness jar 未构建：${source.absolutePath}" }
        val target = e2eRunDirectory.get().file("plugins/${source.name}").asFile
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
    }
}

val prepareMcpPaperJava21Harness = prepareDirectHarness(
    "prepareMcpPaperJava21Harness", "prepareE2eMcpDiagnosticsPaperJava21", ":e2e:harness-mcp-bukkit", "mc-testkit-mcp-bukkit-harness-1.0.0-SNAPSHOT.jar",
)
val prepareMcpFoliaHarness = prepareDirectHarness(
    "prepareMcpFoliaHarness", "prepareE2eMcpDiagnosticsFolia", ":e2e:harness-mcp-bukkit", "mc-testkit-mcp-bukkit-harness-1.0.0-SNAPSHOT.jar",
)
val prepareMcpJava8Harness = prepareDirectHarness(
    "prepareMcpJava8Harness", "prepareE2eMcpDiagnosticsJava8", ":e2e:harness-mcp-java8", "mc-testkit-mcp-java8-harness-1.0.0-SNAPSHOT.jar",
)
val prepareMcpSpigotHarness = prepareDirectHarness(
    "prepareMcpSpigotHarness", "prepareE2eMcpDiagnosticsSpigot", ":e2e:harness-mcp-bukkit", "mc-testkit-mcp-bukkit-harness-1.0.0-SNAPSHOT.jar",
)

wireE2eDependency("e2eMcpDiagnosticsPaperJava21", prepareMcpPaperJava21Harness)
wireE2eDependency("e2eMcpDiagnosticsFolia", prepareMcpFoliaHarness)
wireE2eDependency("e2eMcpDiagnosticsJava8", prepareMcpJava8Harness, ":plugin:mcpJava8Agent")
wireE2eDependency("e2eMcpDiagnosticsSpigot", prepareMcpSpigotHarness)
wireE2eDependency("prepareE2eMcpDiagnosticsBungee", ":e2e:harness-mcp-bungee:jar")
wireE2eDependency("prepareE2eMcpDiagnosticsVelocity", ":e2e:harness-mcp-velocity:jar")
wireE2eDependency("prepareE2eMcpDiagnosticsVelocityJava25", ":e2e:harness-mcp-velocity:jar")
