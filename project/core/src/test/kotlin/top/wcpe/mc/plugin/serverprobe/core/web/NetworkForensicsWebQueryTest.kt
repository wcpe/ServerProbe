package top.wcpe.mc.plugin.serverprobe.core.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.forensics.PacketDirection

/** 网络取证 Web 查询参数必须维持有界、可分页的读取约束。 */
class NetworkForensicsWebQueryTest {

    @Test
    fun `解析完整时间范围过滤器和游标`() {
        val query = NetworkForensicsWebQuery.parse(
            "sinceMs=100&untilMs=200&direction=EGRESS&packetType=PluginMessagePacket" +
                "&playerName=%E7%8E%A9%E5%AE%B6&ip=203.0.113.77&cursorCapturedAtMs=180&cursorId=9&limit=50"
        )

        val apiQuery = query.toApiQuery()
        assertEquals(100, apiQuery.sinceMs)
        assertEquals(200, apiQuery.untilMs)
        assertEquals(PacketDirection.EGRESS, apiQuery.direction)
        assertEquals("玩家", apiQuery.playerName)
        assertEquals(9, apiQuery.cursorId)
        assertEquals(50, apiQuery.limit)
    }

    @Test
    fun `缺少时间范围或页大小越界会被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            NetworkForensicsWebQuery.parse("untilMs=200")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkForensicsWebQuery.parse("sinceMs=100&untilMs=200&limit=101")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkForensicsWebQuery.parse("sinceMs=100&untilMs=200&cursorId=9")
        }
    }
}
