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

    /** 期望出现的场景名（与结果文件名同源，kebab-case）。 */
    @get:Input
    abstract val expectedScenarios: ListProperty<String>

    @TaskAction
    fun verify() {
        val dir = resultsDirectory.orNull?.asFile
        val outcome = E2eResultsVerifier.verify(dir, expectedScenarios.get())
        outcome.lines.forEach { line -> logger.lifecycle(line) }
        if (outcome.failed) {
            throw GradleException("E2E 结果未全部通过或存在缺失场景")
        }
    }
}
