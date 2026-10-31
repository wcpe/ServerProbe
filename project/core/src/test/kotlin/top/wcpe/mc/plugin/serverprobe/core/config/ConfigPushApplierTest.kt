package top.wcpe.mc.plugin.serverprobe.core.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [ConfigPushApplier] 单测：配置下发状态机的**每一条分支**逐条锁定。
 *
 * 覆盖协议规格 §3.5 的配置应用状态机：
 * - 版本陈旧 → `rejected(stale)`，且**不降级**（内存与版本号都不动）；
 * - `baseVersion` 不符 → `rejected(conflict)`；
 * - 含 FORBIDDEN 键（含 `bridge` 整组与未定档键）→ 整包拒绝；
 * - GUARDED 越界 → 拒绝，原因可读；
 * - 自检通过 → `applied`，`effective` 是内存里真实生效的值；
 * - **自检失败 → `failed` + 内存回退上一版 + 缓存文件未被写**（本条最关键）；
 * - `removals` 正确删键（含点分嵌套路径），且不会从上一代"复活"。
 *
 * 用真实的 [ManagedConfigStore] / [ManagedConfigState]，只注入自检与缓存落盘两个外部副作用，
 * 故断言的是生产链路的真实语义，而不是替身的行为。
 */
class ConfigPushApplierTest {

    /** 落盘的缓存快照（每次写盘记录一条，用来断言"失败时不写"）。 */
    private val writtenCaches = mutableListOf<ManagedCacheStore.Snapshot>()

    /** 可切换的自检结果。 */
    private var selfCheck: SelfCheckResult = pass()

    /** 应用器：自检与落盘都可控，时钟固定在 2026-10-19T03:12:44Z。 */
    private lateinit var applier: ConfigPushApplier

    @BeforeEach
    fun setUp() {
        ManagedConfigStore.clear()
        ManagedConfigState.reset()
        writtenCaches.clear()
        selfCheck = pass()
        applier = ConfigPushApplier(
            selfCheck = { selfCheck },
            writeCache = { snapshot -> writtenCaches += snapshot },
            clockMillis = { FIXED_EPOCH_MILLIS },
        )
    }

    @AfterEach
    fun tearDown() {
        ManagedConfigStore.clear()
        ManagedConfigState.reset()
    }

    // ── 版本与并发 ──

    /** 版本陈旧：`configVersion <= 当前版本` 一律 `rejected(stale)`，且**不降级**（那等于接受一次回滚）。 */
    @Test
    fun `版本不高时拒绝且不降级`() {
        applyVersion(configVersion = 3, baseVersion = 0, values = mapOf("history-capacity" to 360))
        assertEquals(3L, ManagedConfigState.configVersion())

        val same = applyVersion(configVersion = 3, baseVersion = 3, values = mapOf("history-capacity" to 200))
        assertEquals(ConfigPushOutcome.RESULT_REJECTED, same.result)
        assertTrue(same.error!!.startsWith("stale"), "原因应为 stale，实际:${same.error}")
        assertEquals(360, ManagedConfigStore.currentValues()["history-capacity"], "陈旧载荷不得覆盖当前值")
        assertEquals(3L, ManagedConfigState.configVersion(), "版本号不得被陈旧载荷推进")

        val older = applyVersion(configVersion = 2, baseVersion = 3, values = mapOf("history-capacity" to 100))
        assertEquals(ConfigPushOutcome.RESULT_REJECTED, older.result)
        assertTrue(older.error!!.startsWith("stale"), "实际:${older.error}")
        assertEquals(360, ManagedConfigStore.currentValues()["history-capacity"])
    }

    /** `baseVersion` 与当前版本不符：`rejected(conflict)`，内存不动。 */
    @Test
    fun `基线版本不符时拒绝为冲突`() {
        applyVersion(configVersion = 5, baseVersion = 0, values = mapOf("history-capacity" to 360))

        val conflict = applyVersion(configVersion = 6, baseVersion = 4, values = mapOf("history-capacity" to 100))
        assertEquals(ConfigPushOutcome.RESULT_REJECTED, conflict.result)
        assertTrue(conflict.error!!.startsWith("conflict"), "原因应为 conflict，实际:${conflict.error}")
        assertEquals(360, ManagedConfigStore.currentValues()["history-capacity"])
        assertEquals(5L, ManagedConfigState.configVersion())
    }

    /** 版本单调推进：每次成功应用后版本前进，后续更高版本才能继续。 */
    @Test
    fun `成功应用后版本单调推进`() {
        assertEquals(1L, applyVersion(1, 0, mapOf("history-capacity" to 360)).configVersion)
        assertEquals(2L, applyVersion(2, 1, mapOf("history-capacity" to 300)).configVersion)
        assertEquals(2L, ManagedConfigState.configVersion())
        assertEquals(300, ManagedConfigStore.currentValues()["history-capacity"])
    }

