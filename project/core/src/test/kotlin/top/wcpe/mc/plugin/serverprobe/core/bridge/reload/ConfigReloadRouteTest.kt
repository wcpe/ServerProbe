package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadErrorCode
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadEvent
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadListener
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommand
import top.wcpe.mc.plugin.serverprobe.core.bridge.ExecutorKind
import top.wcpe.mc.plugin.serverprobe.core.config.ReloadSettings
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ConfigReloadRoute] 单元测试（配置重载规格 §3.1 路由约定 + §3.7 回执形态）。
 *
 * payload 解析与回执编码均注入纯文本实现：core 的单测类路径没有 nightconfig，
 * 而这里要验的是**路由语义**（序位 / 池 / claims / payload 容错 / 传输层成败映射），
 * 不是 JSON 库。
 */
class ConfigReloadRouteTest {

    private val fixtures = ArrayList<Fixture>()

    @AfterEach
    fun tearDown() {
        fixtures.forEach { runCatching { it.close() } }
        fixtures.clear()
    }

    // ------------------------------------------------------------------ 序位 / 池 / claims

    /** 序位与执行池严格按 order 分配表：`config_reload` = 100 / RELOAD。 */
    @Test
    fun `序位与执行池符合分配表`() {
        val (route, _) = routeWithCapture()

        assertEquals(100, route.order, "config_reload 路由序位必须是 100")
        assertEquals(ExecutorKind.RELOAD, route.executor, "重载必须在 reload 协调池执行，不得占用命令池")
        assertEquals("config_reload", ConfigReloadRoute.ACTION)
        assertEquals("core", ConfigReloadRoute.DOMAIN_CORE)
        assertNotEquals(110, route.order, "不得与 business_status 同序位")
        assertNotEquals(120, route.order, "不得与 subscribe 同序位")
    }

    /** `claims` 只认 `domain=core` + `action=config_reload`。 */
    @Test
    fun `claims只认领core的配置重载`() {
        val (route, _) = routeWithCapture()

        assertTrue(route.claims(command()), "core.config_reload 应命中")
        assertFalse(route.claims(command(domain = "economy")), "业务域不得命中")
        assertFalse(route.claims(command(domain = "")), "空域不得命中（那是治理兜底）")
        assertFalse(route.claims(command(action = "bridge_status")), "其它 core 动作不得命中")
        assertFalse(route.claims(command(action = "business_status")), "其它 core 动作不得命中")
    }

    /** `claims` 是纯同步判定：不调编排器、不产生任何副作用。 */
    @Test
    fun `claims不触发任何执行`() {
        val fixture = newFixture()
        val listener = CountingListener("plugins/Foo/config.yml")
        fixture.registry.register(listener)
        val (route, _) = routeWithCapture(fixture)

        repeat(10) { route.claims(command()) }

        assertEquals(0, listener.calls.get(), "claims 绝不得执行重载")
    }

    // ------------------------------------------------------------------ 成功路径

    /** 成功：`ok(回执 JSON)`，且回执结构为 §3.7 的七个键。 */
    @Test
    fun `成功时回执结构与规格一致`() {
        val fixture = newFixture()
        fixture.registry.register(CountingListener("plugins/Foo/config.yml"))
        val (route, capture) = routeWithCapture(fixture)

        val result = route.handle(command(payload = """{"paths":["plugins/Foo/config.yml"]}"""))

        assertTrue(result.success, "全部成功应 success=true")
        val payload = capture.require()
        assertEquals(
            setOf("success", "reloaded", "bestEffort", "failed", "applied", "costMs", "error"),
            payload.keys,
            "回执顶层字段集必须与规格 §3.7 一致",
        )
        assertEquals(true, payload["success"])
        assertEquals(listOf("plugins/Foo/config.yml"), payload["reloaded"])
        assertEquals(emptyList<String>(), payload["bestEffort"])
        assertEquals(emptyList<Any?>(), payload["failed"])
        assertEquals("", payload["error"])
    }

