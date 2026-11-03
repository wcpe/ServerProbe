package top.wcpe.mc.plugin.serverprobe.buildlogic.ci

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * CI 校验任务的**接线**守卫（Gradle TestKit）。
 *
 * 判定逻辑由各自对象/单测覆盖；这里只证两件构建级事实：
 * 1. 四个任务真的被注册（而不是脚本里的字面量）；
 * 2. 输入输出声明有效——同一输入下第二次运行命中 `UP-TO-DATE`（这是“声明了输入输出”的行为证据），
 *    以及失败路径真的会让构建失败。
 */
class CiVerificationTasksTest {

    /** 根项目目录（build-logic 的测试从它自己的项目目录起步）。 */
    private val rootDir: File = File(System.getProperty("user.dir")).parentFile

    private fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(rootDir)
        .withArguments(*arguments, "--console=plain", "-q")

    /** 不加 `-q` 的运行器：任务里的 `logger.lifecycle` 在静默模式下会被抑制，需要读文案时用它。 */
    private fun verboseRunner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(rootDir)
        .withArguments(*arguments, "--console=plain")

    @Test
    @DisplayName("四个 CI 任务均已注册")
    fun `任务已注册`() {
        val names = runner("tasks", "--all").build().output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore(' ') }
            .toSet()

        val expected = listOf("verifyBytecodeGate", "generateOsvScanInput", "coverageSummary", "verifyE2eResults")
        val missing = expected.filterNot { it in names }
        assertTrue(missing.isEmpty(), "CI 校验任务缺失：$missing。插件未应用或注册被移除？")
    }

    @Test
    @DisplayName("声明了输入输出：同一输入第二次运行 UP-TO-DATE")
    fun `OSV 清单生成可增量`(@TempDir temp: File) {
        val depsFile = File(temp, "deps.txt").apply {
            writeText("+--- org.slf4j:slf4j-api:2.0.9\n\\--- com.google.code.gson:gson:2.11.0\n")
        }
        val manifest = File(temp, "osv-scanner.json")
        val args = arrayOf(
            "generateOsvScanInput",
            "-PdepsFile=${depsFile.absolutePath}",
            "-PoutputFile=${manifest.absolutePath}",
        )

        val first = runner(*args).build()
        assertEquals(TaskOutcome.SUCCESS, first.task(":generateOsvScanInput")?.outcome, "首次应执行")
        assertTrue(manifest.isFile, "应生成 OSV 清单")
        assertTrue(manifest.readText().contains("org.slf4j:slf4j-api"), "清单应含解析出的坐标")

        val second = runner(*args).build()
        assertEquals(
            TaskOutcome.UP_TO_DATE,
            second.task(":generateOsvScanInput")?.outcome,
            "同一输入再跑应为 UP-TO-DATE：输入输出声明失效应在这里暴露",
        )
    }

    @Test
    @DisplayName("期望场景缺结果文件时构建失败")
    fun `E2E 结果缺失即失败`() {
        val result = verboseRunner("verifyE2eResults", "-PexpectedScenarios=no-such-scenario").buildAndFail()

        // 断言只看 ASCII（场景名与任务结果）：TestKit 的输出解码跟随平台字符集，
        // 中文文案在 Windows 上可能被转成乱码，拿它做断言会让测试在不同环境上忽红忽绿。
        assertEquals(
            TaskOutcome.FAILED,
            result.task(":verifyE2eResults")?.outcome,
            "缺结果文件时必须让任务失败",
        )
        assertTrue(
            result.output.contains("no-such-scenario"),
            "应报告缺失的场景名，实际输出=${result.output.takeLast(400)}",
        )
    }
}