    // ── 档位校验 ──

    /** FORBIDDEN 键（`bridge` 整组）：整包拒绝，同批次的合法键也不得部分生效。 */
    @Test
    fun `含 bridge 键时整包拒绝`() {
        val outcome = applyVersion(
            configVersion = 1,
            baseVersion = 0,
            values = linkedMapOf("history-capacity" to 360, "bridge.url" to "ws://evil"),
        )

        assertEquals(ConfigPushOutcome.RESULT_REJECTED, outcome.result)
        assertTrue(outcome.error!!.startsWith("forbidden"), "原因应为 forbidden，实际:${outcome.error}")
        assertTrue(ManagedConfigStore.currentValues().isEmpty(), "整包拒绝时不得留下部分生效的键")
        assertEquals(0L, ManagedConfigState.configVersion())
        assertTrue(writtenCaches.isEmpty(), "被拒绝的载荷不得写缓存")
    }

    /** FORBIDDEN 组（`metrics` / `mcp` / `web` 下的键）同样整包拒绝。 */
    @Test
    fun `含监听面键时整包拒绝`() {
        listOf("metrics.port", "mcp.secret", "web.enabled").forEach { key ->
            val outcome = applyVersion(1, 0, linkedMapOf("history-capacity" to 360, key to 1))
            assertEquals(ConfigPushOutcome.RESULT_REJECTED, outcome.result, "$key 应被拒")
            assertTrue(outcome.error!!.startsWith("forbidden"), "$key 实际:${outcome.error}")
        }
        assertTrue(ManagedConfigStore.currentValues().isEmpty())
    }

    /** 未定档键按"失败关闭"处理：既不能静默放行，也不能存进内存。 */
    @Test
    fun `未定档键被拒`() {
        val outcome = applyVersion(1, 0, mapOf("brand-new-key" to 1))

        assertEquals(ConfigPushOutcome.RESULT_REJECTED, outcome.result)
        assertTrue(outcome.error!!.startsWith("forbidden"), "未定档键应按禁止档拒绝，实际:${outcome.error}")
        assertTrue(ManagedConfigStore.currentValues().isEmpty())
    }

    /** GUARDED 越界：拒绝且原因可读（带键名与下限）。 */
    @Test
    fun `危险档越界被拒且原因可读`() {
        val outcome = applyVersion(1, 0, mapOf("collect-period-ticks" to 19))

        assertEquals(ConfigPushOutcome.RESULT_REJECTED, outcome.result)
        assertTrue(outcome.error!!.startsWith("out_of_range"), "原因应为 out_of_range，实际:${outcome.error}")
        assertTrue(outcome.error.contains("collect-period-ticks"), "原因应点明键名，实际:${outcome.error}")
        assertTrue(outcome.error.contains("不得小于 20"), "原因应写明下限，实际:${outcome.error}")
        assertTrue(writtenCaches.isEmpty())
    }

    /** SAFE 键只校验类型；类型不符同样整包拒绝。 */
    @Test
    fun `安全档类型不符被拒`() {
        val outcome = applyVersion(1, 0, mapOf("debug" to "yes"))

        assertEquals(ConfigPushOutcome.RESULT_REJECTED, outcome.result)
        assertTrue(outcome.error!!.startsWith("out_of_range"), "实际:${outcome.error}")
    }

    /** 跨键约束看的是**合并后**的值：同一次下发里把两者一起调大不得误拒。 */
    @Test
    fun `跨键约束按合并后的值判定`() {
        applyVersion(1, 0, mapOf("history-capacity" to 360, "aggregation.window" to 300))

        val outcome = applyVersion(
            2,
            1,
            linkedMapOf("history-capacity" to 500, "aggregation.window" to 480),
        )

        assertEquals(ConfigPushOutcome.RESULT_APPLIED, outcome.result, "同批一起调大不应被误拒：${outcome.error}")
        assertEquals(480, ManagedConfigStore.currentValues()["aggregation.window"])
    }

    // ── 应用与自检 ──

