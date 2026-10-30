package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import java.util.concurrent.CompletableFuture

/**
 * MCP 控制面的装配行为测试。
 *
 * <h2>为什么不靠反射</h2>
 * <p>以前这里 `getDeclaredField("platformControl")` 断言字段类型是 `PlatformControl` 而不是具体
 * registry，再断言字段上有 `@Inject`/`@Named`。那些只能证明「字段声明成了这样」——
 * 而 `providers()` 里把 `platformControl` 换成别的实例、或干脆不传下去，这些断言照样绿。</p>
 *
 * <h2>现在测什么</h2>
 * <p>真的把控制面组装起来，塞进录制型控制器，然后走**MCP 工具的调用入口**：
 * <ul>
 *   <li>`server_command` 必须落到注入进来的 [PlatformControl] 上（而不是被替换成别的实例）；</li>
 *   <li>`arthas_execute` 必须落到注入进来的 [ArthasControl] 上；</li>
 *   <li>两个 registry 的注册/注销真的能改变 `execute` 的去向。</li>
 * </ul>
 * “注入的是接口而不是具体 registry”这条，真正的守卫是构建期的 IoC 静态分析
 * （`analyzeTaboolibIocBeans`，`analysisFailOnError=true`）——按接口索引找不到 Bean 就直接拦下构建。
 */
class McpIocContractTest {

    // ────────────────── 控制面装配：注入的对象真的被用上了 ──────────────────

    @Test
    @DisplayName("server_command 落到注入的平台控制器上")
    fun `平台命令落到注入的控制器`() {
        val platform = RecordingPlatformControl()
        val plane = controlPlane(platform = platform)

        val result = nativeProvider(plane).call(SERVER_COMMAND, arguments("command" to "say 你好"))

        assertEquals("say 你好", platform.commands.single(), "命令必须原样交给注入的控制器")
        assertEquals(true, result["success"], "控制器返回成功时必须透传 success，实际=$result")
    }

    @Test
    @DisplayName("platformControl 换一个实现，server_command 就落到新实现上")
    fun `更换平台控制器后路由跟随`() {
        val first = RecordingPlatformControl()
        val second = RecordingPlatformControl()
        val plane = controlPlane(platform = first)
        plane.platformControl = second

        nativeProvider(plane).call(SERVER_COMMAND, arguments("command" to "list"))

        assertEquals(emptyList<String>(), first.commands, "换掉之后不得再走到旧实现上")
        assertEquals(listOf("list"), second.commands, "必须走当前注入的实例")
    }

    @Test
    @DisplayName("arthas_execute 落到注入的 Arthas 控制器上")
    fun `Arthas 命令落到注入的控制器`() {
        val arthas = RecordingArthasControl()
        val plane = controlPlane(arthas = arthas)

        nativeProvider(plane).call(ARTHAS_EXECUTE, arguments("command" to "dashboard"))

        assertEquals(listOf("dashboard"), arthas.submitted, "Arthas 命令必须交给注入的控制器")
    }

    @Test
    @DisplayName("未注入平台控制器时 server_command 结构化失败，不抛异常")
    fun `缺平台控制器时降级`() {
        // 平台模块缺失（如另端未装）时，控制面仍需可用：必须给结构化失败回执。
        val plane = controlPlane(platform = null)

        val result = nativeProvider(plane).call(SERVER_COMMAND, arguments("command" to "say hi"))

        assertEquals(false, result["success"], "无控制器时必须结构化失败，实际=$result")
    }

    // ────────────────── 两个 registry 的真实行为 ──────────────────

    @Test
    @DisplayName("注册后命令转发给控制器，注销后不再转发")
    fun `平台控制注册表转发与撤回`() {
        val registry = PlatformControlRegistry()
        val control = RecordingPlatformControl()

        registry.register(control)
        registry.execute(PlatformCommandRequest("say hi", BoundedCommandOutput(64))).get()
        assertEquals(listOf("say hi"), control.commands, "注册后必须转发给控制器")

        registry.unregister(control)
        val afterUnregister = registry.execute(PlatformCommandRequest("say hi", BoundedCommandOutput(64))).get()
        assertFalse(afterUnregister.success, "注销后不得继续转发，实际=$afterUnregister")
        assertEquals(1, control.commands.size, "注销后的命令不得再传给旧控制器")
    }

