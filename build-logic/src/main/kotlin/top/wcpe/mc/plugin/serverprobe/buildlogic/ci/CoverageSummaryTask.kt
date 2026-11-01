package top.wcpe.mc.plugin.serverprobe.buildlogic.ci

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * 汇总各模块 JaCoCo 行/分支覆盖率。
 *
 * **只打印、不设门槛**：先积累几个月的真实基线数据再讨论阈值（与原内联脚本口径一致）。
 * 输入为各模块 `JacocoReport` 任务的 XML 输出，由构建脚本接线（文件集合自动带出任务依赖，使本任务可单独运行）。
 *
 * **部分缺失也要响**：“一个都没收到”会被发现，但“七个模块只来两份”以前是完全静默的——
 * 覆盖率数字会静静变小，而它正是给 PR 看的基线。所以传入应到报告数，不等就告警（只告警，不设门槛）。
 */
abstract class CoverageSummaryTask : DefaultTask() {

    /** 各模块的 JaCoCo XML 报告文件；不存在（未跑覆盖率）时按空集处理。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val reports: ConfigurableFileCollection

    /** 应用了 jacoco 插件的模块数：用于比对“应到/实到”并告警。 */
    @get:Input
    abstract val expectedReportCount: Property<Int>

    @TaskAction
    fun summarize() {
        val files = reports.files.filter { it.isFile }.sortedBy { it.invariantSeparatorsPath }
        val expected = expectedReportCount.getOrElse(0)
        if (files.isEmpty()) {
            // 不静默：一个模块的报告都没收到，说明 jacoco 未应用或测试未跑，数字会整体变少
            logger.warn("未收到任何模块的 JaCoCo 报告（jacoco 插件未应用或未跑测试），本次汇总无可信数据")
        } else if (files.size < expected) {
            // 部分缺失：例如某模块测试失败导致 report 未生成——以前这种情况完全无声
            logger.warn(
                "JaCoCo 报告不齐：应到 $expected 个模块、实到 ${files.size} 个（缺报告的模块会被静默排除在汇总之外）",
            )
        }
        CoverageSummary.lines(CoverageSummary.summarize(files)).forEach { line -> logger.lifecycle(line) }
    }
}
