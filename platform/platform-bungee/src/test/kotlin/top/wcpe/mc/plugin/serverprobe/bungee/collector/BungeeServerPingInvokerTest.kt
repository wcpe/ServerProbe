package top.wcpe.mc.plugin.serverprobe.bungee.collector

import net.md_5.bungee.api.Callback
import net.md_5.bungee.api.ServerPing
import net.md_5.bungee.api.config.ServerInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

/** BungeeCord 回调式子服 ping 兼容测试。 */
class BungeeServerPingInvokerTest {

    /** 仅提供 Callback 重载的 ServerInfo 也必须判定为可达。 */
    @Test
    fun `回调式 ping 成功时记录可达状态`() {
        val serverInfo = serverInfo { callback -> callback.done(ServerPing(), null) }

        val result = BungeeServerPingInvoker.ping(serverInfo, 1, TimeUnit.SECONDS)

        assertTrue(result.reachable, "回调成功时子服必须标记为可达")
        assertTrue(result.pingMs >= 0, "回调成功时必须记录非负 RTT")
    }

    /** Callback 返回异常时必须保持不可达降级语义。 */
    @Test
    fun `回调式 ping 失败时保持不可达状态`() {
        val serverInfo = serverInfo { callback -> callback.done(null, IllegalStateException("连接失败")) }

        val result = BungeeServerPingInvoker.ping(serverInfo, 1, TimeUnit.SECONDS)

        assertEquals(false, result.reachable, "回调失败时子服必须标记为不可达")
        assertEquals(-1, result.pingMs, "回调失败时 RTT 必须为 -1")
    }

    /** 回调未返回时必须按上限结束等待，不能挂住后台采集线程。 */
    @Test
    fun `回调式 ping 超时时保持不可达状态`() {
        val serverInfo = serverInfo { }

        val result = BungeeServerPingInvoker.ping(serverInfo, 10, TimeUnit.MILLISECONDS)

        assertEquals(false, result.reachable, "回调超时时子服必须标记为不可达")
        assertEquals(-1, result.pingMs, "回调超时时 RTT 必须为 -1")
    }

    private fun serverInfo(onPing: (Callback<ServerPing>) -> Unit): ServerInfo {
        return Proxy.newProxyInstance(
            ServerInfo::class.java.classLoader,
            arrayOf(ServerInfo::class.java)
        ) { _, method, args ->
            if (method.name == "ping") {
                @Suppress("UNCHECKED_CAST")
                onPing(args[0] as Callback<ServerPing>)
            }
            null
        } as ServerInfo
    }
}
