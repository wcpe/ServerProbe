package top.wcpe.mc.plugin.serverprobe.core.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [BridgeSection] 文本解析单测（自动迁移的输入侧）。
 *
 * 覆盖：段识别、三要素读取（含引号与行尾注释）、三要素不齐的判定、段移除的幂等与其余内容保真
 * （迁移只搬走一个段，不得洗掉运维的注释与其它配置）。
 */
class BridgeSectionTest {

    /** 段存在性识别：只认顶层 `bridge:`。 */
    @Test
    fun `识别顶层 bridge 段`() {
        assertTrue(BridgeSection.exists(SAMPLE), "应识别顶层 bridge 段")
        assertFalse(BridgeSection.exists("# 注释\nother: 1\n"), "无段时应为 false")
        assertFalse(BridgeSection.exists("  bridge:\n    url: x\n"), "缩进的 bridge 不是顶层段")
        assertFalse(BridgeSection.exists("bridge-ish: 1\n"), "键名必须完整匹配")
    }

    /** 三要素读取：去引号、去行尾注释。 */
    @Test
    fun `读取三要素并处理引号与注释`() {
        val values = BridgeSection.read(SAMPLE)

        assertTrue(values != null, "应读到段")
        assertEquals("true", values!!.enabled)
        assertEquals("ws://127.0.0.1:9102/ws/plugin-bridge", values.url)
        assertEquals("e2e-fixture", values.instance)
        assertEquals("token-value", values.token)
        assertTrue(values.isComplete(), "三要素齐备")
    }

    /** 三要素任一缺失/空白即不可迁移（不猜、不兜底）。 */
    @Test
    fun `缺字段判定为不完整`() {
        val text = "bridge:\n  enabled: true\n  url: \"ws://h:1/ws\"\n  instance: \"\"\n  token: \"t\"\n"

        val values = BridgeSection.read(text)

        assertTrue(values != null, "段存在")
        assertFalse(values!!.isComplete(), "instance 为空应判为不完整")
    }

    /** `enabled` 非 true 视为未启用（默认模板与显式关闭的部署不迁移）。 */
    @Test
    fun `enabled 缺省或 false 视为未启用`() {
        assertFalse(BridgeSection.read("bridge:\n  url: \"u\"\n")!!.isEnabled(), "缺 enabled 视为未启用")
        assertFalse(BridgeSection.read("bridge:\n  enabled: false\n")!!.isEnabled(), "false 视为未启用")
        assertTrue(BridgeSection.read("bridge:\n  enabled: TRUE\n")!!.isEnabled(), "大小写不敏感")
    }

    /** 无段时读取返回 null、移除原样返回（迁移"无 bridge 段则跳过"）。 */
    @Test
    fun `无段时读取为空且移除不变`() {
        val text = "# 只有注释\nhistory-capacity: 360\n"

        assertNull(BridgeSection.read(text), "无段应返回 null")
        assertEquals(text, BridgeSection.remove(text), "无段时移除应为恒等操作")
    }

    /** 移除只搬走 bridge 段，其余配置与注释逐字保留。 */
    @Test
    fun `移除段并保留其余内容`() {
        val removed = BridgeSection.remove(SAMPLE)

        assertFalse(BridgeSection.exists(removed), "移除后不应再有 bridge 段")
        assertTrue(removed.contains("# 采集周期"), "其它配置的注释必须保留")
        assertTrue(removed.contains("collect-period-ticks: 100"), "其它配置项必须保留")
        assertFalse(removed.contains("token-value"), "段内容应被完整移除")
    }

    /** 移除幂等：再移除一次结果不变。 */
    @Test
    fun `移除幂等`() {
        val once = BridgeSection.remove(SAMPLE)
        val twice = BridgeSection.remove(once)

        assertEquals(once, twice, "重复移除结果应一致")
    }

    /** CRLF 文件移除后仍保留 CRLF（不擅自改写行尾）。 */
    @Test
    fun `移除不改变行尾风格`() {
        val crlf = "bridge:\r\n  enabled: true\r\n  url: \"u\"\r\n  instance: \"i\"\r\n  token: \"t\"\r\nother: 1\r\n"

        val removed = BridgeSection.remove(crlf)

        assertFalse(BridgeSection.exists(removed), "CRLF 文件同样应移除干净")
        assertTrue(removed.contains("other: 1\r\n"), "应保留 CRLF 行尾，实际:$removed")
    }

    /** 段的文本表示必须掩码 token（本对象可能被打进日志）。 */
    @Test
    fun `段取值文本表示掩码凭证`() {
        val values = BridgeSection.read(SAMPLE)!!

        val text = values.toString()

        assertFalse(text.contains("token-value"), "toString 不得包含凭证，实际:$text")
        assertTrue(text.contains("已隐藏"), "toString 应说明凭证已隐藏，实际:$text")
    }

    private companion object {

        /** 典型形态：与插件随包默认 `config.yml` 及控制面写入的形态一致。 */
        private const val SAMPLE = """# 探针配置
# 采集周期
collect-period-ticks: 100
bridge:
  # 是否开启插件桥
  enabled: true
  url: "ws://127.0.0.1:9102/ws/plugin-bridge"
  instance: "e2e-fixture"   # 实例标识
  token: "token-value"
history-capacity: 360
"""

        /**
         * 重复段形态：随包默认自带 `bridge:`（enabled: false），运维在文件末尾再追加一个启用的。
         * 这是线上真实踩过的形态——YAML 解析器后者胜出（桥连上了），但文本扫描若取第一个
         * 就会看到 `enabled: false` 而判定不可迁移。
         */
        private const val DUPLICATED = """collect-period-ticks: 100
bridge:
  enabled: false
  url: ""
  instance: ""
  token: ""
history-capacity: 360

bridge:
  enabled: true
  url: "ws://127.0.0.1:18522/ws/plugin-bridge"
  instance: "0dd7b03c"
  token: "real-token"
"""
    }

    @Test
    fun `重复 bridge 段取最后一个与 YAML 解析器一致`() {
        val values = requireNotNull(BridgeSection.read(DUPLICATED)) { "应读到末尾的 bridge 段" }
        assertTrue(values.isEnabled(), "应取末尾段的 enabled=true，实际:${values.enabled}")
        assertEquals("ws://127.0.0.1:18522/ws/plugin-bridge", values.url)
        assertEquals("0dd7b03c", values.instance)
        assertTrue(values.isComplete(), "末尾段三要素齐备，应可迁移")
    }

    @Test
    fun `移除重复 bridge 段时全部清除避免迁移复发`() {
        val removed = BridgeSection.remove(DUPLICATED)
        assertFalse(removed.contains("bridge:"), "所有 bridge 段都应被移除，实际:$removed")
        assertFalse(removed.contains("real-token"), "凭证不得残留，实际:$removed")
        assertTrue(removed.contains("collect-period-ticks: 100"), "其它配置须原样保留")
        assertTrue(removed.contains("history-capacity: 360"), "其它配置须原样保留")
    }
}
