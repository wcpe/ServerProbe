package top.wcpe.mc.plugin.serverprobe.core.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig

/**
 * [ConfigTier] 单测(FR-43):66 键定档表与物理下限校验。
 *
 * 守的是协议规格 `probe-managed-mode/spec.md` §7.1 的权威表:数量、分档、逐键一致。
 * 其中"与 [ProbeConfig] 实际键常量逐个对齐"的断言是防漂移闸门——新加配置键而不定档时这里会红。
 */
class ConfigTierTest {

    private companion object {

        /** 桥的线路调优键前缀:这些键由 bootstrap 与线路治理冻结,不参与托管 66 键数量契约。 */
        const val BRIDGE_TUNING_PREFIX = "bridge."
    }

    /** 定档表总量与分档数量必须与规格 §7.1 完全一致。 */
    @Test
    fun `66 键全覆盖且三档数量与规格一致`() {
        assertEquals(66, ConfigTier.keys().size, "定档表应为 66 键")
        assertEquals(18, ConfigTier.keysOf(ConfigTier.FORBIDDEN).size, "FORBIDDEN 应为 18 键")
        assertEquals(3, ConfigTier.keysOf(ConfigTier.SAFE).size, "SAFE 应为 3 键")
        assertEquals(45, ConfigTier.keysOf(ConfigTier.GUARDED).size, "GUARDED 应为 45 键")
        // 三档互斥且并集等于全集:任何"同键落两档"都会让并集变小
        val union = ConfigTier.keysOf(ConfigTier.FORBIDDEN) +
            ConfigTier.keysOf(ConfigTier.SAFE) +
            ConfigTier.keysOf(ConfigTier.GUARDED)
        assertEquals(66, union.size, "三档不得重叠")
    }

    /**
     * 定档表必须与 [ProbeConfig] 的键常量逐个对齐:既不漏(新键未定档),也不多(定档了不存在的键)。
     *
     * 两个"预"档表(`metrics-push-interval-ms` / `config-state-interval-ms`)按协议规格 §7.1 的说明
     * **保留在 66 键表外**,故对齐前先剔除;但它们必须仍各自落在 GUARDED / SAFE 档,且不得混进 66 键表
     * ——否则数量契约就失效了。
     */
    @Test
    fun `定档表与 ProbeConfig 实际键常量完全一致`() {
        val declared = declaredKeyNames()
        val reservedGuarded = ConfigTier.reservedKeys()
        val reservedSafe = ConfigTier.reservedSafeKeys()
        assertEquals(
            66,
            declared.size - reservedGuarded.size - reservedSafe.size,
            "除预定档键外,ProbeConfig 的 KEY_ 常量应为 66 个",
        )
        assertEquals(declared - reservedGuarded - reservedSafe, ConfigTier.keys(), "定档表与 ProbeConfig 键常量必须完全一致")
        reservedGuarded.forEach { key ->
            assertEquals(ConfigTier.GUARDED, ConfigTier.tierOf(key), "预定档键 $key 应按 GUARDED 校验")
            assertTrue(key !in ConfigTier.keys(), "预定档键 $key 不得混入 66 键定档表")
        }
        reservedSafe.forEach { key ->
            assertEquals(ConfigTier.SAFE, ConfigTier.tierOf(key), "预 SAFE 档键 $key 应按 SAFE 校验")
            assertTrue(key !in ConfigTier.keys(), "预 SAFE 档键 $key 不得混入 66 键定档表")
        }
    }

    /** 分档真源是规格:抽查每档的代表键(含监听面、凭证、展示类、节奏类)。 */
    @Test
    fun `代表键的档位与规格一致`() {
        assertEquals(ConfigTier.FORBIDDEN, ConfigTier.tierOf("bridge.token"))
        assertEquals(ConfigTier.FORBIDDEN, ConfigTier.tierOf("metrics.allowed-ips"))
        assertEquals(ConfigTier.FORBIDDEN, ConfigTier.tierOf("mcp.secret"))
        assertEquals(ConfigTier.FORBIDDEN, ConfigTier.tierOf("web.port"))
        assertEquals(ConfigTier.SAFE, ConfigTier.tierOf("startup-top-n"))
        assertEquals(ConfigTier.SAFE, ConfigTier.tierOf("server-name"))
        assertEquals(ConfigTier.SAFE, ConfigTier.tierOf("debug"))
        assertEquals(ConfigTier.GUARDED, ConfigTier.tierOf("collect-period-ticks"))
        assertEquals(ConfigTier.GUARDED, ConfigTier.tierOf("alert.rules"))
        assertEquals(ConfigTier.GUARDED, ConfigTier.tierOf("network-forensics.payload-channels"))
    }