    @Test
    @DisplayName("Arthas 注册表在无控制器时仍给出降级快照，注册后转发给它")
    fun `Arthas 注册表降级与转发`() {
        val registry = ArthasControlRegistry()

        val unavailable = registry.submit(ArthasCommandRequest("dashboard", 1_000))
        assertEquals(ArthasTaskState.FAILED, unavailable.state, "未注册时必须给出失败的降级快照，实际=$unavailable")

        val control = RecordingArthasControl()
        registry.register(control)
        registry.submit(ArthasCommandRequest("thread", 1_000))
        assertEquals(listOf("thread"), control.submitted, "注册后必须转发给控制器")
    }

    @Test
    @DisplayName("平台控制注册表以最后一个注册者为准，注销后回到未注册态")
    fun `平台控制注册表只保留一个实现`() {
        val registry = PlatformControlRegistry()
        val first = RecordingPlatformControl()
        val second = RecordingPlatformControl()

        registry.register(first)
        registry.register(second)
        registry.execute(PlatformCommandRequest("list", BoundedCommandOutput(64))).get()

        assertEquals(emptyList<String>(), first.commands, "后注册者生效，旧实现不得再被调用")
        assertEquals(listOf("list"), second.commands)
    }

    // ────────────────── 夹具 ──────────────────

    /**
     * 组装控制面：只装配本测试会用到协作者。
     *
     * [platform] 传 null 表示“平台模块未注册”（用 `UnavailablePlatformControl` 的等价语义：注册表空着）。
     */
    private fun controlPlane(
        platform: PlatformControl? = null,
        arthas: ArthasControl = RecordingArthasControl(),
    ): McpControlPlane = McpControlPlane().apply {
        this.platformControl = platform ?: PlatformControlRegistry()
        this.arthasControl = arthas
        this.mcpToolProviderRegistry = McpToolProviderRegistry()
    }

    /** 取控制面组装出的原生工具集（超时显式传入，避开 `config.yml`）。 */
    private fun nativeProvider(plane: McpControlPlane): McpToolProvider =
        plane.providers(arthasTimeoutMillis = 1_000L).first()

    private fun arguments(vararg pairs: Pair<String, Any?>): JsonObject = MapJsonObject(mapOf(*pairs))

    /** 录制型平台控制器：记下收到的命令，固定返回成功。 */
    private class RecordingPlatformControl : PlatformControl {

        val commands = mutableListOf<String>()

        override fun execute(request: PlatformCommandRequest): CompletableFuture<PlatformCommandResult> {
            commands += request.command
            request.output.append("已执行 ${request.command}")
            return CompletableFuture.completedFuture(PlatformCommandResult.success(request.output.snapshot()))
        }
    }

    /** 录制型 Arthas 控制器：记下提交的命令，返回排队状态。 */
    private class RecordingArthasControl : ArthasControl {

        val submitted = mutableListOf<String>()

        override fun submit(request: ArthasCommandRequest): ArthasTaskSnapshot {
            submitted += request.command
            return ArthasTaskSnapshot("task-${submitted.size}", ArthasTaskState.RUNNING, "已提交")
        }

        override fun status(taskId: String): ArthasTaskSnapshot =
            ArthasTaskSnapshot(taskId, ArthasTaskState.RUNNING, "运行中")

        override fun output(taskId: String, offset: Int): ArthasTaskOutput =
            ArthasTaskOutput(taskId, "", offset, false)

        override fun cancel(taskId: String): ArthasTaskSnapshot =
            ArthasTaskSnapshot(taskId, ArthasTaskState.CANCELLED, "已取消")
    }

    /** 最小 JsonObject 替身（工具参数只有平铺字符串）。 */
    private class MapJsonObject(private val values: Map<String, Any?>) : JsonObject {
        override fun getRaw(key: String): Any? = values[key]
        override fun getString(key: String, default: String): String = values[key]?.toString() ?: default
        override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default
        override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default
        override fun getDouble(key: String, default: Double): Double = values[key] as? Double ?: default
        override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
        override fun getStringList(key: String): List<String> = values[key] as? List<String> ?: emptyList()
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun getObject(key: String): JsonObject? = (values[key] as? Map<String, Any?>)?.let(::MapJsonObject)
    }

    private companion object {
        /** MCP 协议里的工具名（稳定契约，跨模块共认）。 */
        const val SERVER_COMMAND = "server_command"
        const val ARTHAS_EXECUTE = "arthas_execute"
    }
}
