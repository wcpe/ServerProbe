package top.wcpe.mc.plugin.serverprobe.buildlogic.ci

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * 汇总各模块 JaCoCo 行/分支覆盖率。
 *
 * **只打印、不设门槛**：先积累几个月的真实基线数据再讨论阈值（与原内联脚本口径一致）。
 * 输入为各模块 `jacocoTestReport` 产物，由构建脚本接线（并挂上对应任务的依赖，使本任务可单独运行）。
 */
abstract class CoverageSummaryTask : DefaultTask() {

    /** 各模块的 JaCoCo XML 报告文件；不存在（未跑覆盖率）时按空集处理。 */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val reports: ConfigurableFileCollection

    @TaskAction
    fun summarize() {
        val files = reports.files.filter { it.isFile }.sortedBy { it.invariantSeparatorsPath }
        if (files.isEmpty()) {
            // 不静默：一个模块的报告都没收到，说明 jacoco 未应用或测试未跑，数字会整体变少
            logger.warn("未收到任何模块的 JaCoCo 报告（jacoco 插件未应用或未跑测试），本次汇总无可信数据")
        }
        CoverageSummary.lines(CoverageSummary.summarize(files)).forEach { line -> logger.lifecycle(line) }
    }
}
