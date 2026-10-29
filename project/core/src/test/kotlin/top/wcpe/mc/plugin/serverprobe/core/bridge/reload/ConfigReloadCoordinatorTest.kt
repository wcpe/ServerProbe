package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadErrorCode
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadEvent
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadListener
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommand
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeKernel
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeLinkSnapshot
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgePools
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeStatusRoute
import top.wcpe.mc.plugin.serverprobe.core.bridge.ExecutorKind
import top.wcpe.mc.plugin.serverprobe.core.bridge.FrameSender
import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings
import top.wcpe.mc.plugin.serverprobe.core.config.ReloadSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * [ConfigReloadCoordinator] 单元测试（配置重载规格 §5 验收表逐行）。
 *
 * ## 阈值全部注入极端值
 * `timeout-ms` 用 200~400ms、`stuck-after-ms` 用 100ms，让"超时""卡死"成为确定性断言，
 * 而不是靠 sleep 猜时序。
 *
 * ## 熔断默认关闭
 * 除熔断用例专项打开外，其余用例都关掉熔断——否则前一次超时会把后续请求挡在熔断窗口里，
 * 用例之间互相污染（这类串扰最难查）。
 */
class ConfigReloadCoordinatorTest {

    /** 每个用例自建的夹具，统一收尾。 */
    private val fixtures = ArrayList<Fixture>()

    @AfterEach
    fun tearDown() {
        fixtures.forEach { runCatching { it.close() } }
        fixtures.clear()
    }

    // ------------------------------------------------------------------ 成功 / 无人认领 / 拒绝 / 抛异常

    /** 验收「成功」：监听器返回 `ok()` → `reloaded` 含该 path、`failed` 空、`success=true`、`costMs>=0`、调用计数 +1。 */
    @Test
    fun `成功时计入reloaded且调用计数加一`() {
        val fixture = fixture()
        val listener = OkListener(PATH_A)
        fixture.registry.register(listener)

        val receipt = fixture.coordinator.reload(listOf(PATH_A))

        assertTrue(receipt.success, "全部成功应 success=true")
        assertEquals(listOf(PATH_A), receipt.reloaded)
        assertTrue(receipt.failed.isEmpty(), "不得有失败条目")
        assertTrue(receipt.bestEffort.isEmpty(), "有监听器认领就不该进 bestEffort")
        // costMs 是耗时：非负是必然的，单写 `>= 0` 等于没验。
        // 真正有意义的是上界——一次监听器回调不应跑出一分钟。
        assertTrue(
            receipt.costMs < 60_000L,
            "costMs 应在合理范围（<60s），实际=${receipt.costMs}ms（异常大值意味着计时区间错了）",
        )
        assertTrue(receipt.error.isEmpty())
        assertEquals(1, listener.calls.get(), "监听器应被调用 1 次")
    }

    /** 验收「无人认领」：无监听器 → `bestEffort`、`reloaded` 与 `failed` 均空、`success=true`。 */
    @Test
    fun `无人认领的路径进bestEffort且整体成功`() {
        val fixture = fixture()

        val receipt = fixture.coordinator.reload(listOf(PATH_A))

        assertTrue(receipt.success, "无人认领算成功（产品决策，字段名 bestEffort 已自解释）")
        assertEquals(listOf(PATH_A), receipt.bestEffort)
        assertTrue(receipt.reloaded.isEmpty())
        assertTrue(receipt.failed.isEmpty())
    }

    /** 验收「插件拒绝」：返回 `failed(PLUGIN_REJECTED, ...)` → `failed[0].code=PLUGIN_REJECTED`。 */
    @Test
    fun `插件拒绝映射为PLUGIN_REJECTED`() {
        val fixture = fixture()
        fixture.registry.register(FailListener(PATH_A, ConfigReloadErrorCode.PLUGIN_REJECTED, "配置项非法"))

        val receipt = fixture.coordinator.reload(listOf(PATH_A))

        assertFalse(receipt.success, "有失败条目即整体失败")
        assertEquals(1, receipt.failed.size)
        assertEquals(PATH_A, receipt.failed[0].path)
        assertEquals(ConfigReloadErrorCode.PLUGIN_REJECTED, receipt.failed[0].code)
        assertEquals("配置项非法", receipt.failed[0].message)
    }

