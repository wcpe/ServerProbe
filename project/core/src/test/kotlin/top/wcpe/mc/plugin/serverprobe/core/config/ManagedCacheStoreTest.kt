package top.wcpe.mc.plugin.serverprobe.core.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * [ManagedCacheStore] 单测（FR-32 的 `managed-cache.json`）。
 *
 * 锁定四件事：
 * - **原子写**：落盘后不留 `.tmp` 残留，内容可被原样读回；
 * - **重启可加载**：`loadInto` 把版本与生效值装回内存（fail-static 的实际兑现点）；
 * - **损坏降级不崩**：截断/非 JSON/schema 版本未知/缺字段一律视为"无缓存"，绝不抛；
 * - **与身份文件分离**：缓存只承载配置值，不掺身份字段（结构上就不在一起，这里只验证往返不丢值）。
 */
class ManagedCacheStoreTest {

    /** 临时目录（每个用例独立，互不干扰）。 */
    @TempDir
    lateinit var dir: Path

    private lateinit var cacheFile: Path

    @BeforeEach
    fun setUp() {
        cacheFile = dir.resolve(ManagedCacheStore.FILE_NAME)
        ManagedConfigStore.clear()
        ManagedConfigState.reset()
    }

    @AfterEach
    fun tearDown() {
        ManagedConfigStore.clear()
        ManagedConfigState.reset()
    }

    /** 写→读往返：版本、值、时间戳逐项还原。 */
    @Test
    fun `写入后可原样读回`() {
        ManagedCacheStore.write(cacheFile, snapshot(version = 7, values = linkedMapOf("history-capacity" to 360L, "debug" to true)))

        val loaded = requireNotNull(ManagedCacheStore.read(cacheFile)) { "缓存应可读回" }
        assertEquals(7L, loaded.configVersion)
        assertEquals(mapOf("history-capacity" to 360L, "debug" to true), loaded.values)
        assertEquals("2026-10-19T03:12:44Z", loaded.appliedAt)
        assertEquals(1_792_379_564L, loaded.appliedAtEpochSeconds)
    }

    /** 原子写：不留下 `.tmp` 中间文件（半写残留就是下次启动读到残缺配置的根源）。 */
    @Test
    fun `落盘后不留临时文件`() {
        ManagedCacheStore.write(cacheFile, snapshot(version = 1, values = mapOf("history-capacity" to 360L)))

        assertTrue(Files.isRegularFile(cacheFile), "目标文件应存在")
        val leftovers = Files.list(dir).use { stream -> stream.filter { it.fileName.toString().endsWith(".tmp") }.toArray() }
        assertEquals(0, leftovers.size, "不应残留 .tmp 文件")
    }

    /** 启动装载：版本与生效值都进内存，`config_state` 的 cacheAge 才有真实基线。 */
    @Test
    fun `启动装载把版本与生效值装回内存`() {
        ManagedCacheStore.write(cacheFile, snapshot(version = 5, values = mapOf("collect-period-ticks" to 100L)))

        assertTrue(ManagedCacheStore.loadInto(cacheFile))

        assertEquals(mapOf("collect-period-ticks" to 100L), ManagedConfigStore.currentValues())
        assertEquals(5L, ManagedConfigState.configVersion())
        assertEquals(1_792_379_564L, ManagedConfigState.lastAppliedAtSeconds())
        assertNull(ManagedConfigState.lastError())
    }

    /** 无缓存：装载返回 false，内存保持"从未下发"（不抛、不崩）。 */
    @Test
    fun `无缓存时装载返回 false`() {
        assertFalse(ManagedCacheStore.loadInto(cacheFile))
        assertTrue(ManagedConfigStore.currentValues().isEmpty())
        assertEquals(0L, ManagedConfigState.configVersion())
    }

    /** 截断的缓存（半写）：按无缓存处理，不崩。 */
    @Test
    fun `截断的缓存降级为无缓存`() {
        Files.write(cacheFile, "{\"schemaVersion\":1,\"configVersion\":3,\"values\":{\"hist".toByteArray(Charsets.UTF_8))

        assertNull(ManagedCacheStore.read(cacheFile))
        assertFalse(ManagedCacheStore.loadInto(cacheFile))
        assertEquals(0L, ManagedConfigState.configVersion(), "损坏缓存不得污染版本号")
    }

    /** 非 JSON 内容：同样降级为无缓存。 */
    @Test
    fun `非 JSON 缓存降级为无缓存`() {
        Files.write(cacheFile, "这不是 JSON".toByteArray(Charsets.UTF_8))

        assertNull(ManagedCacheStore.read(cacheFile))
    }

    /** schema 版本未知：拒绝加载，避免把未来格式当成当前格式误读。 */
    @Test
    fun `未知 schema 版本降级为无缓存`() {
        Files.write(
            cacheFile,
            "{\"schemaVersion\":99,\"configVersion\":3,\"values\":{},\"appliedAt\":\"2026-10-19T03:12:44Z\"}".toByteArray(Charsets.UTF_8),
        )

        assertNull(ManagedCacheStore.read(cacheFile))
    }

    /** 缺 configVersion：视为不可用，而不是当成 0 版（那会让陈旧判定整个失效）。 */
    @Test
    fun `缺少版本号降级为无缓存`() {
        Files.write(
            cacheFile,
            "{\"schemaVersion\":1,\"values\":{\"debug\":true},\"appliedAt\":\"2026-10-19T03:12:44Z\"}".toByteArray(Charsets.UTF_8),
        )

        assertNull(ManagedCacheStore.read(cacheFile))
    }

    /** 时间戳不可解析：仍加载配置与版本，只是 cacheAge 无基线（不因一个时间戳丢掉整份有效配置）。 */
    @Test
    fun `时间戳不可解析仍加载配置`() {
        ManagedCacheStore.write(
            cacheFile,
            ManagedCacheStore.Snapshot(3, mapOf("debug" to true), "不是时间", appliedAtEpochSeconds = null),
        )

        val loaded = requireNotNull(ManagedCacheStore.read(cacheFile)) { "缓存应可读回" }
        assertEquals(mapOf("debug" to true), loaded.values)
        assertNull(loaded.appliedAtEpochSeconds, "不可解析的时间应如实留空")
    }

    /** 覆盖写：后一次写必须整体替换前一次（不得残留旧键）。 */
    @Test
    fun `覆盖写整体替换旧内容`() {
        ManagedCacheStore.write(cacheFile, snapshot(version = 1, values = linkedMapOf("debug" to true, "history-capacity" to 360L)))
        ManagedCacheStore.write(cacheFile, snapshot(version = 2, values = mapOf("history-capacity" to 300L)))

        val loaded = requireNotNull(ManagedCacheStore.read(cacheFile)) { "缓存应可读回" }
        assertEquals(2L, loaded.configVersion)
        assertEquals(mapOf("history-capacity" to 300L), loaded.values, "旧键不得残留")
    }

    /** 构造一份缓存快照。 */
    private fun snapshot(version: Long, values: Map<String, Any?>): ManagedCacheStore.Snapshot = ManagedCacheStore.Snapshot(
        configVersion = version,
        values = values,
        appliedAt = "2026-10-19T03:12:44Z",
        appliedAtEpochSeconds = 1_792_379_564L,
    )
}
