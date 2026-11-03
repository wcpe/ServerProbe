package top.wcpe.mc.plugin.serverprobe.buildlogic.ci

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 依赖树 → OSV 清单的转换。
 *
 * 覆盖点与原脚本一致：只认依赖树行、跳过 `(n)`/`FAILED`、版本仲裁取箭头后版本、
 * 后出现的坐标覆盖先前版本、按坐标排序。
 */
class GradleDepsOsvTest {

    /** 与 `gradle dependencies` 同形的样本（含各类行尾标记）。 */
    private val sample = """
        +--- project :api
        |    +--- androidx:annotation:1.0
        |    \--- com.example:lib:1.0 -> 2.0
        +--- com.example:unresolved:1.0 (n)
        +--- com.example:broken:1.0 FAILED
        \--- org.other:dep:3.1 (c)
        plain text without tree prefix com.example:ignored:9.9
    """.trimIndent()

    @DisplayName("只认依赖树行，跳过 (n) 与 FAILED，仲裁取箭头后版本")
    @Test
    fun `解析依赖树`() {
        val resolved = GradleDepsOsv.parse(sample)

        assertEquals(
            mapOf(
                "androidx:annotation" to "1.0",
                "com.example:lib" to "2.0",
                "org.other:dep" to "3.1",
            ),
            resolved,
        )
        assertTrue("com.example:unresolved" !in resolved, "(n) 是未解析声明，不应纳入")
        assertTrue("com.example:broken" !in resolved, "FAILED 不应纳入")
        assertTrue("com.example:ignored" !in resolved, "非依赖树行不应纳入")
    }

    @DisplayName("后出现的同一坐标覆盖先前版本（Gradle 仲裁胜出版本）")
    @Test
    fun `同坐标后写覆盖`() {
        val resolved = GradleDepsOsv.parse(
            """
            +--- com.example:lib:1.0
            \--- com.example:lib:1.0 -> 3.0
            """.trimIndent(),
        )

        assertEquals(mapOf("com.example:lib" to "3.0"), resolved)
    }

    @DisplayName("输出按坐标排序的 OSV 自定义清单 JSON")
    @Test
    fun `渲染清单`() {
        val json = GradleDepsOsv.render(linkedMapOf("b:y" to "2", "a:x" to "1"))

        val expected = """{
  "results": [
    {
      "packages": [
        {
          "package": {
            "name": "a:x",
            "version": "1",
            "ecosystem": "Maven"
          }
        },
        {
          "package": {
            "name": "b:y",
            "version": "2",
            "ecosystem": "Maven"
          }
        }
      ]
    }
  ]
}"""
        assertEquals(expected, json)
    }

    @DisplayName("空依赖表也要产出合法结构")
    @Test
    fun `空清单`() {
        val json = GradleDepsOsv.render(emptyMap())

        assertTrue(json.contains("\"packages\": []"), "实际:$json")
    }
}
