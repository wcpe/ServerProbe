package top.wcpe.mc.plugin.serverprobe.core.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import taboolib.module.configuration.Configuration
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneIdentity
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneRuntime
import top.wcpe.mc.plugin.serverprobe.core.control.ProbeMode
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * [ManagedConfigResolver] 单测(FR-31):托管取值优先级、禁止档回落、bridge 身份文件取值。
 *
 * 配置源用动态代理替身(单测 classpath 无配置后端,无法构造真实 [Configuration]),只实现本用例用到的
 * 取值方法;托管缓存与运行模式走真实实现,故断言的正是生产取值链路。
 */
class ManagedConfigResolverTest {

    /** 配置替身:`key` → 值;也记录被问到的方法名与键,便于断言"取的是哪一层"。 */
    private var yaml = RecordingConfiguration(mapOf("collect-period-ticks" to 100L, "metrics.port" to 9940))

    @BeforeEach
    fun setUp() {
        ProbeConfig.conf = yaml.proxy
        ManagedConfigStore.clear()
        ControlPlaneRuntime.reset()
    }

    @AfterEach
    fun tearDown() {
        ManagedConfigStore.clear()
        ControlPlaneRuntime.reset()
    }

    /** 独立模式:缓存里的值必须被无视,只读 config.yml。 */
    @Test
    fun `独立模式忽略托管缓存`() {
        ManagedConfigStore.replace(mapOf("collect-period-ticks" to 20L))

        assertEquals(100L, ManagedConfigResolver.getLong("collect-period-ticks", 100L))
        assertFalse(ManagedConfigResolver.isManaged())
    }

    /** 托管模式:当前生效值优先于 config.yml。 */
    @Test
    fun `托管模式当前生效值优先于 config_yml`() {
        enterManaged()
        ManagedConfigStore.replace(mapOf("collect-period-ticks" to 40L))

        assertEquals(40L, ManagedConfigResolver.getLong("collect-period-ticks", 100L))
    }

    /** 托管模式:当前一代缺该键时,回落到上一次的值(而非直接掉到 config.yml)。 */
    @Test
    fun `托管模式当前值缺失时回落到上一次的值`() {
        enterManaged()
        ManagedConfigStore.replace(mapOf("collect-period-ticks" to 40L))
        ManagedConfigStore.replace(mapOf("history-capacity" to 500))

        assertEquals(40L, ManagedConfigResolver.getLong("collect-period-ticks", 100L), "应取上一次下发的值")
        assertEquals(500, ManagedConfigResolver.getInt("history-capacity", 360))
    }

    /** 托管模式:两代都没有该键时才落到 config.yml,再落代码默认值。 */
    @Test
    fun `托管模式无缓存时落到 config_yml 与代码默认值`() {
        enterManaged()

        assertEquals(100L, ManagedConfigResolver.getLong("collect-period-ticks", 7L), "config.yml 有值则用 config.yml")
        assertEquals(-1L, ManagedConfigResolver.getLong("debug", -1L), "config.yml 与缓存都无值时用默认值")
    }

    /** 托管模式下禁止档键一律回落 config.yml:缓存里即使有(异常载荷)也不得生效。 */
    @Test
    fun `托管模式禁止档键回落 config_yml`() {
        enterManaged()
        ManagedConfigStore.replace(mapOf("metrics.port" to 1, "bridge.url" to "ws://evil"))

        assertEquals(9940, ManagedConfigResolver.getInt("metrics.port", 9090), "禁止档键不得从缓存取值")
        assertEquals(
            "ws://cp.example/agent",
            ManagedConfigResolver.bridgeUrl("bridge.url", ""),
            "bridge 只认身份文件,不认缓存",
        )
    }

    /** 托管模式 bridge 四键取自身份文件(网关地址/实例/凭证/开关)。 */
    @Test
    fun `托管模式 bridge 取自身份文件`() {
        enterManaged()

        assertTrue(ManagedConfigResolver.bridgeEnabled("bridge.enabled", false), "身份存在即该连控制面")
        assertEquals("ws://cp.example/agent", ManagedConfigResolver.bridgeUrl("bridge.url", "ws://cfg"))
        assertEquals("instance-42", ManagedConfigResolver.bridgeInstance("bridge.instance", "cfg-instance"))
        assertEquals("opaque-credential", ManagedConfigResolver.bridgeToken("bridge.token", "cfg-token"))
    }

    /** 托管模式但身份缺失(异常兜底):bridge 四键退回 config.yml 路径。 */
    @Test
    fun `托管模式无身份时 bridge 回落 config_yml`() {
        ControlPlaneRuntime.publish(ProbeMode.MANAGED, null)

        assertFalse(ManagedConfigResolver.bridgeEnabled("bridge.enabled", false))
        assertEquals("cfg", ManagedConfigResolver.bridgeUrl("bridge.url", "cfg"), "config.yml 无该键时用默认值")
        assertEquals("cfg", ManagedConfigResolver.bridgeInstance("bridge.instance", "cfg"))
        assertEquals("cfg", ManagedConfigResolver.bridgeToken("bridge.token", "cfg"))
    }

    /** 缓存里的值类型不符时视为未命中,继续回落,绝不抛异常。 */
    @Test
    fun `缓存值类型不符时回落下一层`() {
        enterManaged()
        ManagedConfigStore.replace(mapOf("collect-period-ticks" to "不是数字"))

        assertEquals(100L, ManagedConfigResolver.getLong("collect-period-ticks", 55L))
        assertEquals(55L, ManagedConfigResolver.getLong("debug", 55L))
    }

