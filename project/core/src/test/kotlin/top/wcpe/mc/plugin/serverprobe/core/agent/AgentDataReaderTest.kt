package top.wcpe.mc.plugin.serverprobe.core.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [AgentDataReader] 单元测试。
 *
 * **可测范围**:agent 类(`ProbeAgentBridge` / `ProbeAgent`)位于 `plugin` 模块,不在 core 的测试类路径,
 * 故 [AgentDataReader.read] 的 `Class.forName` 必然 [ClassNotFoundException]——这恰好覆盖**最关键的"未挂载降级"
 * 契约**:agent 未挂载时读取须静默返回 [AgentStartupData.notAttached] 而不抛异常、不触碰 TabooLib 平台日志
 * (该路径被刻意设计为不告警)。[AgentDataReader.stopStackSampler] 在类不存在时亦须静默无副作用。
 *
 * 序列化串解析逻辑([AgentDataReader] 私有 `parseNameValue`)的正确性,经 agent 已挂载真机验证 +
 * [StartupProfileBuilderTest] 对透传的结构化断言间接保证;此处不反射私有方法。
 */
class AgentDataReaderTest {

    /** agent 未挂载(类不在测试类路径)时,read 应降级为 notAttached 且不抛异常。 */
    @Test
    fun `read 未挂载时降级为 notAttached`() {
        val data = AgentDataReader.read(5)

        assertFalse(data.attached, "未挂载时 attached 应为 false")
        assertEquals(0L, data.premainNanos, "未挂载时 premainNanos 应为 0")
        assertEquals(0L, data.jvmStartTimeMs, "未挂载时 jvmStartTimeMs 应为 0")
        assertTrue(data.loadTimings.isEmpty(), "未挂载时 loadTimings 应为空")
        assertTrue(data.enableTimings.isEmpty(), "未挂载时 enableTimings 应为空")
        assertTrue(data.libraryTimings.isEmpty(), "未挂载时 libraryTimings 应为空")
        assertTrue(data.hotspots.isEmpty(), "未挂载时 hotspots 应为空")
    }

    /** topN 取非正值时同样应安全降级(read 内部仅透传给 agent getter,未挂载分支先行返回)。 */
    @Test
    fun `read 非正 topN 不抛异常`() {
        val data = AgentDataReader.read(0)
        assertFalse(data.attached, "未挂载时无论 topN 取值都应降级为未挂载")
    }

    /** agent 类不存在时,stopStackSampler 应静默无副作用(不抛异常)。 */
    @Test
    fun `stopStackSampler 未挂载时静默`() {
        // 仅验证不抛异常:类不存在 → runCatching 吞掉,无返回值可断言
        AgentDataReader.stopStackSampler()
    }
}
