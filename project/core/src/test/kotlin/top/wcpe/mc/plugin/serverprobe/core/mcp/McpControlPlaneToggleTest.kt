package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.api.model.AggregatedMetrics
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile

/**
 * 校验 FR-24 端点级开关的状态语义。
 *
 * 成功启动路径需要插件数据目录与真实端口绑定，不在单测内执行（由真机验收覆盖）；
 * 此处只断言"未运行"这一确定状态下的开关契约，防止幂等/幂等失败语义回归。
 */
class McpControlPlaneToggleTest {

    @Test
    fun `初始状态未运行且关闭返回 false`() {
        val plane = controlPlane()

        assertFalse(plane.running)
        // 未运行时关闭必须是空操作并回报 false，供命令层区分"确实关了"与"本来就没开"。
        assertFalse(plane.disable())
    }

    @Test
    fun `重复关闭保持幂等不抛异常`() {
        val plane = controlPlane()

        assertFalse(plane.disable())
        assertFalse(plane.disable())

        assertFalse(plane.running)
    }

    /**
     * 构造仅注入必需协作者的控制面。
     *
     * 其余 `lateinit` 字段（平台控制、Arthas 控制、工具提供者注册表）只在启动服务时读取，
     * 本测试不触发启动，故无需装配。
     */
    private fun controlPlane(): McpControlPlane = McpControlPlane().apply {
        readApi = FakeReadApi()
        mcpArtifactWorkspaceRegistry = McpArtifactWorkspaceRegistry()
    }

    /** 最小只读 API 替身：本测试不读取快照，全部返回空值。 */
    private class FakeReadApi : ProbeReadApi {
        override fun latestSnapshot(): MetricSnapshot? = null
        override fun recentSnapshots(limit: Int): List<MetricSnapshot> = emptyList()
        override fun recentSnapshotsSince(sinceMs: Long): List<MetricSnapshot> = emptyList()
        override fun aggregated(windowSize: Int): AggregatedMetrics =
            AggregatedMetrics.builder().windowSampleCount(0).build()
        override fun lastStartupProfile(): StartupProfile? = null
        override fun historyStartupProfiles(limit: Int): List<StartupProfile> = emptyList()
        override fun lastStartupComparisonSummary(): String? = null
    }
}