    /**
     * 失败时传输层 `error` 是**可读摘要**，逐条带上路径 / 错误码 / 原因。
     *
     * 同时 `output` 仍是完整回执 JSON：失败与结构并存，面板既能直接展示原因、
     * 也能拿到 `reloaded` / `failed` / `applied` 明细（规格 §3.7）。
     */
    @Test
    fun `失败时传输层error带路径错误码与原因`() {
        val fixture = newFixture()
        fixture.registry.register(
            object : ConfigReloadListener {
                override fun watchedPaths(): Collection<String> = listOf("plugins/Foo/config.yml")
                override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult =
                    ConfigReloadResult.failed(ConfigReloadErrorCode.PARSE_ERROR, "第 12 行缩进错误")
            },
        )
        val (route, capture) = routeWithCapture(fixture)

        val result = route.handle(command(payload = """{"paths":["plugins/Foo/config.yml"]}"""))

        assertFalse(result.success, "插件失败时传输层也必须失败")
        assertTrue(result.error.contains("plugins/Foo/config.yml"), "摘要应点名失败路径，实际=${result.error}")
        assertTrue(result.error.contains("PARSE_ERROR"), "摘要应带上错误码，实际=${result.error}")
        assertTrue(result.error.contains("第 12 行缩进错误"), "摘要应带上可读原因，实际=${result.error}")
        // 失败也必须带结构化回执：规格 §3.7 要求 `reloaded` 如实列出成功的部分，
        // 若这里退化为空 output，面板就只剩一段文本、拿不到机器可读明细。
        // 断言"编码器被调用过"而不是比对具体 JSON——本用例注入的是假编码器。
        assertFalse(capture.notCalled(), "失败路径同样要产出结构化回执")
        assertTrue(result.output.isNotEmpty(), "失败时 output 不得为空，实际='${result.output}'")
    }

    /** 失败时的传输层 `error` 是可读摘要（不是 JSON），便于前端直接展示。 */
    @Test
    fun `失败时传输层error是可读摘要`() {
        val fixture = newFixture()
        fixture.registry.register(
            object : ConfigReloadListener {
                override fun watchedPaths(): Collection<String> = listOf("plugins/Foo/config.yml")
                override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult =
                    ConfigReloadResult.failed(ConfigReloadErrorCode.PLUGIN_REJECTED, "配置项非法")
            },
        )
        val (route, _) = routeWithCapture(fixture)

        val result = route.handle(command(payload = """{"paths":["plugins/Foo/config.yml"]}"""))

        assertFalse(result.success)
        assertTrue(result.error.contains("plugins/Foo/config.yml"), "摘要应点名失败路径，实际=${result.error}")
        assertTrue(result.error.contains("PLUGIN_REJECTED"), "摘要应带上错误码，实际=${result.error}")
        // 摘要化不影响结构化回执：output 仍是编码器产出的完整回执。
        assertTrue(result.output.isNotEmpty(), "失败时 output 不得为空，实际='${result.output}'")
    }

    /**
     * 请求级错误（路径数超限）：传输层失败 + `error` 说明上限。
     *
     * 请求级错误同样按"失败 + 结构化回执"返回（`failed` 为空、`error` 非空），
     * 见 [ConfigReloadCoordinatorTest] 的 `路径数超限整请求拒绝`。
     */
    @Test
    fun `请求级错误时error说明上限`() {
        val fixture = newFixture()
        val (route, capture) = routeWithCapture(fixture)
        val paths = (1..33).joinToString(",", "[", "]") { "\"plugins/Foo/$it.yml\"" }

        val result = route.handle(command(payload = """{"paths":$paths}"""))

        assertFalse(result.success)
        assertTrue(result.error.contains("max-paths-per-request"), "error 应说明上限，实际=${result.error}")
        assertFalse(capture.notCalled(), "请求级错误同样要产出结构化回执")
    }

    // ------------------------------------------------------------------ payload 容错

    /** 空 payload = 全量重载（省略 `paths` 键同样如此）。 */
    @Test
    fun `空payload等价于全量重载`() {
        val fixture = newFixture()
        val listener = CountingListener("plugins/Foo/config.yml")
        fixture.registry.register(listener)
        val (route, capture) = routeWithCapture(fixture)

        val result = route.handle(command(payload = ""))

        assertTrue(result.success)
        assertEquals(listOf("plugins/Foo/config.yml"), capture.require()["reloaded"])
        assertEquals(1, listener.calls.get())
        assertTrue(listener.lastEvent!!.getFullReload(), "空 payload 应触发全量重载")
    }

    /** `paths: []` 同样等价于全量重载。 */
    @Test
    fun `空数组等价于全量重载`() {
        val fixture = newFixture()
        val listener = CountingListener("plugins/Foo/config.yml")
        fixture.registry.register(listener)
        val (route, _) = routeWithCapture(fixture)

        val result = route.handle(command(payload = """{"paths":[]}"""))

        assertTrue(result.success)
        assertTrue(listener.lastEvent!!.getFullReload(), "空数组应触发全量重载")
    }

