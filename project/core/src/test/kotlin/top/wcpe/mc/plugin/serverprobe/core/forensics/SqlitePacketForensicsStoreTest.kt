package top.wcpe.mc.plugin.serverprobe.core.forensics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketQuery
import top.wcpe.mc.plugin.serverprobe.api.forensics.PacketDirection
import java.nio.file.Path

/** SQLite 取证队列、分页、保留与驱动降级测试。 */
class SqlitePacketForensicsStoreTest {

    /** 单写线程应按批次落库，并按游标返回不重复的降序页面。 */
    @Test
    fun `异步单写后可按游标分页查询`(@TempDir directory: Path) {
        val warnings = mutableListOf<String>()
        SqlitePacketForensicsStore(
            directory.resolve("packets.db"),
            batchSize = 2,
            nowMs = { 4_000 },
            onWarning = warnings::add,
        ).use { store ->
            store.enqueue(observation(1_000))
            store.enqueue(observation(2_000))
            store.enqueue(observation(3_000))
            assertTrue(store.awaitIdle(), "$warnings ${store.status().unavailableReason}")

            val first = store.query(NetworkPacketQuery.builder().sinceMs(0).untilMs(4_000).limit(2).build())
            assertEquals(listOf(3_000L, 2_000L), first.records.map { it.capturedAtMs })
            assertTrue(first.hasNextPage)

            val second = store.query(
                NetworkPacketQuery.builder()
                    .sinceMs(0)
                    .untilMs(4_000)
                    .limit(2)
                    .cursorCapturedAtMs(first.nextCursorCapturedAtMs)
                    .cursorId(first.nextCursorId)
                    .build()
            )
            assertEquals(listOf(1_000L), second.records.map { it.capturedAtMs })
            assertFalse(second.hasNextPage)
        }
    }

    /** 时间范围缺失、倒置或超过单页上限必须拒绝，避免事故查询扩大为全表扫描。 */
    @Test
    fun `查询强制时间范围并限制每页最多100条`(@TempDir directory: Path) {
        SqlitePacketForensicsStore(directory.resolve("packets.db")).use { store ->
            assertThrows(IllegalArgumentException::class.java) {
                store.query(NetworkPacketQuery.builder().untilMs(1_000).limit(1).build())
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.query(NetworkPacketQuery.builder().sinceMs(2_000).untilMs(1_000).limit(1).build())
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.query(NetworkPacketQuery.builder().sinceMs(0).untilMs(1_000).limit(101).build())
            }
        }
    }

    /** 超过保留天数的证据必须先于新记录清理。 */
    @Test
    fun `超过保留天数时按最早记录清理`(@TempDir directory: Path) {
        val now = 10_000_000_000L
        SqlitePacketForensicsStore(
            databasePath = directory.resolve("packets.db"),
            retentionDays = 60,
            nowMs = { now },
        ).use { store ->
            store.enqueue(observation(now - 61L * DAY_MILLIS))
            store.enqueue(observation(now))
            assertTrue(store.awaitIdle(), store.status().unavailableReason)

            val page = store.query(NetworkPacketQuery.builder().sinceMs(0).untilMs(now).limit(100).build())
            assertEquals(listOf(now), page.records.map { it.capturedAtMs })
        }
    }

    /** 数据库物理文件超过上限时必须持续删除最早记录，保留最新证据。 */
    @Test
    fun `数据库超过体积上限时删除最早记录`(@TempDir directory: Path) {
        SqlitePacketForensicsStore(
            databasePath = directory.resolve("packets.db"),
            maxDatabaseBytes = 512L * 1_024L,
            batchSize = 1,
            nowMs = { 10 },
        ).use { store ->
            repeat(10) { index -> store.enqueue(observation(index.toLong(), payloadSize = 64 * 1_024)) }
            assertTrue(store.awaitIdle(), store.status().unavailableReason)

            val page = store.query(NetworkPacketQuery.builder().sinceMs(0).untilMs(10).limit(100).build())
            assertTrue(page.records.isNotEmpty())
            assertEquals(9L, page.records.first().capturedAtMs)
            assertTrue(store.databaseBytes() <= 512L * 1_024L, "实际大小=${store.databaseBytes()}")
        }
    }

    /** 驱动不可用时不得阻断进程，取证状态必须明确降级且记录不可入队。 */
    @Test
    fun `驱动缺失时明确降级`(@TempDir directory: Path) {
        val warnings = mutableListOf<String>()
        SqlitePacketForensicsStore(
            databasePath = directory.resolve("packets.db"),
            driverLoader = { throw ClassNotFoundException("org.sqlite.JDBC") },
            onWarning = warnings::add,
        ).use { store ->
            assertFalse(store.status().available)
            assertFalse(store.enqueue(observation(1_000)))
            assertTrue(warnings.single().contains("SQLite JDBC 驱动"))
        }
    }

    private fun observation(capturedAtMs: Long, payloadSize: Int = 3): PacketForensicsObservation {
        val bytes = ByteArray(payloadSize) { 'a'.code.toByte() }
        val captured = PacketCapturePolicy(setOf("LoginPayload"), emptySet(), payloadSize)
            .capture("LoginPayload", null, bytes)
        return PacketForensicsObservation(
            capturedAtMs = capturedAtMs,
            direction = PacketDirection.INGRESS,
            playerUuid = "00000000-0000-0000-0000-000000000001",
            playerName = "Probe",
            ip = "203.0.113.7",
            packetType = "LoginPayload",
            channel = null,
            payload = captured,
        )
    }

    private companion object {
        private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
