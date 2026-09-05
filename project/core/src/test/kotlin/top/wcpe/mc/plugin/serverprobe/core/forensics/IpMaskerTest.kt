package top.wcpe.mc.plugin.serverprobe.core.forensics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Prometheus IP 标签脱敏的纯逻辑测试。 */
class IpMaskerTest {

    /** IPv4 标签必须精确掩到 /24。 */
    @Test
    fun `IPv4 掩码为24位前缀`() {
        assertEquals("203.0.113.0/24", IpMasker.mask("203.0.113.77"))
    }

    /** IPv6 标签必须精确掩到 /64。 */
    @Test
    fun `IPv6 掩码为64位前缀`() {
        assertEquals("2001:db8:1:2:0:0:0:0/64", IpMasker.mask("2001:db8:1:2:3:4:5:6"))
    }
}
