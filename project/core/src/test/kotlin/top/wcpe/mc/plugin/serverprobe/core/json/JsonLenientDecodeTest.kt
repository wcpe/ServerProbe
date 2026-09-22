package top.wcpe.mc.plugin.serverprobe.core.json

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile

/**
 * [Json.decodeLenient] 的字段定位逻辑单测（issue #46）。
 *
 * 真正的一次性绑定依赖 TabooLib 运行期配置后端，裸单测起不来（与 `LocalFileMetricStore` 同款限制），
 * 故此处锁"哪些字段需要归一化"这个可测且易错的部分：只认**装箱 `Long`**，基本类型与其它装箱类型不碰——
 * 若哪天有人把基本类型也算进去，本用例转红。
 */
class JsonLenientDecodeTest {

    /** 启动画像里需要归一化的字段恰为两个装箱 Long（真机报错的 sampleIntervalMs 与同类的 premainNanos）。 */
    @Test
    fun `启动画像的装箱 Long 字段被精确定位`() {
        val names = Json.boxedLongFieldNames(StartupProfile::class.java)

        assertEquals(setOf("premainNanos", "sampleIntervalMs"), names.toSet(), "新增装箱 Long 字段时需一并确认宽类型解码覆盖")
    }

    /** 标量全是基本类型的模型不该被归一化——否则会把本来能绑的 int/long 字段弄坏。 */
    @Test
    fun `基本类型标量不被纳入归一化`() {
        val names = Json.boxedLongFieldNames(MetricSnapshot::class.java)

        assertTrue(names.isEmpty(), "MetricSnapshot 无装箱 Long 字段，实际=$names")
    }
}