    /** 数值型缓存可被浮点/字符串形态承载(JSON 数字解析差异)。 */
    @Test
    fun `缓存值接受整数值的浮点与字符串`() {
        enterManaged()
        ManagedConfigStore.replace(mapOf("collect-period-ticks" to 40.0, "history-capacity" to "500"))

        assertEquals(40L, ManagedConfigResolver.getLong("collect-period-ticks", 100L))
        assertEquals(500, ManagedConfigResolver.getInt("history-capacity", 360))
    }

    /** 整型越界不做静默截断:视为未命中,回落到下一层。 */
    @Test
    fun `整型越界回落下一层`() {
        enterManaged()
        ManagedConfigStore.replace(mapOf("history-capacity" to 5_000_000_000L))

        assertEquals(360, ManagedConfigResolver.getInt("history-capacity", 360), "越界值不得截断成 int")
        assertEquals(5_000_000_000L, ManagedConfigResolver.getLong("history-capacity", 0L), "long 读取仍可用")
    }

    /** 布尔/字符串/列表各自的取值与回退。 */
    @Test
    fun `布尔字符串列表取值`() {
        enterManaged()
        ManagedConfigStore.replace(
            mapOf(
                "alert.enabled" to true,
                "server-name" to "托管名",
                "network-forensics.payload-channels" to listOf("a", "b"),
            ),
        )

        assertTrue(ManagedConfigResolver.getBoolean("alert.enabled", false))
        assertEquals("托管名", ManagedConfigResolver.getString("server-name"))
        assertEquals(listOf("a", "b"), ManagedConfigResolver.getStringList("network-forensics.payload-channels"))
        assertNull(ManagedConfigResolver.getString("debug"), "未配置的字符串键保持 null")
        assertEquals(emptyList<String>(), ManagedConfigResolver.getStringList("alert.rules"))
    }

    /** 嵌套形态(如 alert.rules 下的子键)也要能从托管面取到。 */
    @Test
    fun `嵌套缓存支持点分路径`() {
        enterManaged()
        ManagedConfigStore.replace(
            mapOf("alert.rules" to mapOf("tps-warn" to mapOf("threshold" to 12.5))),
        )

        assertEquals(12.5, ManagedConfigResolver.getDouble("alert.rules.tps-warn.threshold", 18.0), 1e-9)
        assertEquals(3.0, ManagedConfigResolver.getDouble("alert.rules.tps-warn.sustain-cycles", 3.0), 1e-9)
    }

    /** 热替换:内容相同返回 false,变化返回 true,并把旧值转为"上一次"。 */
    @Test
    fun `热替换返回是否变化`() {
        assertTrue(ManagedConfigResolver.replace(mapOf("debug" to true)))
        assertFalse(ManagedConfigResolver.replace(mapOf("debug" to true)), "内容未变")
        assertTrue(ManagedConfigResolver.replace(mapOf("debug" to false)))
        assertEquals(mapOf<String, Any?>("debug" to true), ManagedConfigStore.previousValues())
        assertEquals(mapOf<String, Any?>("debug" to false), ManagedConfigStore.currentValues())
    }

    /** 校验入口带"当前生效值"上下文:跨键约束能用到 config.yml 里的 history-capacity。 */
    @Test
    fun `校验入口带当前生效值上下文`() {
        yaml = RecordingConfiguration(mapOf("history-capacity" to 100))
        ProbeConfig.conf = yaml.proxy

        val reason = ManagedConfigResolver.validate("aggregation.window", 101)
        assertNotNull(reason)
        assertTrue(reason!!.contains("history-capacity"), "实际:$reason")
        assertNull(ManagedConfigResolver.validate("aggregation.window", 100))
        // 跨键约束优先看托管缓存里的当前值
        enterManaged()
        ManagedConfigStore.replace(mapOf("history-capacity" to 500))
        assertNull(ManagedConfigResolver.validate("aggregation.window", 200))
    }

    /** 进入托管模式(身份齐备)。 */
    private fun enterManaged() {
        ControlPlaneRuntime.publish(
            ProbeMode.MANAGED,
            ControlPlaneIdentity(
                schemaVersion = 1,
                provider = "any",
                endpoint = "ws://cp.example/agent",
                instanceId = "instance-42",
                credential = "opaque-credential",
                capabilities = emptyList(),
                createdAt = "2024-01-01T00:00:00Z",
            ),
        )
    }

    /**
     * 只回答取值方法的配置替身(`config.yml`)。
     *
     * @property values 键 → 值。
     */
    private class RecordingConfiguration(private val values: Map<String, Any?>) {

        /** 代理实例(实现 [Configuration] 接口)。 */
        val proxy: Configuration = Proxy.newProxyInstance(
            Configuration::class.java.classLoader,
            arrayOf(Configuration::class.java),
            InvocationHandler { _, method, args ->
                val key = args?.firstOrNull() as? String
                val fallback = args?.getOrNull(1)
                val value = values[key]
                when (method.name) {
                    "getLong" -> (value as? Number)?.toLong() ?: fallback
                    "getInt" -> (value as? Number)?.toInt() ?: fallback
                    "getBoolean" -> value as? Boolean ?: fallback
                    "getDouble" -> (value as? Number)?.toDouble() ?: fallback
                    "getString" -> value as? String
                    "getStringList" -> (value as? List<*>)?.map { it.toString() } ?: emptyList<String>()
                    "contains" -> values.containsKey(key)
                    "get" -> value
                    else -> fallback
                }
            },
        ) as Configuration
    }
}