    /** 验收「插件抛异常」：监听器 throw → `PLUGIN_THREW`，整段不失败、不抛。 */
    @Test
    fun `插件抛异常被兜住为PLUGIN_THREW`() {
        val fixture = fixture()
        fixture.registry.register(ThrowingListener(PATH_A))

        val receipt = runCatching { fixture.coordinator.reload(listOf(PATH_A)) }
            .getOrElse { fail<ReloadReceipt>("协调器绝不得把监听器异常抛出去:${it.javaClass.name}"); error("unreachable") }

        assertFalse(receipt.success)
        assertEquals(ConfigReloadErrorCode.PLUGIN_THREW, receipt.failed[0].code)
        assertTrue(receipt.failed[0].message.contains("IllegalStateException"), "message 应说明异常类型")
    }

    /** 监听器返回 null → 失败（缺结果不得当作成功）。 */
    @Test
    fun `监听器返回null视为失败`() {
        val fixture = fixture()
        fixture.registry.register(NullResultListener(PATH_A))

        val receipt = fixture.coordinator.reload(listOf(PATH_A))

        assertFalse(receipt.success)
        assertEquals(ConfigReloadErrorCode.PLUGIN_REJECTED, receipt.failed[0].code)
        assertTrue(receipt.failed[0].message.contains("未返回结果"))
    }

    /** 验收「部分成功」：两个 path 一成一败 → `reloaded` 与 `failed` 各一条、`success=false`。 */
    @Test
    fun `部分成功时reloaded与failed各一条`() {
        val fixture = fixture()
        fixture.registry.register(OkListener(PATH_A))
        fixture.registry.register(FailListener(PATH_B, ConfigReloadErrorCode.PARSE_ERROR, "第 12 行缩进错误"))

        val receipt = fixture.coordinator.reload(listOf(PATH_A, PATH_B))

        assertFalse(receipt.success, "部分失败按严格口径整体失败")
        assertEquals(listOf(PATH_A), receipt.reloaded, "成功的部分仍如实列出")
        assertEquals(listOf(PATH_B), receipt.failed.map { it.path })
        assertEquals(ConfigReloadErrorCode.PARSE_ERROR, receipt.failed[0].code)
    }

    // ------------------------------------------------------------------ 越界 / 路径上限

    /** 验收「越界」：四种越界输入均 `PATH_ESCAPE`，且**不调用任何监听器**。 */
    @Test
    fun `越界路径全部返回PATH_ESCAPE`() {
        val fixture = fixture()
        val listener = OkListener("plugins/")
        fixture.registry.register(listener)
        val raw = listOf("../../etc/passwd", "/etc/passwd", "plugins/../secret", "plugins/A/${0.toChar()}x")

        val receipt = fixture.coordinator.reload(raw)

        assertFalse(receipt.success)
        assertEquals(4, receipt.failed.size)
        assertTrue(receipt.failed.all { it.code == ConfigReloadErrorCode.PATH_ESCAPE }, "四种都应 PATH_ESCAPE")
        assertEquals(
            raw.sorted(),
            receipt.failed.map { it.path },
            "PATH_ESCAPE 条目保留原始输入（回执按路径排序），便于 JM 对账",
        )
        assertEquals(0, listener.calls.get(), "越界路径绝不得触发插件")
    }

    /** 验收「路径上限」：33 个 path（上限 32）→ `success=false`、`error` 非空、`failed` 为空。 */
    @Test
    fun `路径数超限整请求拒绝`() {
        val fixture = fixture()
        val listener = OkListener("plugins/")
        fixture.registry.register(listener)

        val receipt = fixture.coordinator.reload((1..33).map { "plugins/Foo/$it.yml" })

        assertFalse(receipt.success)
        assertTrue(receipt.error.contains("max-paths-per-request=32"), "error 应带上限值，实际=${receipt.error}")
        assertTrue(receipt.failed.isEmpty(), "请求级错误不产生 failed 条目")
        assertTrue(receipt.reloaded.isEmpty())
        assertEquals(0, listener.calls.get(), "超限请求不得执行任何重载")
    }

    // ------------------------------------------------------------------ 超时 / 快慢混合

