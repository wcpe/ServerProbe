package top.wcpe.mc.plugin.serverprobe.buildlogic.ci

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Java 8 字节码门禁任务。
 *
 * 输入全部由构建脚本接线，任务自己不搜目录：
 * - [classDirectories] 来自各受检模块 **main 源集的 class 输出**（test 源集允许用更高版本 JDK 编译，不检）；
 * - [releaseJar] 来自 `:plugin` 的发行 Jar（产物由构建提供，故本任务可单独运行，不需先手动 build）。
 *
 * 判定逻辑在 [BytecodeGate]（纯函数、可单测），本类只负责取输入、报告与失败。
 */
abstract class VerifyBytecodeGateTask : DefaultTask() {

    /**
     * 待检 class 目录。
     *
     * 用 `RELATIVE` 路径敏感度：缓存/增量判定只关心目录内相对路径，不因仓库换了位置而失效。
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    abstract val classDirectories: ConfigurableFileCollection

    /**
     * 发行 Jar。
     *
     * `NONE` 敏感度：判定只读 jar 内容，与文件名无关（文件本身作为 `@InputFile` 参与指纹）。
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val releaseJar: RegularFileProperty

    /** 仓库根目录：仅用于把报告路径转成相对路径，不参与输入指纹。 */
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    /** 必须产出 class 的模块（相对仓库根）；实际产物全空时计为「缺失」并判失败。 */
    @get:Input
    abstract val requiredModules: ListProperty<String>

    @TaskAction
    fun verify() {
        val root = rootDirectory.get().asFile
        val outcome = BytecodeGate.evaluate(
            classRoots = classDirectories.files,
            jars = listOf(releaseJar.get().asFile),
            requiredModules = requiredModules.get(),
            root = root,
        )

        logger.lifecycle("已扫描 class 文件 ${outcome.entries.size} 个")
        outcome.distribution().toSortedMap().forEach { (major, count) ->
            logger.lifecycle("  major $major: $count 个")
        }
        if (outcome.jars.isEmpty()) {
            logger.lifecycle("发行 jar: 未匹配到 plugin/build/libs/ServerProbe-*.jar")
        } else {
            logger.lifecycle("发行 jar: ${outcome.jars.joinToString(", ")}")
        }
        outcome.missing.forEach { target -> logger.error("[缺失] 未找到受检产物: $target") }

        if (outcome.violations.isNotEmpty()) {
            logger.error(
                "[失败] ${outcome.violations.size} 个 class 的 major version > ${BytecodeGate.MAX_MAJOR}(Java 8):",
            )
            outcome.violations.forEach { entry -> logger.error("  major ${entry.major}: ${entry.displayPath}") }
            throw GradleException("字节码门禁失败：存在 major version > ${BytecodeGate.MAX_MAJOR} 的 class")
        }
        if (outcome.missing.isNotEmpty()) {
            logger.error("[失败] 受检产物缺失，门禁无法确认")
            throw GradleException("字节码门禁失败：受检产物缺失（${outcome.missing.joinToString(", ")}）")
        }
        logger.lifecycle("[通过] 全部 class 的 major version <= ${BytecodeGate.MAX_MAJOR}(Java 8)")
    }
}
