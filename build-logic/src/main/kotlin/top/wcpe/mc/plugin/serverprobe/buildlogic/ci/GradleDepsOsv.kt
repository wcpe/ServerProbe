package top.wcpe.mc.plugin.serverprobe.buildlogic.ci

/**
 * 把 `gradle dependencies` 的输出转成 OSV-Scanner 的自定义清单。
 *
 * 背景：OSV-Scanner 的 Java 生态只认 `gradle.lockfile` / `pom.xml` 等清单文件，本工程未启用
 * Gradle 依赖锁定（无 lockfile），直接扫描源码会漏掉绝大多数依赖。故先由 Gradle 解析出真实依赖树
 * （含传递依赖与版本仲裁结果），再转成 OSV 支持的自定义清单格式。
 *
 * 本对象是原 `.github/scripts/gradle-deps-to-osv.py` 的等价实现：坐标正则、跳过标记、
 * 版本仲裁取箭头后版本、按键排序等口径均未变动。
 */
object GradleDepsOsv {

    /**
     * 匹配依赖行里的 `group:artifact:version`。
     *
     * `gradle dependencies` 用 `+---` / `\---` 画树，行尾可能带：
     * `(n)` 未解析、`(c)` 约束、`(*)` 已在别处展开、`-> 1.2.3` 版本仲裁、`FAILED` 解析失败。
     */
    private val COORDINATE = Regex("([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-]+):([A-Za-z0-9_.\\-+]+)")

    /** 版本仲裁标记：`group:art:1.0 -> 2.0` 取箭头后的版本。 */
    private val ARBITRATED_VERSION = Regex("->\\s*([A-Za-z0-9_.\\-+]+)")

    /** 行尾标注为依赖声明而非实际解析结果，或解析失败者不纳入。 */
    private val SKIP_MARKERS = listOf("(n)", "FAILED")

    /** 依赖树行前缀（只有这些行才承载坐标）。 */
    private val TREE_PREFIXES = listOf("+---", "\\---", "|")

    /**
     * 解析依赖树文本。
     *
     * @param text `gradle dependencies` 的导出内容。
     * @return `group:artifact` → 版本（后出现的行覆盖先前版本，即 Gradle 仲裁后的胜出版本）。
     */
    fun parse(text: String): Map<String, String> {
        val resolved = LinkedHashMap<String, String>()
        text.lineSequence().forEach { rawLine ->
            val stripped = rawLine.trim()
            if (TREE_PREFIXES.none { stripped.startsWith(it) }) {
                return@forEach
            }
            if (SKIP_MARKERS.any { stripped.contains(it) }) {
                return@forEach
            }
            val match = COORDINATE.find(stripped) ?: return@forEach
            val (group, artifact, initialVersion) = match.destructured
            // 版本仲裁：group:art:1.0 -> 2.0 取箭头后的版本
            val version = ARBITRATED_VERSION.find(stripped)?.groupValues?.get(1) ?: initialVersion
            // Gradle 用 project(...) 表示工程内依赖，坐标行里不会出现，这里天然跳过
            resolved["$group:$artifact"] = version
        }
        return resolved
    }

    /**
     * 渲染 OSV 自定义清单 JSON（`indent=2`，与原先导出的格式保持一致）。
     *
     * @param resolved [parse] 的结果。
     * @return JSON 文本（不含结尾换行）。
     */
    fun render(resolved: Map<String, String>): String {
        val sorted = resolved.entries.sortedBy { it.key }
        val packages = if (sorted.isEmpty()) {
            "[]"
        } else {
            sorted.joinToString(",\n", prefix = "[\n", postfix = "\n      ]") { (name, version) ->
                """        {
          "package": {
            "name": "${escape(name)}",
            "version": "${escape(version)}",
            "ecosystem": "Maven"
          }
        }"""
            }
        }
        return """{
  "results": [
    {
      "packages": $packages
    }
  ]
}"""
    }

    /** JSON 字符串转义（坐标与版本字符集很窄，这里只处理必要的两类）。 */
    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
