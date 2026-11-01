/**
 * CI 辅助任务（仅 root 应用）：把原先散在 `.github/scripts/` 目录与两条 workflow 内联脚本里的
 * 判定逻辑收口成 Gradle 任务，使仓库不再需要额外脚本运行时。
 *
 * 负载内容：
 * - [verifyBytecodeGate]：Java 8 字节码门禁（原 `check-bytecode.py`）；
 * - [generateOsvScanInput]：依赖树 → OSV-Scanner 自定义清单（原 `gradle-deps-to-osv.py`）；
 * - [coverageSummary]：汇总 JaCoCo 行/分支覆盖率（原 ci.yml 内联脚本）；
 * - [verifyE2eResults]：断言 E2E 结果文件全部 PASS（原 e2e.yml 内联脚本）。
 *
 * 判定语义与阈值均不变——它们不是「重写一遍门禁」，只是换一个不引入新运行时的宿主。
 */
import top.wcpe.mc.plugin.serverprobe.buildlogic.ci.BytecodeGate
import top.wcpe.mc.plugin.serverprobe.buildlogic.ci.CoverageSummary
import top.wcpe.mc.plugin.serverprobe.buildlogic.ci.E2eResultsVerifier
import top.wcpe.mc.plugin.serverprobe.buildlogic.ci.GradleDepsOsv
import java.io.File

/**
 * 把命令行传进来的路径解析成真实文件：绝对路径原样用，相对路径基于仓库根。
 *
 * @param root 仓库根目录。
 * @param value 传入的路径文本。
 * @return 解析后的文件。
 */
fun resolveFromRoot(root: File, value: String): File {
    val file = File(value)
    return if (file.isAbsolute) file else File(root, value)
}

// ── Java 8 字节码门禁 ──
// 必须先有完整构建产物（classes + 发行 jar），否则门禁会因「产物缺失」直接失败——这是有意为之：
// 静默变绿比失败更危险。

tasks.register("verifyBytecodeGate") {
    group = "verification"
    description = "校验核心模块产物与发行 jar 的 class major version <= 52（Java 8）"
    val rootDir = layout.projectDirectory.asFile
    doLast {
        val outcome = BytecodeGate.scan(rootDir)
        println("已扫描 class 文件 ${outcome.entries.size} 个")
        outcome.distribution().toSortedMap().forEach { (major, count) ->
            println("  major $major: $count 个")
        }
        if (outcome.jars.isEmpty()) {
            println("发行 jar: 未匹配到 plugin/build/libs/ServerProbe-*.jar")
        } else {
            println("发行 jar: ${outcome.jars.joinToString(", ")}")
        }
        outcome.missing.forEach { target ->
            System.err.println("[缺失] 未找到受检产物: $target")
        }
        if (outcome.violations.isNotEmpty()) {
            System.err.println(
                "[失败] ${outcome.violations.size} 个 class 的 major version > ${BytecodeGate.MAX_MAJOR}(Java 8):",
            )
            outcome.violations.forEach { entry ->
                System.err.println("  major ${entry.major}: ${entry.displayPath}")
            }
            throw GradleException("字节码门禁失败：存在 major version > ${BytecodeGate.MAX_MAJOR} 的 class")
        }
        if (outcome.missing.isNotEmpty()) {
            System.err.println("[失败] 受检产物缺失，门禁无法确认")
            throw GradleException("字节码门禁失败：受检产物缺失（${outcome.missing.joinToString(", ")}）")
        }
        println("[通过] 全部 class 的 major version <= ${BytecodeGate.MAX_MAJOR}(Java 8)")
    }
}

// ── 依赖树 → OSV-Scanner 清单 ──
// `deps.txt` 仍由 workflow 用 `./gradlew :<模块>:dependencies` 导出（那是 Gradle 自己的输出，
// 没必要在任务里重做一次）；本任务只负责把文本转成 OSV 能读的 JSON。

tasks.register("generateOsvScanInput") {
    group = "verification"
    description = "把 gradle dependencies 的导出文本转成 OSV-Scanner 自定义清单"
    val depsFile = providers.gradleProperty("depsFile").orElse("deps.txt")
    val outputFile = providers.gradleProperty("outputFile").orElse("osv-scanner.json")
    val rootDir = layout.projectDirectory.asFile
    doLast {
        val source = resolveFromRoot(rootDir, depsFile.get())
        val target = resolveFromRoot(rootDir, outputFile.get())
        check(source.isFile) { "依赖树文本不存在：${source.absolutePath}（先用 ./gradlew :<模块>:dependencies 导出）" }
        val resolved = GradleDepsOsv.parse(source.readText())
        if (resolved.isEmpty()) {
            System.err.println("[警告] 未从 ${depsFile.get()} 解析出任何依赖")
        }
        target.writeText(GradleDepsOsv.render(resolved))
        println("已导出依赖 ${resolved.size} 个 -> ${outputFile.get()}")
    }
}

// ── 覆盖率汇总（只打印、不设门槛）──

tasks.register("coverageSummary") {
    group = "verification"
    description = "汇总各模块 JaCoCo 行/分支覆盖率（不设门槛）"
    val rootDir = layout.projectDirectory.asFile
    doLast {
        val files = CoverageSummary.reportFiles(rootDir)
        if (files.isEmpty()) {
            println("未找到 JaCoCo 报告（先跑 ./gradlew test jacocoTestReport）")
        }
        CoverageSummary.lines(CoverageSummary.summarize(files)).forEach { println(it) }
    }
}

// ── E2E 结果断言 ──
// 期望场景列表由 workflow 传入（`-PexpectedScenarios` 或环境变量 `EXPECTED_SCENARIOS`），
// 与原先内联脚本读环境变量的口径一致。

tasks.register("verifyE2eResults") {
    group = "verification"
    description = "断言 build/mc-testkit/results 下的 E2E 结果文件全部 PASS"
    val expected = providers.gradleProperty("expectedScenarios")
        .orElse(providers.environmentVariable("EXPECTED_SCENARIOS"))
        .orElse("")
    val rootDir = layout.projectDirectory.asFile
    doLast {
        val resultsDir = rootDir.resolve("build/mc-testkit/results")
        val scenarios = expected.get().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val outcome = E2eResultsVerifier.verify(resultsDir, scenarios)
        outcome.lines.forEach { println(it) }
        if (outcome.failed) {
            throw GradleException("E2E 结果未全部通过或存在缺失场景")
        }
    }
}
