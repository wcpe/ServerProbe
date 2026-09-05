package top.wcpe.mc.plugin.serverprobe.e2e

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertTrue

/** FR10 余额验收金额比较测试。 */
class IntegrationsConsumerTest {

    @Test
    fun `最终余额忽略存储层尾随零精度`() {
        val expected = BigDecimal.ZERO + BigDecimal.TEN + BigDecimal.ONE
        val actual = BigDecimal("11.00000000")

        assertTrue(sameAmount(actual, expected))
    }
}