    /** 验收「超时」：`timeout-ms=200` + 永不返回的监听器 → `TIMEOUT`（"已执行未完成"）。 */
    @Test
    fun `监听器不返回时回执TIMEOUT并说明已执行未完成`() {
        val fixture = fixture(settings = settings(timeoutMs = 200L))
        fixture.registry.register(InterruptibleBlockingListener(PATH_A))

        val receipt = fixture.coordinator.reload(listOf(PATH_A))

        assertFalse(receipt.success)
        assertEquals(1, receipt.failed.size)
        assertEquals(ConfigReloadErrorCode.TIMEOUT, receipt.failed[0].code)
        assertTrue(receipt.failed[0].message.contains("已执行未完成"), "应说明已执行未完成，实际=${receipt.failed[0].message}")
        assertTrue(receipt.costMs >= 200L, "应至少耗尽预算，实际=${receipt.costMs}")
    }

    /**
     * 验收「超时」后半句：**超时期间向 cmd 池发一条 `bridge_status` 仍立即返回**。
     *
     * 这条断言证明"读线程与命令链都活着"——重载卡死不得传染治理通道。
     */
    @Test
    fun `重载超时期间命令池的bridge_status仍立即返回`() {
        val fixture = fixture(settings = settings(timeoutMs = 400L))
        val started = CountDownLatch(1)
        fixture.registry.register(BlockingListener(PATH_A, started))
        val line = LineSettings()
        val pools = BridgePools(line)
        val sender = FrameSender(line, null)
        try {
            val kernel = BridgeKernel(sender, pools, parse = { null }, encodeReceipt = { _, _ -> null })
            val status = BridgeStatusRoute(
                sender,
                kernel,
                topics = { emptyList() },
                encode = { "ok" },
                snapshot = { BridgeLinkSnapshot() },
            )
            val reloadThread = Thread({ fixture.coordinator.reload(listOf(PATH_A)) }, "reload-caller").apply {
                isDaemon = true
                start()
            }
            assertTrue(started.await(2, TimeUnit.SECONDS), "监听器应已开始执行")

            val elapsedMs = timeOnCommandPool(pools, status)

            assertTrue(elapsedMs < 200L, "重载超时期间 bridge_status 必须立即返回，实际=${elapsedMs}ms")
            reloadThread.join(2_000L)
        } finally {
            sender.shutdown()
            pools.shutdownNow()
        }
    }

    /**
     * 验收「快慢混合」：1 个秒回 + 1 个卡死 → 秒回的必须 `ok`，不得被慢项拖成 `TIMEOUT`。
     *
     * 这是轮转收割的直接证据：按顺序死等第一个的实现会让快项也超时。
     */
    @Test
    fun `快慢混合时快项必须成功`() {
        val fixture = fixture(settings = settings(timeoutMs = 300L))
        fixture.registry.register(OkListener(PATH_A))
        fixture.registry.register(InterruptibleBlockingListener(PATH_B))

        val receipt = fixture.coordinator.reload(listOf(PATH_A, PATH_B))

        assertTrue(receipt.reloaded.contains(PATH_A), "秒回的项必须成功，实际 reloaded=${receipt.reloaded}")
        assertTrue(
            receipt.failed.none { it.path == PATH_A },
            "快项不得被慢项拖入失败，实际 failed=${receipt.failed.map { it.path }}",
        )
        assertEquals(ConfigReloadErrorCode.TIMEOUT, receipt.failed.single { it.path == PATH_B }.code)
    }

    // ------------------------------------------------------------------ 池分离

