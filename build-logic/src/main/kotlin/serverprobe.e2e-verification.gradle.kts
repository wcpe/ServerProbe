/**
 * ServerProbe 的 mc-testkit E2E 编排约定（仅 root 应用）。
 *
 * 承载：
 * - 应用 top.wcpe.mc-testkit 并声明全部 mcTestkit{} 拓扑（backends/proxies/scenarios/serve）
 * - e2e 场景配置注入：模板文件(e2e/templates/configs/<scenario>/) + 占位符替换，脚本内禁止内嵌 YAML
 * - prepare 系列任务与 e2e 任务接线
 * - 被测发行 jar 文件名与 mc-testkit 运行目录的统一定义（本插件唯一真源）
 *
 * 跨项目任务引用一律用路径字符串，避免 root 配置期跨项目 tasks.named 的时序坑。
 */
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
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
// 运行目录不再集中定义：mc-testkit 0.10.0 起每个后端一个独立目录（run-<backend>），
// 需要定位时一律走 `mcTestkit.backendRunDirectory(backend)`（见 installE2eConfig / prepareDirectHarness）。

// ── 托管代理场景（Bungee / Velocity）的一次性参数 ──
// 代理运行目录由 mc-testkit 在**场景任务内部**用代理模板铺开，而场景钩子跑在那之前：
// 钩子写进 `plugins/<数据目录>` 的文件会被模板覆盖掉（实测身份文件会消失，探针因此退回独立模式）。
// 所以身份文件只能作为**模板的一部分**生成。端口与凭据只在生成模板的那一刻取一次
// （见 `prepareManagedProxyTemplates`）——配置期取会得到一个 TOCTOU 窗口等于整个构建的空闲端口，
// 且对 configuration cache 不友好。
val managedBungeeTemplateDir = "build/mc-testkit/templates/managed-bungee"
val managedVelocityTemplateDir = "build/mc-testkit/templates/managed-velocity"

/** 取一个当前空闲的回环端口（在任务动作里调用，端口占用窗口最小）。 */
private fun freeLoopbackPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket -> socket.localPort }

/** 一次性回环凭据（测试夹具用，非生产密钥）。 */
private fun e2eToken(): String = "e2e-" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)

/**
 * 身份文件 JSON：连接目标写在文件里，文件即托管开关（字段与探针侧 `ControlPlaneModeService` 一致）。
 *
 * @param port 模拟控制面监听端口。
 * @param token 一次性凭据。
 * @return 身份文件内容。
 */
private fun managedIdentityJson(port: Int, token: String): String {
    val createdAt = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
    return """{"schemaVersion":1,"provider":"e2e","endpoint":"ws://127.0.0.1:$port/ws/plugin-bridge",""" +
        """"instanceId":"e2e-fixture","credential":"$token","capabilities":[],""" +
        """"createdAt":"$createdAt"}"""
}


// ── mc-testkit 拓扑声明 ──
// harness 产物由收编后的子项目任务构建（原 9 个 Exec 调子 gradlew 的 hack 已删除）：
//   :e2e:harness:jar、:e2e:harness-network-*:jar、:e2e:harness-velocity-matrix:jar、:e2e:harness-mcp-*:jar
// 产物路径不变（e2e/<dir>/build/libs/mc-testkit-*-harness-1.0.0-SNAPSHOT.jar），mcTestkit 路径引用不受影响。

