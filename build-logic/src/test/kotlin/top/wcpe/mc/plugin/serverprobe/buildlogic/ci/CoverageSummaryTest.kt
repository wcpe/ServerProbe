package top.wcpe.mc.plugin.serverprobe.buildlogic.ci

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * JaCoCo 覆盖率汇总。
 *
 * 覆盖点与原内联脚本一致：只读根元素下的 `<counter>` 直接子节点、LINE/BRANCH 分别累加、
 * 无数据时百分比为 0.0、输出保留一位小数。
 */
class CoverageSummaryTest {

    /**
     * 写一份与 JaCoCo 真实产物同形的报告：**带 DOCTYPE**，指向本地不存在的 `report.dtd`。
     *
     * 这一行正是回归点：默认的 DOM 解析器会去加载这份外部 DTD 而报 FileNotFoundException，
     * 而原脚本用的 ElementTree 天然不处理 DTD——真实跑 CI 时才发现，因此钉进单测。
     */
    private fun writeReport(root: File, modulePath: String, counters: String): File {
        val file = File(root, "$modulePath/build/reports/jacoco/test/jacocoTestReport.xml")
        file.parentFile.mkdirs()
        file.writeText(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                "\n" +
                """<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">""" +
                "\n<report name=\"demo\">$counters</report>",
        )
        return file
    }

    @DisplayName("按模块累加 LINE/BRANCH，忽略其它计数器")
    @Test
    fun `累加两个模块`(@TempDir root: File) {
        writeReport(
            root,
            "core",
            "<counter type=\"LINE\" missed=\"2\" covered=\"8\"/><counter type=\"BRANCH\" missed=\"1\" covered=\"1\"/>",
        )
        writeReport(
            root,
            "platform/bukkit",
            "<counter type=\"LINE\" missed=\"8\" covered=\"2\"/><counter type=\"BRANCH\" missed=\"0\" covered=\"3\"/>",
        )

        val files = CoverageSummary.reportFiles(root)
        val totals = CoverageSummary.summarize(files)

        assertEquals(2, files.size, "两级目录下的报告都要被枚举")
        assertEquals(10L, totals.lineCovered)
        assertEquals(10L, totals.lineMissed)
        assertEquals(4L, totals.branchCovered)
        assertEquals(1L, totals.branchMissed)
        assertEquals(
            listOf("行覆盖率: 50.0% (10/20)", "分支覆盖率: 80.0% (4/5)"),
            CoverageSummary.lines(totals),
        )
    }

    @DisplayName("百分比：无数据取 0.0，四舍五入保留一位小数")
    @Test
    fun `百分比格式`() {
        assertEquals("0.0", CoverageSummary.percent(0, 0))
        assertEquals("50.0", CoverageSummary.percent(1, 1))
        assertEquals("33.3", CoverageSummary.percent(1, 2))
        assertEquals("66.7", CoverageSummary.percent(2, 1))
        assertEquals("100.0", CoverageSummary.percent(7, 0))
    }

    @DisplayName("没有报告时汇总为全 0，不抛异常")
    @Test
    fun `无报告`(@TempDir root: File) {
        val totals = CoverageSummary.summarize(CoverageSummary.reportFiles(root))

        assertTrue(totals.lineCovered == 0L && totals.branchMissed == 0L)
    }
}