    /**
     * 验收「池分离」：4 个并发 `config_reload` × 每条 4 个 path（协调池 4 + worker 池 8）
     * → 全部在预算内返回、**互不产生 `TIMEOUT`**。
     *
     * ## 为什么用 16 个精确监听器
     * 子任务的粒度是「一个监听器 × 它命中的路径集合」。要让"每请求 4 个子任务、共 16 个"
     * 真正排队（从而检验 worker 池容量），每个路径必须由**不同**监听器认领。
     *
     * ## 若两池共用同一个有界池
     * 4 个协调任务各占 1 个线程后把 16 个子任务推进同一队列，队列一满即无空闲线程
     * → 协调器全部阻塞在 future.get → 全部误判 `TIMEOUT`。本用例在该实现下必失败。
     */
    @Test
    fun `四并发每请求四路径全部返回且无TIMEOUT`() {
        val fixture = fixture(settings = settings(timeoutMs = 1_000L, workerPoolSize = 8))
        val requests = (1..4).map { index -> (1..4).map { "plugins/P$index/item$it.yml" } }
        val listeners = requests.flatten().map { SlowListener(it, sleepMs = 40L) }
        listeners.forEach { fixture.registry.register(it) }
        val reloadPool = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "serverprobe-reload-test").apply { isDaemon = true }
        }
        try {
            val futures = requests.map { paths ->
                reloadPool.submit<ReloadReceipt> { fixture.coordinator.reload(paths) }
            }
            val receipts = futures.map { it.get(10, TimeUnit.SECONDS) }

            receipts.forEachIndexed { index, receipt ->
                val timeouts = receipt.failed.filter { it.code == ConfigReloadErrorCode.TIMEOUT }
                assertTrue(timeouts.isEmpty(), "第 ${index + 1} 个请求不应出现 TIMEOUT，实际=${receipt.failed}")
                assertEquals(4, receipt.reloaded.size, "四个路径都应成功，实际=${receipt.reloaded}")
            }
            assertEquals(16, listeners.sumOf { it.calls.get() }, "16 个（请求 × 路径）子任务各回调一次")
        } finally {
            reloadPool.shutdownNow()
        }
    }

    // ------------------------------------------------------------------ 在途 / 卡死 / 全量互斥

    /** 验收「在途」：并发同 path → 第二个 `IN_FLIGHT`，监听器仅执行 1 次。 */
    @Test
    fun `同路径并发时第二个返回IN_FLIGHT`() {
        val fixture = fixture(settings = settings(timeoutMs = 2_000L))
        val started = CountDownLatch(1)
        val listener = BlockingListener(PATH_A, started)
        fixture.registry.register(listener)

        val first = fixture.coordinatorAsync(listOf(PATH_A))
        assertTrue(started.await(2, TimeUnit.SECONDS), "首个重载应已进入监听器")
        val second = fixture.coordinator.reload(listOf(PATH_A))

        assertEquals(ConfigReloadErrorCode.IN_FLIGHT, second.failed.single().code)
        assertTrue(second.failed.single().message.contains("同路径已有重载在途"), "message 应点名在途")
        assertTrue(first.get(5, TimeUnit.SECONDS).success, "首个重载应成功")
        assertEquals(1, listener.calls.get(), "监听器只应被执行 1 次")
    }

    /** 验收「卡死」：任务不响应中断且超时 → 后续同 path 返回 `STUCK`（不是 `IN_FLIGHT`），message 含"疑似卡死"。 */
    @Test
    fun `不响应中断且超时后同路径返回STUCK`() {
        val fixture = fixture(settings = settings(timeoutMs = 200L, stuckAfterMs = 100L))
        fixture.registry.register(UninterruptibleListener(PATH_A))

        val first = fixture.coordinator.reload(listOf(PATH_A))
        assertEquals(ConfigReloadErrorCode.TIMEOUT, first.failed.single().code)

        val second = fixture.coordinator.reload(listOf(PATH_A))

        assertEquals(ConfigReloadErrorCode.STUCK, second.failed.single().code, "卡死必须报 STUCK，不能用 IN_FLIGHT 掩盖")
        assertTrue(second.failed.single().message.contains("疑似卡死"), "message 应写明疑似卡死")
    }

    /** 验收「全量互斥」：全量在途时单路径 → `IN_FLIGHT`。 */
    @Test
    fun `全量在途时单路径返回IN_FLIGHT`() {
        val fixture = fixture(settings = settings(timeoutMs = 2_000L))
        val started = CountDownLatch(1)
        fixture.registry.register(BlockingListener(PATH_A, started))

        val full = fixture.coordinatorAsync(emptyList())
        assertTrue(started.await(2, TimeUnit.SECONDS), "全量重载应已进入监听器")
        val single = fixture.coordinator.reload(listOf(PATH_A))

        assertEquals(ConfigReloadErrorCode.IN_FLIGHT, single.failed.single().code)
        assertTrue(single.failed.single().message.contains("全量重载在途"))
        assertTrue(full.get(5, TimeUnit.SECONDS).success)
    }

    /** 验收「全量互斥」：单路径在途时全量 → `IN_FLIGHT`。 */
    @Test
    fun `单路径在途时全量返回IN_FLIGHT`() {
        val fixture = fixture(settings = settings(timeoutMs = 2_000L))
        val started = CountDownLatch(1)
        fixture.registry.register(BlockingListener(PATH_A, started))

        val single = fixture.coordinatorAsync(listOf(PATH_A))
        assertTrue(started.await(2, TimeUnit.SECONDS), "单路径重载应已进入监听器")
        val full = fixture.coordinator.reload(emptyList())

        assertEquals(ConfigReloadErrorCode.IN_FLIGHT, full.failed.single().code)
        assertTrue(
            full.failed.single().message.contains("单路径重载在途"),
            "message 应说明被哪条单路径挡住，实际=${full.failed.single().message}",
        )
        assertTrue(single.get(5, TimeUnit.SECONDS).success)
    }

    /** 全量重载：向所有已注册监听器各通知一次，`matchedPaths` 为其声明集合。 */
    @Test
    fun `全量重载通知所有监听器`() {
        val fixture = fixture()
        val first = OkListener(PATH_A)
        val second = OkListener("plugins/Bar/")
        fixture.registry.register(first)
        fixture.registry.register(second)

        val receipt = fixture.coordinator.reload(emptyList())

        assertTrue(receipt.success)
        assertEquals(
            listOf("plugins/Bar/", PATH_A),
            receipt.reloaded,
            "reloaded 为各监听器声明集合的并集（按路径排序）",
        )
        assertEquals(1, first.calls.get())
        assertEquals(1, second.calls.get())
        assertTrue(first.lastEvent!!.getFullReload(), "全量事件应标记 fullReload")
        assertTrue(first.lastEvent!!.requestedPaths.isEmpty(), "全量时 requestedPaths 为空")
    }

    /** 全量重载且无监听器：整体成功、三个列表都空（不是失败）。 */
    @Test
    fun `全量重载无监听器时成功且无条目`() {
        val receipt = fixture().coordinator.reload(emptyList())

        assertTrue(receipt.success)
        assertTrue(receipt.reloaded.isEmpty())
        assertTrue(receipt.bestEffort.isEmpty())
        assertTrue(receipt.failed.isEmpty())
    }

    // ------------------------------------------------------------------ 熔断

    /** 验收「熔断」：超时后窗口内再请求同 path → `TIMEOUT` 且监听器执行计数**不增**。 */
    @Test
    fun `超时后窗口内不再执行且计数不增`() {
        val fixture = fixture(settings = settings(timeoutMs = 200L, breakerEnabled = true))
        val listener = InterruptibleBlockingListener(PATH_A)
        fixture.registry.register(listener)

        val first = fixture.coordinator.reload(listOf(PATH_A))
        assertEquals(ConfigReloadErrorCode.TIMEOUT, first.failed.single().code)
        val callsAfterFirst = listener.calls.get()

        val second = fixture.coordinator.reload(listOf(PATH_A))

        assertEquals(ConfigReloadErrorCode.TIMEOUT, second.failed.single().code)
        assertTrue(second.failed.single().message.contains("熔断"), "message 应说明熔断，实际=${second.failed.single().message}")
        assertEquals(callsAfterFirst, listener.calls.get(), "熔断期间监听器执行计数不得增加")
    }

    /** 熔断被记录：超时后熔断表出现该路径且已打开（退避倍数的断言见 CircuitBreakerTest）。 */
    @Test
    fun `超时会写入熔断表`() {
        val fixture = fixture(settings = settings(timeoutMs = 200L, breakerEnabled = true))
        fixture.registry.register(InterruptibleBlockingListener(PATH_A))

        fixture.coordinator.reload(listOf(PATH_A))

        val entry = fixture.runtime.breaker.snapshot(System.currentTimeMillis()).single()
        assertEquals(PATH_A, entry.path)
        assertEquals(1, entry.failures)
        assertTrue(entry.open, "首次超时即应熔断（默认阈值 1）")
    }

    /** 成功一次即清零：先超时熔断，再在窗口过后成功 → 熔断表条目消失。 */
    @Test
    fun `成功后熔断计数清零`() {
        val fixture = fixture(
            settings = settings(timeoutMs = 100L, breakerEnabled = true, breakerOpenMs = 100L),
        )
        val flaky = FlakyListener(PATH_A)
        fixture.registry.register(flaky)

        fixture.coordinator.reload(listOf(PATH_A))
        // 等熔断窗口过期，并等首个任务真正让出在途占位（否则第二次会先撞 IN_FLIGHT）。
        Thread.sleep(150L)
        awaitUntil("首个任务已释放在途占位") { fixture.runtime.inFlight.snapshot(System.currentTimeMillis()).isEmpty() }
        val second = fixture.coordinator.reload(listOf(PATH_A))

        assertTrue(second.success, "熔断窗口过后应能成功，实际=${second.failed}")
        assertTrue(fixture.runtime.breaker.snapshot(System.currentTimeMillis()).isEmpty(), "成功后计数应清零")
    }

    // ------------------------------------------------------------------ 幂等

    /**
     * 验收「幂等」：连续两次成功重载 → **逐字段结构化相等**（排除 `costMs`），
     * 监听器调用计数恰 +2，累加型自报字段不翻倍。
     */
    @Test
    fun `连续两次成功重载逐字段相等且计数恰加二`() {
        val fixture = fixture()
        val listener = AccumulatingListener(PATH_A, entries = 3)
        fixture.registry.register(listener)

        val first = fixture.coordinator.reload(listOf(PATH_A))
        val second = fixture.coordinator.reload(listOf(PATH_A))

        assertTrue(first.success && second.success)
        assertEquals(first.copy(costMs = 0L), second.copy(costMs = 0L), "除 costMs 外所有字段应逐项相等")
        assertEquals(2, listener.calls.get(), "监听器调用次数恰为 2")
        assertEquals(3, first.applied[PATH_A]?.count, "自报条目数应如实透传")
        assertEquals(3, second.applied[PATH_A]?.count, "累加型自报字段不得翻倍（第二次仍是 3）")
        assertEquals(3, listener.loadedEntries, "插件内部状态在第二次之后应与第一次相同（不得累加）")
    }

    // ------------------------------------------------------------------ 三种"没有结果"

    /** 提交被拒（池已关闭）→ `INTERNAL_ERROR`，message 说明"未执行"。 */
    @Test
    fun `提交被拒时返回INTERNAL_ERROR`() {
        val fixture = fixture()
        fixture.registry.register(OkListener(PATH_A))
        fixture.runtime.workerPool.shutdownNow()

        val receipt = fixture.coordinator.reload(listOf(PATH_A))

        assertEquals(ConfigReloadErrorCode.INTERNAL_ERROR, receipt.failed.single().code)
        assertTrue(receipt.failed.single().message.contains("未执行"), "应说明未执行")
    }

    /** 排队到超时仍未被取走 → `TIMEOUT`，message 说明"未轮到执行"。 */
    @Test
    fun `排队到超时返回TIMEOUT且说明未轮到执行`() {
        val fixture = fixture(settings = settings(timeoutMs = 150L, workerPoolSize = 1))
        // 用"忽略中断"的监听器占住唯一工作线程：它不会因首个请求超时而让位。
        val occupier = UninterruptibleListener("plugins/Long/config.yml")
        fixture.registry.register(occupier)
        fixture.coordinatorAsync(listOf("plugins/Long/config.yml"))
        awaitUntil("工作线程被占住") { occupier.calls.get() >= 1 }
        fixture.registry.register(OkListener(PATH_A))

        val receipt = fixture.coordinator.reload(listOf(PATH_A))

        assertEquals(ConfigReloadErrorCode.TIMEOUT, receipt.failed.single().code)
        assertTrue(receipt.failed.single().message.contains("未轮到执行"), "应说明未轮到执行，实际=${receipt.failed.single().message}")
    }

    // ------------------------------------------------------------------ 派发语义

    /** 同一监听器命中多路径：**只回调一次**，`matchedPaths` 给出全部命中项。 */
    @Test
    fun `同一监听器命中多路径只回调一次`() {
        val fixture = fixture()
        val listener = OkListener("plugins/Foo/")
        fixture.registry.register(listener)

        val receipt = fixture.coordinator.reload(listOf(PATH_A, "plugins/Foo/other.yml"))

        assertEquals(1, listener.calls.get(), "同一监听器应只被调用一次")
        assertEquals(
            listOf("plugins/Foo/config.yml", "plugins/Foo/other.yml"),
            listener.lastEvent!!.matchedPaths,
            "matchedPaths 应给出该监听器命中的全部路径（已排序）",
        )
        assertEquals(2, receipt.reloaded.size)
    }

    /** 自报字段：未上报的键整个缺席（不是写 0）。 */
    @Test
    fun `未上报的自报字段缺席`() {
        val fixture = fixture()
        fixture.registry.register(OkListener(PATH_A))

        val receipt = fixture.coordinator.reload(listOf(PATH_A))

        assertFalse(receipt.applied.containsKey(PATH_A), "什么都没上报时 applied 不应含该路径")
        val payload = receipt.toPayload()
        @Suppress("UNCHECKED_CAST")
        val applied = payload["applied"] as Map<String, Any?>
        assertTrue(applied.isEmpty(), "applied 应为空 Map（不是缺键）")
    }

    // ------------------------------------------------------------------ 测试设施

    /** 手工可控的协调器夹具。 */
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

        /** 在独立线程上跑一次（用于"占住"与并发场景）。 */
        fun coordinatorAsync(paths: List<String>): java.util.concurrent.Future<ReloadReceipt> =
            singleThread.submit<ReloadReceipt> { coordinator.reload(paths) }

        private val singleThread = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "reload-caller-test").apply { isDaemon = true }
        }

        override fun close() {
            singleThread.shutdownNow()
            runtime.shutdownNow()
        }
    }

    private fun fixture(settings: ReloadSettings = settings()): Fixture {
        val registry = ConfigReloadRegistryImpl()
        registry.warn = { }
        val fixture = Fixture(settings, registry, ReloadRuntime.of(settings))
        fixtures.add(fixture)
        return fixture
    }

    /** 极端阈值：总预算默认 400ms（够慢动作，但不至于让用例跑很久）。 */
    private fun settings(
        timeoutMs: Long = 400L,
        workerPoolSize: Int = 4,
        stuckAfterMs: Long = 60_000L,
        breakerEnabled: Boolean = false,
        breakerOpenMs: Long = 60_000L,
    ): ReloadSettings = ReloadSettings(
        timeoutMs = timeoutMs,
        maxPathsPerRequest = 32,
        workerPoolSize = workerPoolSize,
        stuckAfterMs = stuckAfterMs,
        breakerEnabled = breakerEnabled,
        breakerFailureWindowMs = 60_000L,
        breakerOpenDurationMs = breakerOpenMs,
        breakerMaxOpenDurationMs = 600_000L,
        breakerFailureThreshold = 1,
        breakerMaxEntries = 256,
    )

    /** 在命令池上跑一次 `bridge_status`，返回耗时（毫秒）。 */
    private fun timeOnCommandPool(pools: BridgePools, route: BridgeStatusRoute): Long {
        val done = CountDownLatch(1)
        val elapsed = AtomicLong(-1L)
        val submittedAt = System.nanoTime()
        val accepted = pools.submit(ExecutorKind.COMMAND) {
            runCatching {
                route.handle(
                    BridgeCommand(
                        action = BridgeStatusRoute.ACTION,
                        target = "",
                        reason = "",
                        requestId = "req-status",
                        domain = BridgeStatusRoute.DOMAIN_CORE,
                        payload = "",
                    ),
                )
            }
            elapsed.set((System.nanoTime() - submittedAt) / 1_000_000L)
            done.countDown()
        }
        assertTrue(accepted, "命令池应接受 bridge_status")
        assertTrue(done.await(2, TimeUnit.SECONDS), "bridge_status 应在 2s 内返回")
        return elapsed.get()
    }

    /** 有界轮询直到条件成立。 */
    private fun awaitUntil(description: String, timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(5L)
        }
        fail<Unit>("等待超时：$description")
    }

    // ------------------------------------------------------------------ 监听器桩

    /** 恒定返回 `ok()`，并记录调用次数与最近事件。 */
    private open class OkListener(vararg paths: String) : ConfigReloadListener {

        private val declared: List<String> = paths.toList()
        val calls = AtomicInteger(0)

        @Volatile
        var lastEvent: ConfigReloadEvent? = null

        override fun watchedPaths(): Collection<String> = declared

        override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult {
            calls.incrementAndGet()
            lastEvent = event
            return ConfigReloadResult.ok()
        }
    }

    /** 恒定返回指定失败。 */
    private class FailListener(
        private val path: String,
        private val code: ConfigReloadErrorCode,
        private val message: String,
    ) : ConfigReloadListener {

        override fun watchedPaths(): Collection<String> = listOf(path)

        override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult =
            ConfigReloadResult.failed(code, message)
    }

    /** 抛异常。 */
    private class ThrowingListener(private val path: String) : ConfigReloadListener {

        override fun watchedPaths(): Collection<String> = listOf(path)

        // 刻意抛 IllegalStateException 而非 check()/error()：这里要验证的是
        // "第三方插件随手抛的普通运行时异常会被兜住并转成 PLUGIN_THREW"，
        // 换成 error() 会变成 IllegalStateException 的另一个构造路径，语义反而被弱化。
        @Suppress("UseCheckOrError")
        override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult =
            throw IllegalStateException("boom")
    }

    /** 返回 null（契约违约）。 */
    private class NullResultListener(private val path: String) : ConfigReloadListener {

        override fun watchedPaths(): Collection<String> = listOf(path)

        override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult? = null
    }

    /** 每次回调睡固定时长（用于制造排队压力）。 */
    private class SlowListener(private val path: String, private val sleepMs: Long) : ConfigReloadListener {

        val calls = AtomicInteger(0)

        override fun watchedPaths(): Collection<String> = listOf(path)

        override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult {
            calls.incrementAndGet()
            Thread.sleep(sleepMs)
            return ConfigReloadResult.ok()
        }
    }

    /**
     * 阻塞直到外部释放（**远短于** `timeout-ms`，避免"首个请求自己也超时"这类串扰），
     * 并先用 [started] 通报"已进入回调"。
     */
    private class BlockingListener(private val path: String, private val started: CountDownLatch) : ConfigReloadListener {

        private val gate = CountDownLatch(1)
        val calls = AtomicInteger(0)

        override fun watchedPaths(): Collection<String> = listOf(path)

        override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult {
            calls.incrementAndGet()
            started.countDown()
            gate.await(GATE_WAIT_MS, TimeUnit.MILLISECONDS)
            return ConfigReloadResult.ok()
        }
    }

    /** 一直阻塞，但**响应中断**（超时用例用它，收尾时能被 cancel 打断）。 */
    private class InterruptibleBlockingListener(private val path: String) : ConfigReloadListener {

        private val gate = CountDownLatch(1)
        val calls = AtomicInteger(0)

        override fun watchedPaths(): Collection<String> = listOf(path)

        override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult {
            calls.incrementAndGet()
            try {
                gate.await()
            } catch (_: InterruptedException) {
                // 被超时判定打断：本结果会被协调器丢弃（迟到结果不得污染回执）。
            }
            return ConfigReloadResult.ok()
        }
    }

    /** 一直阻塞且**忽略中断**（卡死用例用它，模拟不响应中断的病态插件）。 */
    private class UninterruptibleListener(private val path: String) : ConfigReloadListener {

        val calls = AtomicInteger(0)

        override fun watchedPaths(): Collection<String> = listOf(path)

        override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult {
            calls.incrementAndGet()
            while (true) {
                try {
                    Thread.sleep(1_000L)
                } catch (_: InterruptedException) {
                    // 刻意忽略：模拟"不响应中断"的真实插件。
                }
            }
        }
    }

    /** 首次阻塞超时、之后正常返回（熔断清零用例）。 */
    private class FlakyListener(private val path: String) : ConfigReloadListener {

        private val gate = CountDownLatch(1)
        val calls = AtomicInteger(0)

        override fun watchedPaths(): Collection<String> = listOf(path)

        override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult {
            if (calls.incrementAndGet() == 1) {
                try {
                    gate.await()
                } catch (_: InterruptedException) {
                    // 首个请求被判定超时；第二次调用起直接成功。
                }
            }
            return ConfigReloadResult.ok()
        }
    }

    /**
     * 「重载」式插件：每次回调都把条目数重置为自己的真实状态，并自报 md5/version/count。
     *
     * 它模拟正确实现的插件——**不会因为被调用两次就把 count 累加**，
     * 因此回执里的 `count` 两次都必须是 3（若探针把结果累加，这里会变成 6）。
     */
    private class AccumulatingListener(private val path: String, entries: Int) : ConfigReloadListener {

        private val fixedEntries = entries
        val calls = AtomicInteger(0)

        @Volatile
        var loadedEntries: Int = entries
            private set

        override fun watchedPaths(): Collection<String> = listOf(path)

        override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult {
            calls.incrementAndGet()
            // 真正的"重载"是覆盖而不是累加。
            loadedEntries = fixedEntries
            return ConfigReloadResult.ok()
                .withAppliedMd5("md5-fixed")
                .withAppliedVersion("v1")
                .withAppliedCount(loadedEntries)
        }
    }

    private companion object {
        const val PATH_A = "plugins/Foo/config.yml"
        const val PATH_B = "plugins/Baz/config.yml"

        /** 阻塞型监听器的单次阻塞上限：须远小于用例里的 `timeout-ms`（最短 400ms 之外另设小值）。 */
        const val GATE_WAIT_MS = 300L
    }
}
