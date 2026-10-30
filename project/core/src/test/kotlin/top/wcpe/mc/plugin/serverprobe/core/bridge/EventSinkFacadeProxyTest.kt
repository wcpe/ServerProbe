package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.BridgeServices
import top.wcpe.mc.plugin.serverprobe.api.FacadeAssemblyTestHook
import top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessEventSink
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessHost
import top.wcpe.mc.plugin.serverprobe.api.bridge.EmitReceipt

/**
 * `ServerProbeAPI.eventSink()` 稳定代理的契约验收。
 *
 * <h2>为什么这个类必须存在</h2>
 * <p>`eventSink()` 与 `read()` / `businessHost()` 同级，三个门面都是“稳定代理”。
 * 但此前只有 `read()` 有完整验收（[ReadFacadeProxyTest] 7 条），`eventSink()` **一条都没有**——
 * 它内部那五个方法各自带有 `catch (Throwable)` 降级分支，全都没被跑过。</p>
 *
 * <p>尤其 `:305-311` 的注释强调：“必须显式转发，否则会退化成从参数反推原因”——
 * 那正是一个**一旦被删就会静默劣化**的行为（调用方拿到的 reason 从精确值变成
 * 保守推断的 `REJECTED_SATURATED`，于是本该放弃的重试会变成无限退避）。本类钉住它。</p>
 */
class EventSinkFacadeProxyTest {

    private val hosts = ArrayList<BusinessHostImpl>()

    @AfterEach
    fun tearDown() {
        FacadeAssemblyTestHook.uninstall()
        hosts.forEach { runCatching { it.shutdown() } }
        hosts.clear()
    }

    private fun newHost(): BusinessHost {
        val host = BusinessHostImpl()
        hosts.add(host)
        return host
    }

    /** 只实现业务宿主；`eventSink()` 故意不覆写，用于验证 default 降级单例。 */
    private inner class HostOnlyServices : BridgeServices {
        override fun businessHost(): BusinessHost = newHost()
    }

    /** 覆写了 `eventSink()`，用于验证代理真的委派下去。 */
    private inner class SinkServices(private val sink: BusinessEventSink) : BridgeServices {
        override fun businessHost(): BusinessHost = newHost()
        override fun eventSink(): BusinessEventSink = sink
    }

    /** 记录调用并返回预设结果的假实现。 */
    private class RecordingSink(
        private val receipt: EmitReceipt,
        private val boolResult: Boolean = false,
    ) : BusinessEventSink {

        var emitCalls = 0
        var reliableCalls = 0
        var seqCalls = 0
        var receiptCalls = 0
        var reliableReceiptCalls = 0

        override fun emit(domain: String?, dedupKey: String?, topic: String?, fields: Map<String, String>?): Boolean {
            emitCalls++
            return boolResult
        }

        override fun emitReliable(
            domain: String?,
            dedupKey: String?,
            topic: String?,
            fields: Map<String, String>?,
        ): Boolean {
            reliableCalls++
            return boolResult
        }

        override fun emitReliable(
            domain: String?,
            dedupKey: String?,
            topic: String?,
            fields: Map<String, String>?,
            seq: Long,
        ): Boolean {
            seqCalls++
            return boolResult
        }

        override fun emitReceipt(
            domain: String?,
            dedupKey: String?,
            topic: String?,
            fields: Map<String, String>?,
        ): EmitReceipt {
            receiptCalls++
            return receipt
        }

        override fun emitReliableReceipt(
            domain: String?,
            dedupKey: String?,
            topic: String?,
            fields: Map<String, String>?,
        ): EmitReceipt {
            reliableReceiptCalls++
            return receipt
        }
    }

    /** 所有方法都抛异常的违约实现，用于验证代理的兜底。 */
    private class ThrowingSink : BusinessEventSink {
        override fun emit(domain: String?, dedupKey: String?, topic: String?, fields: Map<String, String>?): Boolean =
            error("emit 违约")

        override fun emitReliable(
            domain: String?,
            dedupKey: String?,
            topic: String?,
            fields: Map<String, String>?,
        ): Boolean = error("emitReliable 违约")

        override fun emitReceipt(
            domain: String?,
            dedupKey: String?,
            topic: String?,
            fields: Map<String, String>?,
        ): EmitReceipt = error("emitReceipt 违约")

        override fun emitReliableReceipt(
            domain: String?,
            dedupKey: String?,
            topic: String?,
            fields: Map<String, String>?,
        ): EmitReceipt = error("emitReliableReceipt 违约")
    }

    @Test
    @DisplayName("返回稳定代理：永不 null、可缓存、多次取同一实例")
    fun `返回稳定代理`() {
        val first = ServerProbeAPI.eventSink()
        val second = ServerProbeAPI.eventSink()

        assertNotNull(first, "eventSink() 永不返回 null")
        assertSame(first, second, "必须是稳定代理，可安全缓存为字段")
    }

