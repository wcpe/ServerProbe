package top.wcpe.mc.plugin.serverprobe.command

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * `/probe` 命令数值格式化的单测（呈现辅助）。
 *
 * ## 为什么这块值得测
 *
 * 这些函数直接把「字节 / 毫秒 / 比例」翻成服主看到的读数。它们看起来是琐碎的单位换算，
 * 但错一位就会让人对服务器状态判断失误：把 1 GB 显示成 1024 MB、把 42.3% 显示成 4.2%、
 * 把 1 天 12 小时显示成 36 小时——都不是崩溃，只是让人读错。
 *
 * ## 最要紧的一条：区域无关
 *
 * `String.format` 不传 Locale 时会用 JVM 默认区域。德语/法语等区域的小数点是逗号，
 * 于是 `512,00 MB` 会被误读成两个数，同一台服在不同区域设置下输出也不一致。
 * 因此本文件含一个**显式把默认区域切到德国**的用例：若哪天有人把这些
 * `Locale.ROOT` 参数删掉，它会立刻变红。
 *
 * 注意：区域相关的用例会改全局默认值，必须 [AfterEach] 还原，否则会污染同 JVM 的其它测试。
 */
@DisplayName("ProbeFormat 数值格式化")
class ProbeFormatTest {

    /** 区域用例会改 JVM 全局默认值，先存后用后还原。 */
    private lateinit var originalLocale: Locale

    @BeforeEach
    fun rememberLocale() {
        originalLocale = Locale.getDefault()
    }

    @AfterEach
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    // ==================== bytes ====================

    @Test
    @DisplayName("负数字节原样返回：约定表示「无上限/无数据」，不在格式化层臆断语义")
    fun negativeBytesReturnRawValue() {
        assertEquals("-1", ProbeFormat.bytes(-1))
        assertEquals("-9223372036854775808", ProbeFormat.bytes(Long.MIN_VALUE))
    }

    @Test
    @DisplayName("小于 1024 字节用 B 且不带小数")
    fun subKilobyteUsesBytesWithoutDecimals() {
        assertEquals("0 B", ProbeFormat.bytes(0))
        assertEquals("1023 B", ProbeFormat.bytes(1023))
    }

    @Test
    @DisplayName("按 1024 进制逐级升单位，保留两位小数")
    fun scalesThroughUnits() {
        assertEquals("1.00 KB", ProbeFormat.bytes(1024))
        assertEquals("1.50 KB", ProbeFormat.bytes(1536))
        assertEquals("1.00 MB", ProbeFormat.bytes(1024L * 1024))
        assertEquals("1.00 GB", ProbeFormat.bytes(1024L * 1024 * 1024))
        assertEquals("1.00 TB", ProbeFormat.bytes(1024L * 1024 * 1024 * 1024))
    }

    @Test
    @DisplayName("超出 TB 时停在 TB，不会越界访问单位表")
    fun saturatesAtTerabytes() {
        // Long.MAX_VALUE 约 8.39e6 TB；关键是它不抛 IndexOutOfBounds
        assertEquals("8388608.00 TB", ProbeFormat.bytes(Long.MAX_VALUE))
    }

    // ==================== millis / seconds ====================

    @Test
    @DisplayName("毫秒原样加 ms 后缀")
    fun millisAppendsSuffix() {
        assertEquals("0ms", ProbeFormat.millis(0))
        assertEquals("1234ms", ProbeFormat.millis(1234))
    }

    @Test
    @DisplayName("秒保留一位小数（截断而非四舍五入到整数）")
    fun secondsKeepsOneDecimal() {
        assertEquals("0.0s", ProbeFormat.seconds(0))
        assertEquals("12.3s", ProbeFormat.seconds(12_345))
        assertEquals("1.0s", ProbeFormat.seconds(1000))
    }

    // ==================== duration ====================

    @Test
    @DisplayName("全为 0 时输出 0m，保证始终有可读文本")
    fun zeroDurationRendersZeroMinutes() {
        assertEquals("0m", ProbeFormat.duration(0))
        // 不足一分钟同样落到 0m
        assertEquals("0m", ProbeFormat.duration(59_000))
    }

    @Test
    @DisplayName("自动省略为 0 的高位单位")
    fun omitsLeadingZeroUnits() {
        assertEquals("1m", ProbeFormat.duration(60_000))
        assertEquals("1h 0m", ProbeFormat.duration(3_600_000))
        assertEquals("1d 0h 0m", ProbeFormat.duration(86_400_000))
    }

    @Test
    @DisplayName("天/时/分同时非零时全部呈现")
    fun rendersAllUnits() {
        // 1 天 12 小时 0 分
        assertEquals("1d 12h 0m", ProbeFormat.duration(129_600_000))
        // 2 天 3 小时 4 分
        assertEquals("2d 3h 4m", ProbeFormat.duration(((2L * 24 + 3) * 60 + 4) * 60_000))
    }

    // ==================== percentOrNull ====================

    @Test
    @DisplayName("负比例视为不可用，返回 null 交给 i18n 显示 N/A")
    fun negativeRatioIsUnavailable() {
        assertNull(ProbeFormat.percentOrNull(-0.1))
        assertNull(ProbeFormat.percentOrNull(-1.0))
    }

    @Test
    @DisplayName("比例按 0–1 换算成百分比并保留一位小数")
    fun ratioBecomesPercent() {
        assertEquals("0.0%", ProbeFormat.percentOrNull(0.0))
        assertEquals("42.3%", ProbeFormat.percentOrNull(0.423))
        assertEquals("100.0%", ProbeFormat.percentOrNull(1.0))
    }

    // ==================== tpsOrNull / msptOrNull ====================

    @Test
    @DisplayName("TPS/MSPT 为 null 时返回 null（表示 N/A），有值时保留两位小数")
    fun nullableMetrics() {
        assertNull(ProbeFormat.tpsOrNull(null))
        assertNull(ProbeFormat.msptOrNull(null))

        assertEquals("19.98", ProbeFormat.tpsOrNull(19.98))
        assertEquals("20.00", ProbeFormat.tpsOrNull(20.0))
        assertEquals("3.21ms", ProbeFormat.msptOrNull(3.214))
    }

    // ==================== 区域无关（核心回归） ====================

    @Test
    @DisplayName("默认区域是德语（小数点为逗号）时，输出仍用点号——不得随 JVM 区域变形")
    fun outputIsLocaleIndependent() {
        Locale.setDefault(Locale.GERMANY)

        // 德语区域下不传 Locale 的 %.2f 会产出 "1,00"；钉死 ROOT 后必须是 "1.00"
        assertEquals("1.00 KB", ProbeFormat.bytes(1024))
        assertEquals("42.3%", ProbeFormat.percentOrNull(0.423))
        assertEquals("12.3s", ProbeFormat.seconds(12_345))
        assertEquals("19.98", ProbeFormat.tpsOrNull(19.98))
        assertEquals("3.21ms", ProbeFormat.msptOrNull(3.214))
    }

    @Test
    @DisplayName("默认区域是阿拉伯语（不同数字形态）时，输出同样稳定")
    fun outputStableUnderNonLatinLocale() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))

        assertEquals("1.00 MB", ProbeFormat.bytes(1024L * 1024))
        assertEquals("50.0%", ProbeFormat.percentOrNull(0.5))
    }
}