    /** 未定档的键必须"失败关闭":tierOf 兜底 FORBIDDEN,tierOfOrNull 明确给出 null。 */
    @Test
    fun `未定档的键按禁止档兜底`() {
        assertNull(ConfigTier.tierOfOrNull("some-new-key"), "未定档键应无档位")
        assertEquals(ConfigTier.FORBIDDEN, ConfigTier.tierOf("some-new-key"), "未定档键必须兜底为禁止档")
    }

    /** 禁止路径取并集:bridge 整组(含更深子孙路径)一律禁止,普通键不得误判。 */
    @Test
    fun `bridge 整组与更深路径均判为禁止`() {
        assertTrue(ConfigTier.isForbiddenPath("bridge.enabled"))
        assertTrue(ConfigTier.isForbiddenPath("bridge.url"))
        assertTrue(ConfigTier.isForbiddenPath("bridge.url.extra"), "深层路径经前缀应命中")
        assertTrue(ConfigTier.isForbiddenPath("bridge"), "根级 bridge 整组禁止")
        assertTrue(ConfigTier.isForbiddenPath("metrics.port"))
        assertTrue(!ConfigTier.isForbiddenPath("aggregation.window"))
        assertTrue(!ConfigTier.isForbiddenPath("alert.rules.tps-warn.threshold"), "告警规则子路径不受禁止")
    }

    /** 禁止远程档:出现即拒绝,原因必须是中文且点明键名。 */
    @Test
    fun `禁止档键下发被拒且原因可读`() {
        val reason = ConfigTier.validate("bridge.url", "ws://evil")
        assertNotNull(reason)
        assertTrue(reason!!.contains("bridge.url"), "拒绝原因应包含键名,实际:$reason")
        assertTrue(reason.contains("禁止远程档"), "拒绝原因应点明档位,实际:$reason")
    }

    /** 未知键下发被拒。 */
    @Test
    fun `未定档键下发被拒`() {
        val reason = ConfigTier.validate("brand-new-key", 1)
        assertNotNull(reason)
        assertTrue(reason!!.contains("定档表未收录"), "实际:$reason")
    }

    /** 空值下发被拒(不支持经下发删除键)。 */
    @Test
    fun `空值下发被拒`() {
        val reason = ConfigTier.validate("collect-period-ticks", null)
        assertNotNull(reason)
        assertTrue(reason!!.contains("不支持经下发删除键"), "实际:$reason")
    }

    /** 采集节奏类三个键的物理下限(>= 20 ticks)。 */
    @Test
    fun `采样周期低于 20 被拒`() {
        listOf("collect-period-ticks", "world.sample-period-ticks", "cpu.sample-period-ticks").forEach { key ->
            val reason = ConfigTier.validate(key, 19)
            assertNotNull(reason, "$key=19 应被拒")
            assertTrue(reason!!.contains("不得小于 20"), "$key 原因应写明下限,实际:$reason")
            assertNull(ConfigTier.validate(key, 20), "$key=20 应通过")
        }
    }

    /** 历史缓冲容量上限(<= 20000)。 */
    @Test
    fun `历史容量超过 20000 被拒`() {
        val reason = ConfigTier.validate("history-capacity", 20_001)
        assertNotNull(reason)
        assertTrue(reason!!.contains("不得大于 20000"), "实际:$reason")
        assertNull(ConfigTier.validate("history-capacity", 20_000))
    }

