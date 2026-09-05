package top.wcpe.mc.plugin.serverprobe.velocity.collector

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Velocity 原始代理观测映射到开放 ProxyMetrics 契约的单元测试。 */
class VelocityProxyMetricMapperTest {

    /** 后端、玩家路由与 ping 均应保持既有 Bungee 侧对外模型。 */
    @Test
    fun `映射保留代理在线后端路由与延迟`() {
        val metrics = VelocityProxyMetricMapper.map(
            totalOnline = 2,
            backends = listOf(VelocityBackendObservation("lobby", 2, 12, true)),
            players = listOf(
                VelocityPlayerObservation("Steve", "lobby", 35),
                VelocityPlayerObservation("Alex", null, -1),
            ),
        )

        assertEquals(2, metrics.totalOnline)
        assertEquals("lobby", metrics.backends.single().name)
        assertEquals(12, metrics.backends.single().pingMs)
        assertEquals(listOf("Steve"), metrics.playerRoutes.map { it.name })
        assertEquals(listOf(35, -1), metrics.playerPings.map { it.pingMs })
    }
}