    /** payload 非法（解码失败）→ 失败回执，且不执行任何重载。 */
    @Test
    fun `payload非法时不执行任何重载`() {
        val fixture = newFixture()
        val listener = CountingListener("plugins/Foo/config.yml")
        fixture.registry.register(listener)
        val (route, _) = routeWithCapture(fixture, decode = { null })

        val result = route.handle(command(payload = "not-json"))

        assertFalse(result.success)
        assertTrue(result.error.contains("payload 非法"), "应说明 payload 非法，实际=${result.error}")
        assertEquals(0, listener.calls.get(), "payload 非法绝不得执行重载")
    }

    // ------------------------------------------------------------------ 测试设施

    /** 捕获回执 Map。 */
    private class PayloadCapture {
        @Volatile
        private var payload: Map<String, Any?>? = null

        fun set(value: Map<String, Any?>) {
            payload = value
        }

        fun require(): Map<String, Any?> = payload ?: error("编码器未被调用（回执未生成）")

        /** 编码器是否一次都没被调用（失败路径的预期）。 */
        fun notCalled(): Boolean = payload == null
    }

    private inner class Fixture(
        val reloadSettings: ReloadSettings,
        val registry: ConfigReloadRegistryImpl,
        val runtime: ReloadRuntime,
    ) : AutoCloseable {

        val coordinator: ConfigReloadCoordinator = ConfigReloadCoordinator(
            runtime = runtime,
            listeners = registry,
            settings = { reloadSettings },
        )

        override fun close() {
            runtime.shutdownNow()
        }
    }

    private fun newFixture(): Fixture {
        val settings = ReloadSettings(timeoutMs = 500L, workerPoolSize = 2, breakerEnabled = false)
        val registry = ConfigReloadRegistryImpl()
        registry.warn = { }
        val fixture = Fixture(settings, registry, ReloadRuntime.of(settings))
        fixtures.add(fixture)
        return fixture
    }

    /** 构造路由与捕获器；[decode] 默认为"逗号分隔的路径列表"。 */
    private fun routeWithCapture(
        fixture: Fixture = newFixture(),
        decode: (String) -> List<String>? = decodePaths,
    ): Pair<ConfigReloadRoute, PayloadCapture> {
        val capture = PayloadCapture()
        val route = ConfigReloadRoute(
            coordinator = fixture.coordinator,
            decode = decode,
            encode = { payload ->
                capture.set(payload)
                "ok"
            },
        )
        return route to capture
    }

    /** 构造一条 `core.config_reload` 命令。 */
    private fun command(
        action: String = ConfigReloadRoute.ACTION,
        domain: String = ConfigReloadRoute.DOMAIN_CORE,
        payload: String = "",
    ): BridgeCommand = BridgeCommand(
        action = action,
        target = "",
        reason = "",
        requestId = "req-config-reload",
        domain = domain,
        payload = payload,
    )

    /** 记录调用次数的监听器。 */
    private class CountingListener(private val path: String) : ConfigReloadListener {

        val calls = AtomicInteger(0)

        @Volatile
        var lastEvent: ConfigReloadEvent? = null

        override fun watchedPaths(): Collection<String> = listOf(path)

        override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult {
            calls.incrementAndGet()
            lastEvent = event
            return ConfigReloadResult.ok()
        }
    }

    private companion object {

        /**
         * 测试用 payload 解析器：提取 `"paths":["a","b"]` 里的字符串。
         *
         * 空 payload → 空列表（全量）；找不到 paths 键 → 空列表（全量）。
         * 与生产 `parsePaths` 的**输出约定一致**，只是不依赖 JSON 库。
         */
        val decodePaths: (String) -> List<String>? = { payload ->
            if (payload.isBlank()) {
                emptyList()
            } else {
                val keyIndex = payload.indexOf("\"paths\"")
                if (keyIndex < 0) {
                    emptyList()
                } else {
                    val open = payload.indexOf('[', keyIndex)
                    val close = payload.indexOf(']', keyIndex)
                    if (open < 0 || close < open) {
                        null
                    } else {
                        Regex("\"([^\"]*)\"")
                            .findAll(payload.substring(open + 1, close))
                            .map { it.groupValues[1] }
                            .toList()
                    }
                }
            }
        }
    }
}