    /** 推送间隔下限(>= 1000ms,该键保留在 66 键表外但已接入配置面)。 */
    @Test
    fun `推送间隔低于 1000 被拒`() {
        val reason = ConfigTier.validate("metrics-push-interval-ms", 999)
        assertNotNull(reason)
        assertTrue(reason!!.contains("不得小于 1000"), "实际:$reason")
        assertNull(ConfigTier.validate("metrics-push-interval-ms", 1_000))
    }

    /**
     * 托管状态上报周期(FR-45)按 **SAFE** 档校验：只校验类型，不做边界判定。
     *
     * 下限由读取侧 `ProbeConfig.configStateIntervalMs()` 兜底（协议规格 §3.4.3 只说"可经 SAFE 档键调整"，
     * 未给出协议级下限）；此处锁住"类型错被拒、值不设界"这一档位语义，防后续被误改成 GUARDED。
     */
    @Test
    fun `托管状态上报周期按 SAFE 档只校验类型`() {
        assertEquals(ConfigTier.SAFE, ConfigTier.tierOf("config-state-interval-ms"))
        val reason = ConfigTier.validate("config-state-interval-ms", "经常")
        assertNotNull(reason)
        assertTrue(reason!!.contains("需要 整数值"), "实际:$reason")
        assertNull(ConfigTier.validate("config-state-interval-ms", 30_000))
    }

    /** 跨键约束:聚合窗口不得超过历史缓冲容量(取"合并后的生效值")。 */
    @Test
    fun `聚合窗口超过历史容量被拒`() {
        val effective = { key: String -> if (key == "history-capacity") 360 else null }
        val reason = ConfigTier.validate("aggregation.window", 361, effective)
        assertNotNull(reason)
        assertTrue(reason!!.contains("history-capacity"), "实际:$reason")
        assertNull(ConfigTier.validate("aggregation.window", 360, effective))
        // 无上下文时跳过跨键约束,不得误拒
        assertNull(ConfigTier.validate("aggregation.window", 5_000))
    }

    /** 危险档的数值类键只接受整数。 */
    @Test
    fun `危险档数值键拒绝非整数`() {
        val reason = ConfigTier.validate("collect-period-ticks", "一段文字")
        assertNotNull(reason)
        assertTrue(reason!!.contains("需要整数值"), "实际:$reason")
        assertNull(ConfigTier.validate("collect-period-ticks", 100L), "长整型应可接受")
    }

    /** 安全档校验类型:debug 只接受布尔、server-name 只接受字符串。 */
    @Test
    fun `安全档校验类型`() {
        assertNull(ConfigTier.validate("debug", true))
        val debugReason = ConfigTier.validate("debug", "yes")
        assertNotNull(debugReason)
        assertTrue(debugReason!!.contains("需要 布尔值"), "实际:$debugReason")
        assertNull(ConfigTier.validate("server-name", "生存服"))
        assertNotNull(ConfigTier.validate("server-name", 123))
        assertNull(ConfigTier.validate("startup-top-n", 5))
        assertNotNull(ConfigTier.validate("startup-top-n", 5.5))
    }

    /**
     * 反射取出 [ProbeConfig] 内全部 `KEY_*` 字符串常量。
     *
     * `private const val` 在 JVM 上是静态字段,故可直接读到值;以此把"定档表"和"实际用到的键"锁在一起。
     *
     * ## 剔除桥的线路调优键
     * `bridge.reconnect.*` / `bridge.reload.*` / `bridge.subscribe.*` 等描述**连接与队列治理**,
     * 不属于托管 66 键面（连接参数与线路容量由 bootstrap 段与协议规格冻结决定），且
     * [ConfigTier.isForbiddenPath] 已按 `bridge` 前缀整组拒绝。它们不参与 66 键数量契约,
     * 否则每加一个线路调优键就要改协议规格表。其余新增键仍会被本闸门拦下。
     */
    private fun declaredKeyNames(): Set<String> = ProbeConfig::class.java.declaredFields
        .filter { it.name.startsWith("KEY_") }
        .mapNotNull { field ->
            field.isAccessible = true
            field.get(null) as? String
        }
        .toSet()
        .filterNot { key -> key.startsWith(BRIDGE_TUNING_PREFIX) && key !in ConfigTier.keys() }
        .toSet()
}