    @Test
    @DisplayName("未就绪时五个方法全部降级且不抛")
    fun `未就绪时降级`() {
        FacadeAssemblyTestHook.uninstall()
        val sink = ServerProbeAPI.eventSink()

        // 布尔入口：一律 false
        assertFalse(sink.emit("demo", "k", null, emptyMap()))
        assertFalse(sink.emitReliable("demo", "k", null, emptyMap()))
        assertFalse(sink.emitReliable("demo", "k", null, emptyMap(), 1L))

        // 强语义入口：必须是 PROBE_UNAVAILABLE，而不是保守推断出的可重试饱和。
        // 若降级单例没覆写 emitReceipt，这里会拿到 REJECTED_SATURATED，
        // 调用方就会在“探针永久缺失”时无限退避重试。
        val receipt = sink.emitReceipt("demo", "k", null, emptyMap())
        assertFalse(receipt.isAccepted, "未就绪时不得受理")
        assertEquals(EmitReceipt.Reason.PROBE_UNAVAILABLE, receipt.reason)
        assertFalse(receipt.isRetryable, "探针未就绪时重试无意义")

        val reliableReceipt = sink.emitReliableReceipt("demo", "k", null, emptyMap())
        assertFalse(reliableReceipt.isAccepted)
        assertEquals(EmitReceipt.Reason.PROBE_UNAVAILABLE, reliableReceipt.reason)
        assertFalse(reliableReceipt.isRetryable)
    }

    @Test
    @DisplayName("已就绪时五个方法都委派到真实实现")
    fun `委派到真实实现`() {
        val expected = EmitReceipt.accepted(42L, 1L)
        val fake = RecordingSink(expected, boolResult = true)
        FacadeAssemblyTestHook.install(SinkServices(fake))

        val sink = ServerProbeAPI.eventSink()

        assertTrue(sink.emit("demo", "k", null, emptyMap()))
        assertTrue(sink.emitReliable("demo", "k", null, emptyMap()))
        assertTrue(sink.emitReliable("demo", "k", null, emptyMap(), 7L))

        assertEquals(1, fake.emitCalls, "emit 应被委派一次")
        assertEquals(1, fake.reliableCalls, "emitReliable 应被委派一次")
        assertEquals(1, fake.seqCalls, "带 seq 的 emitReliable 应被委派一次")

        // 强语义入口：必须是把真实实现的返回值原样传出去，
        // 而不是由代理自己“从参数反推原因”（那会丢掉精确 reason 与 seq）。
        val receipt = sink.emitReceipt("demo", "k", null, emptyMap())
        assertSame(expected, receipt, "应原样返回真实实现的凭据（含 seq），不得重新构造")
        assertEquals(1, fake.receiptCalls)

        val reliableReceipt = sink.emitReliableReceipt("demo", "k", null, emptyMap())
        assertSame(expected, reliableReceipt)
        assertEquals(1, fake.reliableReceiptCalls)
    }

    @Test
    @DisplayName("实现违约抛异常时，强语义入口兜底为 PROBE_UNAVAILABLE")
    fun `违约抛异常时兜底`() {
        FacadeAssemblyTestHook.install(SinkServices(ThrowingSink()))
        val sink = ServerProbeAPI.eventSink()

        // 布尔入口兜底为 false（契约：永不抛）
        assertFalse(sink.emit("demo", "k", null, emptyMap()))
        assertFalse(sink.emitReliable("demo", "k", null, emptyMap()))

        // 强语义入口兜底：必须是不可重试的 PROBE_UNAVAILABLE。
        // 若这里报 REJECTED_SATURATED，调用方会把“实现已坏”当成“队列满”而无限重试。
        val receipt = sink.emitReceipt("demo", "k", null, emptyMap())
        assertFalse(receipt.isAccepted)
        assertEquals(EmitReceipt.Reason.PROBE_UNAVAILABLE, receipt.reason)
        assertFalse(receipt.isRetryable)

        val reliableReceipt = sink.emitReliableReceipt("demo", "k", null, emptyMap())
        assertFalse(reliableReceipt.isAccepted)
        assertEquals(EmitReceipt.Reason.PROBE_UNAVAILABLE, reliableReceipt.reason)
        assertFalse(reliableReceipt.isRetryable)
    }

    @Test
    @DisplayName("未覆写 eventSink 时走独立降级单例，不会递归")
    fun `未覆写时走降级单例`() {
        FacadeAssemblyTestHook.install(HostOnlyServices())

        val sink = ServerProbeAPI.eventSink()

        // 不得 StackOverflowError：BridgeServices.eventSink() 的 default 必须返回独立单例，
        // 而不是转发到 ServerProbeAPI.eventSink()。
        assertFalse(sink.emit("demo", "k", null, emptyMap()))
        assertEquals(EmitReceipt.Reason.PROBE_UNAVAILABLE, sink.emitReceipt("demo", "k", null, emptyMap()).reason)
    }

    @Test
    @DisplayName("卸载后缓存的代理引用立即降级")
    fun `卸载后立即降级`() {
        val fake = RecordingSink(EmitReceipt.accepted(), boolResult = true)
        FacadeAssemblyTestHook.install(SinkServices(fake))
        val cached = ServerProbeAPI.eventSink()

        assertTrue(cached.emit("demo", "k", null, emptyMap()), "卸载前应委派成功")

        FacadeAssemblyTestHook.uninstall()

        // 同一引用，卸载后必须立即降级（代理每次实时取当前实现，不缓存实现本身）
        assertFalse(cached.emit("demo", "k", null, emptyMap()), "卸载后缓存引用应降级")
        assertEquals(
            EmitReceipt.Reason.PROBE_UNAVAILABLE,
            cached.emitReceipt("demo", "k", null, emptyMap()).reason,
            "卸载后强语义入口也应降级为不可重试",
        )
    }
}
