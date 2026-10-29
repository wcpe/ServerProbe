package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.fail

/**
 * 桥回执测试的共享辅助。
 *
 * <h2>为什么抽出来</h2>
 * <p>`BusinessDomainStatusTest` 与 `BusinessStatusRouteTest` 曾各自复制了一份
 * `PayloadCapture` 与 `DECODE_DOMAIN`——一字不差。<b>重复的测试辅助比重复的业务代码更危险</b>：
 * 一处修了 bug（比如正则不兼容转义引号），另一处仍然旧样，于是同一份规格会有两套不同判据，
 * 且失败时看起来“其中一个测试坏了”而不是“辅助代码不一致”。</p>
 *
 * <h2>注意：这里只共享“机械件”，不共享测试阈值</h2>
 * <p>两个测试类的 `HealthSettings` 阈值是有意不同的（一个注入 50ms 超时测超时分支，
 * 一个用 500ms 走正常路径），<b>不要</b>因为“看着像重复”就把它们合并。
 */

/**
 * 捕获编码器实际写出的 payload（回执 JSON 结构），供断言逐字段核对。
 */
class PayloadCapture {

    @Volatile
    private var payload: Map<String, Any?>? = null

    fun set(value: Map<String, Any?>) {
        payload = value
    }

    /**
     * 取已捕获的 payload。
     *
     * 编码器未被调用（回执根本没生成）时直接让测试失败，而不是返回 null
     * 让下游抛 NPE——后者的失败信息会指向断言位置而不是真正的缺口。
     */
    fun require(): Map<String, Any?> = payload ?: fail("编码器未被调用（回执未生成）")
}

/**
 * 从原始 payload 文本里抽出 `domain` 字段。
 *
 * 用于验证“路由把请求转给了正确的域”——解码失败返回 null（让调用方断言失败）。
 */
val DECODE_DOMAIN: (String) -> String? = { payload ->
    Regex("\"domain\"\\s*:\\s*\"([^\"]+)\"").find(payload)?.groupValues?.get(1)
}
