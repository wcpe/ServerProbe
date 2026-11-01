package top.wcpe.mc.plugin.serverprobe.buildlogic.ci

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * 断言 mc-testkit 的 E2E 结果文件全部 PASS、期望场景均有结果。
 *
 * 结果目录允许不存在（例如所有场景在服务端启动阶段就挂了）：此时只要 [expectedScenarios] 非空
 * 就会以「缺结果文件」判失败，而不是让 `@InputDirectory` 直接报一句难懂的输入校验错误。
 */
abstract class VerifyE2eResultsTask : DefaultTask() {

    /** 结果目录（`build/mc-testkit/results`）。 */
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resultsDirectory: DirectoryProperty

    /**
     * 期望出现的场景名（与结果文件名同源，kebab-case）。
     *
     * 故意标 `@Optional`：缺值时不让 Gradle 在校验阶段报一句通用的 `Value not set`，
     * 而是由任务动作抛出带修复指引的错误（空清单会让本校验形同虚设，必须大声失败）。
     */
    @get:Input
    @get:Optional
    abstract val expectedScenarios: ListProperty<String>

    @TaskAction
    fun verify() {
        val expected = expectedScenarios.orNull.orEmpty()
        if (expected.isEmpty()) {
            // 空清单下“所有场景都没跑”与“全部通过”结果一致，必须硬失败（旧内联脚本缺变量即 KeyError）。
            throw GradleException(
                "必须通过 -PexpectedScenarios 或环境变量 EXPECTED_SCENARIOS 给出期望场景（空清单会让本校验形同虚设）",
            )
        }
        val dir = resultsDirectory.orNull?.asFile
        val outcome = E2eResultsVerifier.verify(dir, expected)
        outcome.lines.forEach { line -> logger.lifecycle(line) }
        if (outcome.failed) {
            throw GradleException("E2E 结果未全部通过或存在缺失场景")
        }
    }
}