val mcTestkit = extensions.getByType(top.wcpe.mc.testkit.dsl.McTestkitExtension::class.java)
mcTestkit.apply {
    backend("paper") {
        platform = paper
        version = "1.20.1"
        // 25575 与外部测试服端口冲突，改用 25620。
        port = 25620
    }
    backend("paper-incision") {
        platform = paper
        version = "1.20.1"
        // 与 paper(25620) / paper-mcp(25577) 错开，避免并行跑场景时抢端口。
        port = 25621
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
        // 1.16.5 的 patcher 拒绝 Java 17+（实测 Unsupported Java detected），锁定 Java 8 起服
        javaVersion = 8
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
    // MC 26.x 纪元版本（新版本号方案，要求 Java 25+）：验证 mc-testkit 对新版本号的下载与起服
    backend("matrix-paper-26-2") {
        platform = paper
        version = "26.2"
        javaVersion = 25
        port = 25622
    }
    backend("matrix-spigot-1-8-8") {
        platform = spigot
        version = "1.8.8"
        port = 25618
    }
    backend("matrix-spigot-1-16-5") {
        platform = spigot
        version = "1.16.5"
        // 同 paper-1-16-5：锁定 Java 8
        javaVersion = 8
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
    // 注意模板里的插件数据目录大小写：Velocity 侧插件的数据目录由 velocity-plugin.json 的 id 派生，
    // 实为 **plugins/serverprobe（小写）**；Bukkit / Bungee 则由 plugin.yml / bungee.yml 的 name 派生，
    // 是 plugins/ServerProbe（大写）。写错时 Linux（大小写敏感）上插件读不到模板配置、会自建默认配置
    // （如 mcp.enabled=false → MCP 端点不起 → 该场景必然超时），而 Windows 本地因文件系统不敏感
    // 始终看不出问题 —— CI 实测仅 velocity 场景失败、bungee 通过即为该差异所致。
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
    // 托管模式下的代理：模板里含托管 `config.yml` 与一次性身份文件（由 prepareManagedProxyTemplates 生成）。
    // 不能复用 bungee-mcp / velocity-mcp：模板按**代理声明**给出，而这两个模板是独立模式（bridge 段）配置。
    // 夹具插件（harness-managed-*）随代理启动：它在代理进程内扮模拟控制面，端口与凭据直接读身份文件。
    proxy("bungee-managed") {
        platform = bungeecord
        // 与 bungee-mcp(25605) / velocity-mcp(25606) 错开。
        port = 25609
        routesTo("paper-mcp-proxy")
        templateDirectory(managedBungeeTemplateDir)
        plugin("plugin/build/libs/$pluginJarName")
        plugin("e2e/harness-managed-bungee/build/libs/mc-testkit-managed-bungee-harness-1.0.0-SNAPSHOT.jar")
    }
    proxy("velocity-managed") {
        platform = velocity
        version = "3.1.1"
        // 25623：避开后端/矩阵已占用的 25576-25622 与 25630。
        port = 25623
        routesTo("paper-mcp-proxy")
        templateDirectory(managedVelocityTemplateDir)
        plugin("plugin/build/libs/$pluginJarName")
        plugin("e2e/harness-managed-velocity/build/libs/mc-testkit-managed-velocity-harness-1.0.0-SNAPSHOT.jar")
    }
    scenario("read-api") { backend = "paper" }
    // Incision 织入的场景：必须开 `incision.enabled`，且要等首个启动画像落盘。
    // 单独一个 backend 是为了不把“开织入”这个变量泄到其他场景上。
    scenario("incision-startup") { backend = "paper-incision" }
    scenario("storage-spi") { backend = "paper" }
    scenario("bridge-fixture") { backend = "paper" }
    scenario("chunk-fixture") { backend = "paper" }
    scenario("topic-fixture") { backend = "paper" }
    scenario("health-fixture") { backend = "paper" }
    scenario("reload-fixture") { backend = "paper" }
    // 托管模式：连接目标写在身份文件里（身份文件即开关），故没有 bridge 段可自动迁移。
    scenario("managed-config") { backend = "paper" }
    // 版本矩阵：同一套托管断言在旧版与新版运行时上各跑一遍（1.8 锁 Java 8）。
    scenario("managed-config-paper188") { backend = "matrix-paper-1-8-8" }
    scenario("managed-config-paper1211") { backend = "matrix-paper-1-21-1" }
    // 控制面拒绝凭据（401/403）：夹具在握手头两次分别回 401、403，验「被拒不降级」与「恢复后能连上」。
    scenario("managed-auth-reject") { backend = "paper" }
    // 代理端托管：身份文件随代理模板生成（钩子早于模板铺设，写不进去）；
    // 夹具插件在代理进程内起模拟控制面并把断言写成结果文件。
    scenario("managed-config-bungee") {
        backend = "paper-mcp-proxy"
        via = "bungee-managed"
    }
    scenario("managed-config-velocity") {
        backend = "paper-mcp-proxy"
        via = "velocity-managed"
    }
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
    // 单后端 + bot 的场景必须用 e2e<Key>WithBot 跑（或先跑 launch<Key>Bot 再跑 e2e<Key>）：
    // mc-testkit 的直连任务 e2e<Key> 只 dependsOn(prepare)，对 launch<Key>Bot 仅 mustRunAfter 排序——
    // bot 不在它的任务图里，直接跑 e2e<Key> 不会起机器人，场景会以「未等到 bot 入服 / 未查询到真实
    // 协议记录」超时失败（现象是 mc-testkit 日志里根本没有 bot 启动行、服务端也没有玩家入服记录）。
    // 经代理（e2e<Key>Via<Proxy>）与集群（e2e<Key>Cluster）任务不受此限：它们自身就会起 bot。
    scenario("folia-observed-regions") {
        backend = "folia"
        bot {
            username = "FoliaProbe"
            action = "folia-observed-regions"
            count = 2
        }
    }
    // 多版本矩阵场景：与 read-api 同判据（真实快照 + 启动画像），由 harness 的 matrix- 前缀识别。
    scenario("matrix-paper-1-8-8") { backend = "matrix-paper-1-8-8" }
    scenario("matrix-paper-1-12-2") { backend = "matrix-paper-1-12-2" }
    scenario("matrix-paper-1-16-5") { backend = "matrix-paper-1-16-5" }
    scenario("matrix-paper-1-17-1") { backend = "matrix-paper-1-17-1" }
    scenario("matrix-paper-1-18-2") { backend = "matrix-paper-1-18-2" }
    scenario("matrix-paper-1-19-4") { backend = "matrix-paper-1-19-4" }
    scenario("matrix-paper-1-20-4") { backend = "matrix-paper-1-20-4" }
    scenario("matrix-paper-1-21-1") { backend = "matrix-paper-1-21-1" }
    scenario("matrix-paper-26-2") { backend = "matrix-paper-26-2" }
    scenario("matrix-spigot-1-8-8") { backend = "matrix-spigot-1-8-8" }
    scenario("matrix-spigot-1-16-5") { backend = "matrix-spigot-1-16-5" }
    scenario("network-forensics-bukkit") {
        // Bukkit 平台的既有权威结果实际运行于 Spigot 后端。
        backend = "spigot-network"
        bot {
            username = "NetworkProbe"
            action = "network-forensics"
        }
    }
    scenario("network-forensics-paper") {
        backend = "paper-network"
        bot {
            username = "NetworkProbe"
            action = "network-forensics"
        }
    }
    scenario("network-forensics-folia") {
        backend = "folia-network"
        bot {
            username = "NetworkProbe"
            action = "network-forensics"
        }
    }
    scenario("network-forensics-bungee") {
        backend = "spigot-network"
        via = "bungee-network"
        bot {
            username = "NetworkProbe"
            action = "network-forensics"
        }
    }
    scenario("network-forensics-velocity") {
        backend = "paper-network-velocity"
        via = "velocity-network"
        bot {
            username = "NetworkProbe"
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

/** 身份文件名（与探针侧 `ControlPlaneModeService` 的常量保持一致）。 */
private val identityFileName = "agent-identity.json"


/**
 * 读取 `e2e/templates/configs/<scenario>/` 下的配置模板，替换占位符后写入**目标后端运行目录**。
 *
 * 目录一律由 mc-testkit 的公开契约给出（`mcTestkit.backendRunDirectory`），本脚本不自行拼路径：
 * 布局属 mc-testkit 内部实现，自行拼写在布局调整后会静默失配（曾把配置写进已废弃的共享 run 目录）。
 */
private fun Project.installE2eConfig(
    targetRunDirectory: File,
    scenario: String,
    targetRelativePath: String,
    replacements: Map<String, String> = emptyMap(),
) {
    val template = rootProject.layout.projectDirectory.file("e2e/templates/configs/$scenario/$targetRelativePath").asFile
    check(template.isFile) { "e2e 配置模板缺失：${template.relativeTo(rootProject.layout.projectDirectory.asFile)}" }
    val target = File(targetRunDirectory, targetRelativePath)
    target.parentFile.mkdirs()
    var content = template.readText()
    replacements.forEach { (key, value) -> content = content.replace(key, value) }
    target.writeText(content)
    // FR-30 托管模式：带 bridge 段的夹具在首次启动时会被自动迁移成身份文件（bridge 段随之移除）。
    // 夹具每次 prepare 都重新生成端口与 token，若上一次运行留下的 agent-identity.json 不被清掉，
    // 探针会按**旧身份**连**旧端口**，表现为桥连不上——故"重新注入配置"即等同"重新下发探针"，
    // 一并清除同目录下的身份文件。
    val identityFile = File(target.parentFile, identityFileName)
    if (identityFile.isFile && !identityFile.delete()) {
        logger.warn("无法清理残留身份文件：${identityFile.absolutePath}")
    }
}

/**
 * 为 e2e 任务统一挂 prepare 依赖（支持任务路径字符串与 TaskProvider）。
 *
 * 不能只靠 `tasks.matching {}"`：名字拼错时它什么也不匹配、于是依赖静默不挂（“看着接线了，实际没挂”）。
 * 因此配置结束后补一次存在性断言，把“拼错/改名”从运行时静默变成配置期失败。
 */
private fun Project.wireE2eDependency(e2eTaskName: String, vararg prepare: Any) {
    tasks.matching { it.name == e2eTaskName }.configureEach { dependsOn(*prepare) }
    afterEvaluate {
        check(tasks.names.contains(e2eTaskName)) {
            "e2e 接线引用的任务不存在：$e2eTaskName（任务名拼错会让 prepare 依赖静默不挂）"
        }
    }
}

// ── harness 产物接线：prepareE2e* 任务依赖收编后的子项目 jar 任务 ──
// （原 9 个 Exec 调子 gradlew 的 hack 已删除，产物由子项目任务直接构建）
//
// network-bukkit harness 也要挂上：它在 dependencies { } 里被**全局注入**到每个后端
// （各场景按 MC_TESTKIT_E2E_SCENARIO 自行判断是否生效），故所有场景的 prepare 都要求它存在；
// 漏挂的后果是全新检出（CI / 新克隆）时 prepare 报「缺少必需的依赖注入」而本地因 jar 早已存在于
// 磁盘上、问题被掩盖。
tasks.matching { it.name.startsWith("prepareE2e") }.configureEach {
    dependsOn(":e2e:harness:jar", ":e2e:harness-network-bukkit:jar")
}

// ── 身份文件残留清理（与场景无关）──
// 身份文件即托管开关：托管场景会把它写进后端运行目录，而非托管场景（read-api / storage-spi / matrix-*）
// 走的是 harness 自写配置的路径、不经 installE2eConfig，于是前一个场景留下的身份文件会让它们以
// **托管模式**启动并连向已关闭的夹具端口（表现为难查的模式漂移）。故在所有 prepareE2e* 之前统一清一次；
// 托管场景自己的 prepare（prepareManagedConfig*）在那之后重新写入，顺序由 dependsOn 链保证。
val cleanE2eIdentityResidue by tasks.registering {
    group = "verification"
    description = "清理各后端运行目录里残留的托管身份文件（避免非托管场景以托管模式启动）"
    outputs.upToDateWhen { false }
    doLast {
        val workRoot = rootProject.layout.buildDirectory.dir("mc-testkit").get().asFile
        val residues = workRoot.listFiles { file -> file.isDirectory && file.name.startsWith("run-") }
            ?.flatMap { runDir -> (runDir.resolve("plugins").listFiles() ?: emptyArray()).toList() }
            ?.mapNotNull { dataDir -> dataDir.resolve(identityFileName).takeIf { it.isFile } }
            .orEmpty()
        residues.forEach { file ->
            if (!file.delete()) {
                logger.warn("无法清理残留身份文件：${file.absolutePath}")
            }
        }
        if (residues.isNotEmpty()) {
            logger.lifecycle("已清理残留托管身份文件 ${residues.size} 个（防止非托管场景以托管模式启动）")
        }
    }
}
tasks.matching { it.name.startsWith("prepareE2e") }.configureEach {
    dependsOn(cleanE2eIdentityResidue)
}

tasks.matching { it.name.startsWith("prepareE2eNetworkForensics") }.configureEach {
    dependsOn(":e2e:harness-network-bukkit:jar", ":e2e:harness-network-bungee:jar", ":e2e:harness-network-velocity:jar")
}
tasks.matching { it.name.startsWith("e2eVelocityMatrixV") }.configureEach {
    dependsOn(":e2e:harness-velocity-matrix:jar")
}

// ── 桥 fixture ──

val prepareBridgeFixture by tasks.registering {
    group = "verification"
    description = "为 回环 Worker fixture 生成一次性本机桥配置"
    dependsOn("prepareE2eBridgeFixture")
    outputs.upToDateWhen { false }
    // 配置期只取 Provider，任务动作执行期才解析（配置缓存友好）
    val runDirectory = mcTestkit.backendRunDirectory("paper")
    doLast {
        val tokenBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val port = freeLoopbackPort()
        installE2eConfig(
            runDirectory.get().asFile,
            "bridge-fixture",
            "plugins/ServerProbe/config.yml",
            mapOf("__PORT__" to port.toString(), "__TOKEN__" to token),
        )
    }
}

wireE2eDependency("e2eBridgeFixture", prepareBridgeFixture)

// ── 帧分片 fixture ──
// 与同构：先由 prepareE2eChunkFixture 铺设运行目录，再写入一次性回环桥配置（含分片参数）。

val prepareChunkFixture by tasks.registering {
    group = "verification"
    description = "为 帧分片 fixture 生成一次性本机桥配置"
    dependsOn("prepareE2eChunkFixture")
    outputs.upToDateWhen { false }
    doLast {
        val tokenBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val port = freeLoopbackPort()
        installE2eConfig(
            mcTestkit.backendRunDirectory("paper").get().asFile,
            "chunk-fixture",
            "plugins/ServerProbe/config.yml",
            mapOf("__PORT__" to port.toString(), "__TOKEN__" to token),
        )
    }
}

wireE2eDependency("e2eChunkFixture", prepareChunkFixture)

// ── 事件主题订阅 fixture ──
// 与/同构：先由 prepareE2eTopicFixture 铺设运行目录，再写入一次性回环桥配置（含订阅上限）。

val prepareTopicFixture by tasks.registering {
    group = "verification"
    description = "为 事件主题订阅 fixture 生成一次性本机桥配置"
    dependsOn("prepareE2eTopicFixture")
    outputs.upToDateWhen { false }
    doLast {
        val tokenBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val port = freeLoopbackPort()
        installE2eConfig(
            mcTestkit.backendRunDirectory("paper").get().asFile,
            "topic-fixture",
            "plugins/ServerProbe/config.yml",
            mapOf("__PORT__" to port.toString(), "__TOKEN__" to token),
        )
    }
}

wireE2eDependency("e2eTopicFixture", prepareTopicFixture)

// ── Provider 健康状态 fixture ──
// 与//同构：先由 prepareE2eHealthFixture 铺设运行目录，再写入一次性回环桥配置
// （含 health 刷新参数——超时预算必须显式写死，卡死域的判据就挂在它上面）。

val prepareHealthFixture by tasks.registering {
    group = "verification"
    description = "为 Provider 健康状态 fixture 生成一次性本机桥配置"
    dependsOn("prepareE2eHealthFixture")
    outputs.upToDateWhen { false }
    // 配置期只取 Provider，任务动作执行期才解析（配置缓存友好）
    val runDirectory = mcTestkit.backendRunDirectory("paper")
    doLast {
        val tokenBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val port = freeLoopbackPort()
        installE2eConfig(
            runDirectory.get().asFile,
            "health-fixture",
            "plugins/ServerProbe/config.yml",
            mapOf("__PORT__" to port.toString(), "__TOKEN__" to token),
        )
    }
}

wireE2eDependency("e2eHealthFixture", prepareHealthFixture)

// ── 配置重载通知 fixture ──
// 与///同构：先由 prepareE2eReloadFixture 铺设运行目录，再写入一次性回环桥配置
// （含 reload 预算与两池参数——"预算内编排"的判据就挂在 timeout-ms 上）。

val prepareReloadFixture by tasks.registering {
    group = "verification"
    description = "为 配置重载通知 fixture 生成一次性本机桥配置"
    dependsOn("prepareE2eReloadFixture")
    outputs.upToDateWhen { false }
    doLast {
        val tokenBytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val port = freeLoopbackPort()
        installE2eConfig(
            mcTestkit.backendRunDirectory("paper").get().asFile,
            "reload-fixture",
            "plugins/ServerProbe/config.yml",
            mapOf("__PORT__" to port.toString(), "__TOKEN__" to token),
        )
    }
}

wireE2eDependency("e2eReloadFixture", prepareReloadFixture)

// ── 托管模式 fixture ──
// 与桥场景的关键差别：连接目标写在**身份文件**里（身份文件即托管开关），config.yml 不含 bridge 段。
// 身份文件必须在服启动前就位，故与配置一起在 prepare 阶段落盘；installE2eConfig 会清理残留身份文件，
// 因此必须写在它之后。

val prepareManagedConfig by tasks.registering {
    group = "verification"
    description = "为托管模式 fixture 写入身份文件与不带 bridge 段的配置"
    dependsOn("prepareE2eManagedConfig")
    outputs.upToDateWhen { false }
    val runDirectory = mcTestkit.backendRunDirectory("paper")
    doLast { installManagedScenario(runDirectory.get().asFile) }
}

wireE2eDependency("e2eManagedConfig", prepareManagedConfig)

// 版本矩阵：同模板不同后端（1.8 锁 Java 8，1.21 用默认 JDK）。
val prepareManagedConfigPaper188 by tasks.registering {
    group = "verification"
    description = "为托管模式 fixture（Paper 1.8.8）写入身份文件与配置"
    dependsOn("prepareE2eManagedConfigPaper188")
    outputs.upToDateWhen { false }
    val runDirectory = mcTestkit.backendRunDirectory("matrix-paper-1-8-8")
    doLast { installManagedScenario(runDirectory.get().asFile) }
}

wireE2eDependency("e2eManagedConfigPaper188", prepareManagedConfigPaper188)

val prepareManagedConfigPaper1211 by tasks.registering {
    group = "verification"
    description = "为托管模式 fixture（Paper 1.21.1）写入身份文件与配置"
    dependsOn("prepareE2eManagedConfigPaper1211")
    outputs.upToDateWhen { false }
    val runDirectory = mcTestkit.backendRunDirectory("matrix-paper-1-21-1")
    doLast { installManagedScenario(runDirectory.get().asFile) }
}

wireE2eDependency("e2eManagedConfigPaper1211", prepareManagedConfigPaper1211)

// 拒绝凭据场景：配置与身份文件同托管场景（同一个模板，三个监听面故意开着），
// 这样「是否降级到文件配置」可以直接用端口探活判定。
val prepareManagedAuthReject by tasks.registering {
    group = "verification"
    description = "为托管拒绝凭据场景写入身份文件与配置"
    dependsOn("prepareE2eManagedAuthReject")
    outputs.upToDateWhen { false }
    val runDirectory = mcTestkit.backendRunDirectory("paper")
    doLast { installManagedScenario(runDirectory.get().asFile) }
}

wireE2eDependency("e2eManagedAuthReject", prepareManagedAuthReject)

// ── 托管代理场景模板生成（Bungee / Velocity）──
// 代理运行目录是 mc-testkit 在**场景任务内部**用代理模板铺开的，场景钩子跑在那之前，
// 所以身份文件与托管 config.yml 必须作为**模板的一部分**存在（见文件头的说明）。
// 生成任务必须早于场景任务的 staging，故挂到 via 场景任务上。

val prepareManagedProxyTemplates by tasks.registering {
    group = "verification"
    description = "生成托管代理场景模板（托管 config.yml + 一次性身份文件）"
    outputs.upToDateWhen { false }
    doLast {
        // 端口/凭据在动作里取：配置期取会得到一个“空闲窗口 = 整个构建”的 TOCTOU 空隙，
        // 且 configuration cache 会把值冻结进缓存条目。
        writeManagedProxyTemplate(managedBungeeTemplateDir, "ServerProbe", freeLoopbackPort(), e2eToken())
        writeManagedProxyTemplate(managedVelocityTemplateDir, "serverprobe", freeLoopbackPort(), e2eToken())
    }
}

// 任务名由 mc-testkit 按 `e2e<场景>Via<代理>` 生成（代理名 bungee-managed → BungeeManaged）。
wireE2eDependency("e2eManagedConfigBungeeViaBungeeManaged", prepareManagedProxyTemplates)
wireE2eDependency("e2eManagedConfigVelocityViaVelocityManaged", prepareManagedProxyTemplates)

/**
 * 生成一个托管代理模板目录：托管 `config.yml`（无 `bridge` 段）+ 身份文件。
 *
 * @param templateDir 模板目录（相对 rootProject，与 ProxySpec.templateDirectory 一致）。
 * @param pluginDataDir 插件数据目录名（Bungee 大写 `ServerProbe`，Velocity 小写 `serverprobe`）。
 * @param port 模拟控制面端口（夹具插件从身份文件读取后监听）。
 * @param token 一次性凭据（夹具插件从身份文件读取后完成握手校验）。
 */
private fun Project.writeManagedProxyTemplate(templateDir: String, pluginDataDir: String, port: Int, token: String) {
    val dataDir = File(rootProject.layout.projectDirectory.asFile, "$templateDir/plugins/$pluginDataDir")
    dataDir.mkdirs()
    val template = rootProject.layout.projectDirectory
        .file("e2e/templates/configs/managed-config/plugins/ServerProbe/config.yml").asFile
    check(template.isFile) { "托管场景配置模板缺失：${template.absolutePath}" }
    template.copyTo(File(dataDir, "config.yml"), overwrite = true)
    File(dataDir, identityFileName).writeText(managedIdentityJson(port, token))
}

/**
 * 写入托管模式场景的两份输入：不带 `bridge` 段的 `config.yml` + 身份文件（端口/凭据一次性随机）。
 *
 * 身份文件必须在配置注入**之后**写：`installE2eConfig` 会清理同目录下的残留身份文件（避免上次运行残留的旧身份
 * 把探针指向旧端口）。
 */
private fun Project.installManagedScenario(runDir: File) {
    // 端口/凭据/身份 JSON 全部复用同一组 helper：此前这里内联了一份相同的 JSON 与另一种 token 风格，
    // 改一处忘一处就会出现“模板与案例读到的凭据不同”的谜之失败。
    val token = e2eToken()
    val port = freeLoopbackPort()
    installE2eConfig(runDir, "managed-config", "plugins/ServerProbe/config.yml")
    // 身份文件名与探针侧 ControlPlaneModeService 的常量一致；实例标识与 fixture 的 INSTANCE_ID 一致。
    val identity = File(runDir, "plugins/ServerProbe/$identityFileName")
    identity.parentFile.mkdirs()
    identity.writeText(managedIdentityJson(port, token))
}

// ── Incision 织入场景配置注入 ──
// 必须在服启动前写入：`incision.enabled` 由 TabooLib 在 LOAD 阶段读入并缓存，
// 由 harness 在 onLoad 里写太晚（已读到默认的 false）——表现为“配置文件看着对、就是不生效”。
val prepareIncisionStartupConfiguration by tasks.registering {
    group = "verification"
    description = "为 Incision 织入场景写入已开启的开关配置"
    dependsOn("prepareE2eIncisionStartup")
    outputs.upToDateWhen { false }
    doLast {
        installE2eConfig(mcTestkit.backendRunDirectory("paper-incision").get().asFile, "incision-startup", "plugins/ServerProbe/config.yml")
    }
}

wireE2eDependency("e2eIncisionStartup", prepareIncisionStartupConfiguration)

// ── Folia 场景配置注入 ──

val prepareFoliaObservedRegionsConfiguration by tasks.registering {
    group = "verification"
    description = "为 Folia 场景写入快速采样与 region 过期配置"
    dependsOn("prepareE2eFoliaObservedRegions")
    outputs.upToDateWhen { false }
    val runDirectory = mcTestkit.backendRunDirectory("folia")
    doLast {
        installE2eConfig(runDirectory.get().asFile, "folia-observed-regions", "plugins/ServerProbe/config.yml")
    }
}

wireE2eDependency("e2eFoliaObservedRegions", prepareFoliaObservedRegionsConfiguration)

// ── MCP 场景配置注入 ──

val prepareMcpDiagnosticsConfiguration by tasks.registering {
    group = "verification"
    description = "在 Paper 启动前写入 MCP 场景配置"
    dependsOn("prepareE2eMcpDiagnosticsPaper")
    outputs.upToDateWhen { false }
    val runDirectory = mcTestkit.backendRunDirectory("paper-mcp")
    doLast {
        installE2eConfig(runDirectory.get().asFile, "mcp-diagnostics", "plugins/ServerProbe/config.yml")
    }
}

wireE2eDependency("e2eMcpDiagnosticsPaper", prepareMcpDiagnosticsConfiguration)

/**
 * 直接后端场景在 mc-testkit 铺设完运行目录后才注入专属验收桩，避免污染 Java8 场景。
 *
 * 注入落点取自 mc-testkit 的公开契约（`mcTestkit.backendRunDirectory`），不自行拼路径。
 */
private fun Project.prepareDirectHarness(
    name: String,
    prepareTask: String,
    backendName: String,
    harnessProjectPath: String,
    harnessJarName: String,
) = tasks.register(name) {
    group = "verification"
    dependsOn(prepareTask, "$harnessProjectPath:jar")
    inputs.file(rootProject.layout.projectDirectory.file("e2e/${harnessProjectPath.removePrefix(":e2e:").replace(':', '/')}/build/libs/$harnessJarName"))
    outputs.upToDateWhen { false }
    val runDirectory = mcTestkit.backendRunDirectory(backendName)
    doLast {
        val source = rootProject.layout.projectDirectory.file(
            "e2e/${harnessProjectPath.removePrefix(":e2e:").replace(':', '/')}/build/libs/$harnessJarName",
        ).asFile
        check(source.isFile) { "harness jar 未构建：${source.absolutePath}" }
        val target = File(runDirectory.get().asFile, "plugins/${source.name}")
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
    }
}

val prepareMcpPaperJava21Harness = prepareDirectHarness(
    "prepareMcpPaperJava21Harness", "prepareE2eMcpDiagnosticsPaperJava21", "paper-mcp-java21",
    ":e2e:harness-mcp-bukkit", "mc-testkit-mcp-bukkit-harness-1.0.0-SNAPSHOT.jar",
)
val prepareMcpFoliaHarness = prepareDirectHarness(
    "prepareMcpFoliaHarness", "prepareE2eMcpDiagnosticsFolia", "folia-mcp",
    ":e2e:harness-mcp-bukkit", "mc-testkit-mcp-bukkit-harness-1.0.0-SNAPSHOT.jar",
)
val prepareMcpJava8Harness = prepareDirectHarness(
    "prepareMcpJava8Harness", "prepareE2eMcpDiagnosticsJava8", "paper-mcp-java8",
    ":e2e:harness-mcp-java8", "mc-testkit-mcp-java8-harness-1.0.0-SNAPSHOT.jar",
)
val prepareMcpSpigotHarness = prepareDirectHarness(
    "prepareMcpSpigotHarness", "prepareE2eMcpDiagnosticsSpigot", "spigot-mcp",
    ":e2e:harness-mcp-bukkit", "mc-testkit-mcp-bukkit-harness-1.0.0-SNAPSHOT.jar",
)

wireE2eDependency("e2eMcpDiagnosticsPaperJava21", prepareMcpPaperJava21Harness)
wireE2eDependency("e2eMcpDiagnosticsFolia", prepareMcpFoliaHarness)
wireE2eDependency("e2eMcpDiagnosticsJava8", prepareMcpJava8Harness, ":plugin:mcpJava8Agent")
wireE2eDependency("e2eMcpDiagnosticsSpigot", prepareMcpSpigotHarness)
wireE2eDependency("prepareE2eMcpDiagnosticsBungee", ":e2e:harness-mcp-bungee:jar")
wireE2eDependency("prepareE2eMcpDiagnosticsVelocity", ":e2e:harness-mcp-velocity:jar")
wireE2eDependency("prepareE2eMcpDiagnosticsVelocityJava25", ":e2e:harness-mcp-velocity:jar")
// 代理端托管场景的夹具 jar：在代理进程内起模拟控制面，故必须由 prepare 任务先构出来。
wireE2eDependency("prepareE2eManagedConfigBungee", ":e2e:harness-managed-bungee:jar")
wireE2eDependency("prepareE2eManagedConfigVelocity", ":e2e:harness-managed-velocity:jar")
