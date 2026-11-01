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
        // 输入声明已保证文件存在；这里保留显式提示，避免用错 -P 指向时只看到 Gradle 的通用报文
        if (!source.isFile) {
            throw GradleException("依赖树文本不存在：${source.absolutePath}（先用 ./gradlew :<模块>:dependencies 导出）")
        }
        val resolved = GradleDepsOsv.parse(source.readText())
        if (resolved.isEmpty()) {
            logger.warn("[警告] 未从 ${source.name} 解析出任何依赖")
        }
        val target = osvManifest.get().asFile
        target.parentFile?.mkdirs()
        target.writeText(GradleDepsOsv.render(resolved))
        logger.lifecycle("已导出依赖 ${resolved.size} 个 -> ${target.name}")
    }
}
