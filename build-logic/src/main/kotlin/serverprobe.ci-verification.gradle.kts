/**
 * CI 辅助任务（仅 root 应用）：把原先散在仓库脚本目录与两条 workflow 内联脚本里的判定逻辑
 * 收口成 Gradle 任务，使仓库不再需要额外脚本运行时。
 *
 * 提供四个任务（判定逻辑在 [top.wcpe.mc.plugin.serverprobe.buildlogic.ci] 下的纯函数对象里）：
 * - [verifyBytecodeGate]：Java 8 字节码门禁（原独立脚本）；
 * - [generateOsvScanInput]：依赖树 → OSV-Scanner 自定义清单（原独立脚本）；
 * - [coverageSummary]：汇总 JaCoCo 行/分支覆盖率（原 ci.yml 内联脚本）；
 * - [verifyE2eResults]：断言 E2E 结果文件全部 PASS（原 e2e.yml 内联脚本）。
 *
 * 四个任务均为**类型化任务 + 声明输入输出**：输入输出全部通过 property / file collection 接线，
 * Gradle 自行推导任务依赖与增量，因而每个任务都能单独运行（不依赖 workflow 先手动跑一次 build），
 * 第二次运行可命中 UP-TO-DATE。
 *
 * 判定语义与阈值均不变——它们不是「重写一遍门禁」，只是换了一个不引入新运行时的宿主。
 */
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import top.wcpe.mc.plugin.serverprobe.buildlogic.ci.BytecodeGate
import top.wcpe.mc.plugin.serverprobe.buildlogic.ci.CoverageSummaryTask
import top.wcpe.mc.plugin.serverprobe.buildlogic.ci.GenerateOsvScanInputTask
import top.wcpe.mc.plugin.serverprobe.buildlogic.ci.VerifyBytecodeGateTask
import top.wcpe.mc.plugin.serverprobe.buildlogic.ci.VerifyE2eResultsTask
import java.io.File

/**
 * 把命令行传入的路径解析成真实文件：绝对路径原样用，相对路径基于仓库根。
 *
 * @param value 传入的路径文本。
 * @return 解析后的文件。
 */
private fun Project.resolveRepoPath(value: String): File {
    val file = File(value)
    return if (file.isAbsolute) file else File(projectDir, value)
}

// ── Java 8 字节码门禁 ──
// 受检目录从各模块 main 源集输出接线：既只含 main（test 源集允许更高 JDK），
// 又由 Gradle 自动推导出构建依赖——因此任务单独跑就会先编译好被测产物，不必先执行 build。
// 发行 jar 的产物任务（TabooLib 打包）不是标准 ArchiveTask，无法取 archiveFile provider，
// 故用固定路径 + 显式 dependsOn：路径由发布命名约定给出，依赖由打包任务保证。
tasks.register<VerifyBytecodeGateTask>("verifyBytecodeGate") {
    group = "verification"
    description = "校验核心模块产物与发行 jar 的 class major version <= 52（Java 8）"
    rootDirectory.set(layout.projectDirectory)
    // 发行 jar 的产物任务（TabooLib 打包）不是标准 ArchiveTask，取不到 archiveFile provider；
    // 故用该子项目的 buildDirectory 拼产物路径，并以任务提供者（而非字符串路径）声明依赖。
    val pluginProject = project(":plugin")
    requiredModules.set(BytecodeGate.MODULES)
    classDirectories.from(
        BytecodeGate.MODULES.map { module ->
            project(":" + module.replace('/', ':'))
                .extensions.getByType<SourceSetContainer>()
                .getByName("main")
                .output
                .classesDirs
        },
    )
    releaseJar.set(pluginProject.layout.buildDirectory.file("libs/${project.name}-${project.version}.jar"))
    dependsOn(pluginProject.tasks.named("taboolibMainTask"))
}

// ── 依赖树 → OSV-Scanner 清单 ──
// 依赖树导出仍由 workflow 调 Gradle 自己完成（见 dependency-scan.yml），此处只做纯文本转换。
tasks.register<GenerateOsvScanInputTask>("generateOsvScanInput") {
    group = "verification"
    description = "把 gradle dependencies 的导出文本转成 OSV-Scanner 自定义清单"
    dependenciesExport.set(layout.file(providers.gradleProperty("depsFile").orElse("deps.txt").map { resolveRepoPath(it) }))
    osvManifest.set(layout.file(providers.gradleProperty("outputFile").orElse("osv-scanner.json").map { resolveRepoPath(it) }))
}

// ── 覆盖率汇总（只打印、不设门槛）──
// 报告从 **应用了 jacoco 插件的子项目** 收集：任务依赖按类型取（不猜任务名），
// 报告路径用 `jacocoTestReport` 的默认产物路径（本仓无自定义报告位置；如有，需同步这里）。
// 注：e2e 系列模块（fixture-core / harness-*）不应用 jacoco，它们的单测覆盖率不在统计内——这是事实，不是缺陷。
val jacocoExpectedModules = project.objects.listProperty(String::class.java)
val jacocoReports = project.objects.fileCollection()
project.subprojects.forEach { sub ->
    sub.plugins.withId("jacoco") {
        jacocoExpectedModules.add(sub.path)
        jacocoReports.from(sub.layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml"))
    }
}
tasks.register<CoverageSummaryTask>("coverageSummary") {
    group = "verification"
    description = "汇总各模块 JaCoCo 行/分支覆盖率（不设门槛）"
    reports.from(jacocoReports)
    expectedReportCount.set(jacocoExpectedModules.map { it.size })
    dependsOn(project.subprojects.map { sub -> sub.tasks.withType(JacocoReport::class.java) })
}

// ── E2E 结果断言 ──
// 期望场景列表由 workflow 传入（-PexpectedScenarios 或环境变量 EXPECTED_SCENARIOS），
// 与原先内联脚本读环境变量的口径一致。
tasks.register<VerifyE2eResultsTask>("verifyE2eResults") {
    group = "verification"
    description = "断言 build/mc-testkit/results 下的 E2E 结果文件全部 PASS"
    resultsDirectory.convention(layout.buildDirectory.dir("mc-testkit/results"))
    expectedScenarios.convention(
        providers.gradleProperty("expectedScenarios")
            .orElse(providers.environmentVariable("EXPECTED_SCENARIOS"))
            .map { value -> value.split(Regex("\\s+")).filter { it.isNotEmpty() } },
    )
}
