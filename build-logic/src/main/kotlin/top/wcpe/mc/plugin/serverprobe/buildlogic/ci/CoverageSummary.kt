package top.wcpe.mc.plugin.serverprobe.buildlogic.ci

import org.w3c.dom.Element
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 汇总各模块 JaCoCo XML 的行/分支覆盖率并输出可读数字。
 *
 * 数据源与原先 `.github/workflows/ci.yml` 里的内联脚本一致：各模块
 * `build/reports/jacoco/test/jacocoTestReport.xml` 的根元素 `<counter>` 子元素；
 * 具体收集哪些模块由构建脚本按“应用了 jacoco 插件的子项目”接线（见 `CoverageSummaryTask`），
 * 本对象只负责解析与累加。
 *
 * **只打印、不设门槛**：先积累几个月的真实基线数据，再据此讨论阈值；
 * 避免凭空定一个数把 PR 卡住或催生凑数测试（与原口径一致）。
 */
object CoverageSummary {

    /** 计数器类型：行与分支。 */
    private const val COUNTER_LINE = "LINE"
    private const val COUNTER_BRANCH = "BRANCH"

    /** 累加结果：四类计数（已覆盖 / 未覆盖）。 */
    data class Totals(
        val lineCovered: Long = 0,
        val lineMissed: Long = 0,
        val branchCovered: Long = 0,
        val branchMissed: Long = 0,
    )

    /**
     * 汇总给定报告文件的行与分支覆盖数。
     *
     * @param files 报告文件列表。
     * @return 累加结果。
     */
    fun summarize(files: List<File>): Totals {
        val factory = newFactory()
        var totals = Totals()
        files.forEach { file ->
            val builder = factory.newDocumentBuilder().apply {
                // 空实体解析器兜底：即使某个解析器实现仍想拉取 DTD，也只得到空实体
                setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
            }
            val root = builder.parse(file).documentElement
            val counters = root.childNodes
            for (index in 0 until counters.length) {
                val counter = counters.item(index) as? Element ?: continue
                if (counter.tagName != "counter") {
                    continue
                }
                val missed = counter.getAttribute("missed").toLongOrNull() ?: continue
                val covered = counter.getAttribute("covered").toLongOrNull() ?: continue
                totals = when (counter.getAttribute("type")) {
                    COUNTER_LINE -> totals.copy(lineCovered = totals.lineCovered + covered, lineMissed = totals.lineMissed + missed)
                    COUNTER_BRANCH -> totals.copy(branchCovered = totals.branchCovered + covered, branchMissed = totals.branchMissed + missed)
                    else -> totals
                }
            }
        }
        return totals
    }

    /**
     * 生成与原脚本同口径的汇总行。
     *
     * @param totals 累加结果。
     * @return 待打印的两行文本。
     */
    fun lines(totals: Totals): List<String> = listOf(
        "行覆盖率: ${percent(totals.lineCovered, totals.lineMissed)}% " +
            "(${totals.lineCovered}/${totals.lineCovered + totals.lineMissed})",
        "分支覆盖率: ${percent(totals.branchCovered, totals.branchMissed)}% " +
            "(${totals.branchCovered}/${totals.branchCovered + totals.branchMissed})",
    )

    /**
     * 构造不加载外部 DTD 的解析器工厂。
     *
     * JaCoCo 的 XML 带 `<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">`，
     * 默认配置会尝试从本地解析 `report.dtd` 而报 FileNotFoundException（原脚本用 ElementTree，天然不处理 DTD）。
     * 只需要 `<counter>` 数据，因此关掉 DTD 加载与校验、并用空实体解析器兜底。
     */
    private fun newFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { setFeature("http://xml.org/sax/features/validation", false) }
        isNamespaceAware = false
    }

    /**
     * 计算百分比（无数据时为 0）。
     *
     * @param covered 已覆盖数。
     * @param missed 未覆盖数。
     * @return 百分比数值（未取整）。
     */
    fun percent(covered: Long, missed: Long): String {
        val total = covered + missed
        val value = if (total == 0L) 0.0 else covered.toDouble() / total * 100.0
        // 与原脚本的 %.1f 对齐：对 double 的十进制表示做银行家舍入
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_EVEN).toPlainString()
    }
}