    /** 自检通过：`applied`，`effective` 反映内存真实生效值，并原子写缓存。 */
    @Test
    fun `自检通过时生效并写缓存`() {
        val outcome = applyVersion(7, 0, linkedMapOf("collect-period-ticks" to 100, "history-capacity" to 360))

        assertEquals(ConfigPushOutcome.RESULT_APPLIED, outcome.result)
        assertNull(outcome.error)
        assertEquals("2026-10-19T03:12:44Z", outcome.appliedAt, "appliedAt 应为秒精度 UTC")
        assertEquals(mapOf("collect-period-ticks" to 100, "history-capacity" to 360), outcome.effective)
        assertEquals(outcome.effective, ManagedConfigStore.currentValues(), "effective 必须等于内存里的真实生效值")
        assertTrue(outcome.selfCheck?.passed == true, "applied 必须附带三项全通过的自检")

        assertEquals(1, writtenCaches.size, "生效后必须落盘一份缓存")
        assertEquals(7L, writtenCaches[0].configVersion)
        assertEquals(outcome.effective, writtenCaches[0].values)
        assertEquals("2026-10-19T03:12:44Z", writtenCaches[0].appliedAt)
        assertEquals(7L, ManagedConfigState.configVersion())
        assertEquals(FIXED_EPOCH_SECONDS, ManagedConfigState.lastAppliedAtSeconds())
        assertNull(ManagedConfigState.lastError())
    }

    /**
     * **自检失败 → `failed` + 内存回退到上一版 + 缓存文件未被污染**（本组最关键的一条）。
     *
     * 三者缺一都会造成"探针自认为生效、控制面也以为生效，实际行为却是坏的"。
     */
    @Test
    fun `自检失败时回退上一版且不写缓存`() {
        // 先有一版正常生效的值作为"上一版"
        applyVersion(1, 0, mapOf("history-capacity" to 360))
        writtenCaches.clear()

        // 第二版自检失败
        selfCheck = SelfCheckResult(collectorAlive = true, metricProducible = false, bridgeUsable = true)
        val outcome = applyVersion(2, 1, mapOf("history-capacity" to 200))

        assertEquals(ConfigPushOutcome.RESULT_FAILED, outcome.result)
        assertTrue(outcome.error!!.contains("取不到非空指标快照"), "原因应点明失败项，实际:${outcome.error}")
        assertEquals(
            mapOf("history-capacity" to 360),
            ManagedConfigStore.currentValues(),
            "内存必须回退上一版",
        )
        assertEquals(outcome.effective, ManagedConfigStore.currentValues(), "effective 必须是回退后的真实值")
        assertTrue(writtenCaches.isEmpty(), "自检失败**不得**写缓存（那是把坏配置固化成下次启动的真源）")
        assertEquals(1L, ManagedConfigState.configVersion(), "失败不得推进版本号")
        assertTrue(ManagedConfigState.lastError()!!.contains("自检失败"))
    }

    /** 回退必须把**两代都快照还原**，不能留下"current 是回退值、previous 是失败值"的错位。 */
    @Test
    fun `自检失败后两代快照整对还原`() {
        applyVersion(1, 0, mapOf("history-capacity" to 360))
        applyVersion(2, 1, mapOf("history-capacity" to 300))
        val before = ManagedConfigStore.generations()

        selfCheck = SelfCheckResult(collectorAlive = false, metricProducible = true, bridgeUsable = true)
        applyVersion(3, 2, mapOf("history-capacity" to 200))

        assertEquals(before.current, ManagedConfigStore.currentValues(), "current 应还原")
        assertEquals(before.previous, ManagedConfigStore.previousValues(), "previous 也应还原")
    }

    /** 自检本身抛异常：按失败处理并回退，绝不向调用方(桥读线程)抛。 */
    @Test
    fun `自检抛异常时按失败回退`() {
        applyVersion(1, 0, mapOf("history-capacity" to 360))
        writtenCaches.clear()
        val throwing = ConfigPushApplier(
            selfCheck = { throw IllegalStateException("采集器炸了") },
            writeCache = { snapshot -> writtenCaches += snapshot },
            clockMillis = { FIXED_EPOCH_MILLIS },
        )

        val outcome = throwing.apply(payload(2, 1, mapOf("history-capacity" to 200)))

        assertEquals(ConfigPushOutcome.RESULT_FAILED, outcome.result)
        assertEquals(360, ManagedConfigStore.currentValues()["history-capacity"])
        assertFalse(outcome.selfCheck!!.passed)
        assertTrue(writtenCaches.isEmpty())
    }

    /** 缓存落盘失败不影响内存生效：磁盘问题不该把一份可用配置撤掉，但原因要上报。 */
    @Test
    fun `缓存落盘失败仍报生效并带上原因`() {
        val failing = ConfigPushApplier(
            selfCheck = { pass() },
            writeCache = { throw java.io.IOException("磁盘只读") },
            clockMillis = { FIXED_EPOCH_MILLIS },
        )

        val outcome = failing.apply(payload(1, 0, mapOf("history-capacity" to 360)))

        assertEquals(ConfigPushOutcome.RESULT_APPLIED, outcome.result)
        assertTrue(outcome.error!!.contains("落盘失败"), "实际:${outcome.error}")
        assertEquals(360, ManagedConfigStore.currentValues()["history-capacity"], "内存仍然生效")
    }

