/**
 * 厂商标识隔离门禁（FR-30 / managed-mode 规格 §3.1 的 CI 断言）。
 *
 * 托管能力是**厂商中立**的通用能力：`provider` 只能是数据，不能成为代码分支。为防止实现漂移，
 * `api` 与 `project:core` 两个厂商中立模块的源码中**不得出现任何控制面厂商标识**（大小写不敏感）；
 * 厂商名只允许出现在文档（`docs/`）与 `integration:integration-<厂商>` 模块。
 *
 * 禁词表可经 Gradle 属性扩展（逗号分隔），默认仅含首个对接方：
 * `-PserverprobeForbiddenVendorIdentifiers=foo,bar`
 *
 * 本任务已挂入 `check`，随 `./gradlew build` 执行。
 */

/** 禁词表：默认首个对接方，可用 -P 覆盖/扩展（逗号分隔，大小写不敏感）。 */
val forbiddenVendorIdentifiers = providers
    .gradleProperty("serverprobeForbiddenVendorIdentifiers")
    .orElse("jianmanager")
    .map { raw -> raw.split(',').map { it.trim() }.filter { it.isNotEmpty() } }

/** 被扫描的源码根目录。 */
val vendorIsolationSourceRoot = layout.projectDirectory.dir("src").asFile

val verifyVendorIsolation by tasks.registering {
    group = "verification"
    description = "校验本模块源码不出现控制面厂商标识（FR-30，大小写不敏感）"
    inputs.files(
        fileTree(vendorIsolationSourceRoot) {
            include("**/*.kt", "**/*.java", "**/*.yml", "**/*.yaml", "**/*.json", "**/*.properties")
        },
    ).withPropertyName("vendorIsolationSources")
    inputs.property("forbiddenVendorIdentifiers", forbiddenVendorIdentifiers.map { it.joinToString(",") })
    doLast {
        val terms = forbiddenVendorIdentifiers.get().map { it.lowercase() }
        val findings = mutableListOf<String>()
        vendorIsolationSourceRoot.walkTopDown()
            .filter { it.isFile && it.extension in SCANNED_EXTENSIONS }
            .sortedBy { it.path }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val lower = line.lowercase()
                    terms.firstOrNull { lower.contains(it) }?.let { hit ->
                        findings.add("${file.relativeTo(vendorIsolationSourceRoot).path}:${index + 1} 命中厂商标识 \"$hit\"")
                    }
                }
            }
        check(findings.isEmpty()) {
            buildString {
                appendLine("检测到控制面厂商标识，违反托管模式的厂商中立约束（FR-30）：")
                findings.forEach { appendLine("  - $it") }
                appendLine("厂商名只允许出现在文档与 integration:integration-<厂商> 模块；")
                appendLine("请把文案改为厂商中立的表述（如“外部控制面 / 控制面 Worker”）。")
            }
        }
    }
}

/** 被扫描的文件扩展名。 */
val SCANNED_EXTENSIONS = setOf("kt", "java", "yml", "yaml", "json", "properties")

// check 由 java 插件创建；用 matching 惰性挂钩，避免与约定插件的应用顺序耦合
tasks.matching { it.name == "check" }.configureEach {
    dependsOn(verifyVendorIsolation)
}
