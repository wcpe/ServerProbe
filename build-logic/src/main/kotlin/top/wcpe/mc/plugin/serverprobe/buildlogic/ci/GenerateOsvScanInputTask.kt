package top.wcpe.mc.plugin.serverprobe.buildlogic.ci

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * 依赖树文本 → OSV-Scanner 自定义清单。
 *
 * `deps.txt` 仍由 workflow 用 `./gradlew :<模块>:dependencies` 导出（那是 Gradle 自己的输出，
 * 不必在任务里重做一次）；本任务只负责纯文本转换。
 *
 * 转换是确定性的、输出只依赖输入，故标 `@CacheableTask`：同一份依赖树可直接命中构建缓存。
 */
@CacheableTask
abstract class GenerateOsvScanInputTask : DefaultTask() {

    /** 依赖树导出文本（`gradle dependencies` 的输出）。 */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependenciesExport: RegularFileProperty

    /** 生成的 OSV 清单。 */
    @get:OutputFile
    abstract val osvManifest: RegularFileProperty

    @TaskAction
    fun generate() {
        val source = dependenciesExport.get().asFile
        val resolved = GradleDepsOsv.parse(source.readText())
        if (resolved.isEmpty()) {
            // 一条依赖都解析不出来，说明导出/解析链路断了（例如 deps.txt 是空文件、
            // 或 `gradle dependencies` 在 `-q` 下什么都没输出）。此时若生成 `"packages": []`，
            // 扫描步骤会“成功但什么也没扫”，静默空跑比报错危险得多。
            throw GradleException("未从 ${source.name} 解析出任何依赖：依赖导出或解析链路已断，拒绝生成空清单")
        }
        val target = osvManifest.get().asFile
        target.parentFile?.mkdirs()
        target.writeText(GradleDepsOsv.render(resolved))
        logger.lifecycle("已导出依赖 ${resolved.size} 个 -> ${target.name}")
    }
}