    // ── removals ──

    /** `removals` 按点分路径删键：扁平键与嵌套子键都要删掉，且不得留下 null 值。 */
    @Test
    fun `removals 按点分路径删键`() {
        applyVersion(
            1,
            0,
            linkedMapOf(
                "history-capacity" to 360,
                "server-name" to "生存服",
                "alert.rules" to linkedMapOf("tps-warn" to linkedMapOf("threshold" to 18), "lag-warn" to 50),
            ),
        )

        val outcome = applyVersionWithRemovals(
            configVersion = 2,
            baseVersion = 1,
            values = emptyMap(),
            removals = listOf("server-name", "alert.rules.tps-warn"),
        )

        assertEquals(ConfigPushOutcome.RESULT_APPLIED, outcome.result, "实际:${outcome.error}")
        val effective = ManagedConfigStore.currentValues()
        assertFalse(effective.containsKey("server-name"), "扁平键应被删除")
        assertFalse(effective.containsValue(null), "不得留下 null 值")
        @Suppress("UNCHECKED_CAST")
        val rules = effective["alert.rules"] as Map<String, Any?>
        assertFalse(rules.containsKey("tps-warn"), "嵌套子键应被删除")
        assertTrue(rules.containsKey("lag-warn"), "未列入 removals 的兄弟键必须保留")
        assertEquals(360, effective["history-capacity"], "未提及的键保持不变")
        assertEquals(2L, ManagedConfigState.configVersion(), "删除是有效变更，版本应推进")
    }

    /** 删除必须作用到上一代：否则 `previous → current` 的回落会把刚删掉的键"复活"。 */
    @Test
    fun `被删除的键不会从上一代复活`() {
        applyVersion(1, 0, mapOf("server-name" to "生存服", "history-capacity" to 360))
        applyVersionWithRemovals(2, 1, emptyMap(), listOf("server-name"))

        assertFalse(ManagedConfigStore.currentValues().containsKey("server-name"))
        assertFalse(
            ManagedConfigStore.previousValues().containsKey("server-name"),
            "上一代也必须抹掉该键，否则读取侧会从上一代取回它",
        )
    }

    /** `removals` 里出现禁止远程档路径：整包拒绝（与 values 同一条规则）。 */
    @Test
    fun `removals 含禁止路径时整包拒绝`() {
        val outcome = applyVersionWithRemovals(1, 0, mapOf("history-capacity" to 360), listOf("bridge.token"))

        assertEquals(ConfigPushOutcome.RESULT_REJECTED, outcome.result)
        assertTrue(outcome.error!!.startsWith("forbidden"), "实际:${outcome.error}")
        assertTrue(ManagedConfigStore.currentValues().isEmpty(), "整包拒绝时同批 values 也不得生效")
    }

    // ── 辅助 ──

    /** 走一次完整应用（无 removals）。 */
    private fun applyVersion(configVersion: Long, baseVersion: Long, values: Map<String, Any?>): ConfigPushOutcome =
        applyVersionWithRemovals(configVersion, baseVersion, values, emptyList())

    /** 走一次完整应用（可带 removals）。 */
    private fun applyVersionWithRemovals(
        configVersion: Long,
        baseVersion: Long,
        values: Map<String, Any?>,
        removals: List<String>,
    ): ConfigPushOutcome = applier.apply(payload(configVersion, baseVersion, values, removals))

    /** 构造载荷。 */
    private fun payload(
        configVersion: Long,
        baseVersion: Long,
        values: Map<String, Any?>,
        removals: List<String> = emptyList(),
    ): ConfigPushPayload = ConfigPushPayload(
        configVersion = configVersion,
        baseVersion = baseVersion,
        values = values,
        removals = removals,
        reason = "单测",
        operator = "tester",
    )

    private companion object {

        /** 固定时钟:2026-10-19T03:12:44Z(与协议规格 §3.4.2 的示例时刻一致)。 */
        private const val FIXED_EPOCH_MILLIS = 1_792_379_564_000L

        /** 同一时刻的 epoch 秒。 */
        private const val FIXED_EPOCH_SECONDS = 1_792_379_564L

        /** 三项全通过的自检。 */
        private fun pass(): SelfCheckResult = SelfCheckResult(collectorAlive = true, metricProducible = true, bridgeUsable = true)
    }
}
