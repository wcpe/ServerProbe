package top.wcpe.mc.plugin.serverprobe.buildlogic.ci

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * E2E 结果文件断言。
 *
 * 覆盖点与原内联脚本一致：非 PASS 判失败并回显文件内容、缺结果文件同样判失败、
 * 注释/空行/无等号行均跳过。
 */
class E2eResultsVerifierTest {

    private fun writeResult(dir: File, name: String, content: String) {
        dir.mkdirs()
        File(dir, "$name.properties").writeText(content)
    }

    @DisplayName("全部 PASS 且期望场景齐备时通过")
    @Test
    fun `全部通过`(@TempDir root: File) {
        val dir = File(root, "results")
        writeResult(dir, "a", "# 注释\nstatus=PASS\nmessage=ok\n")

        val outcome = E2eResultsVerifier.verify(dir, listOf("a"))

        assertEquals(false, outcome.failed)
        assertTrue(outcome.lines.contains("[PASS] a"), "实际:${outcome.lines}")
        assertTrue(outcome.lines.contains("结果文件 1 个，期望场景 1 个"), "实际:${outcome.lines}")
    }

    @DisplayName("非 PASS 与缺失场景都判失败")
    @Test
    fun `失败与缺失`(@TempDir root: File) {
        val dir = File(root, "results")
        writeResult(dir, "a", "status=PASS\n")
        writeResult(dir, "b", "status=FAIL\nmessage=握手失败\n")

        val outcome = E2eResultsVerifier.verify(dir, listOf("a", "b", "c"))

        assertEquals(true, outcome.failed)
        assertTrue(outcome.lines.contains("[FAIL] b -> status='FAIL'"), "实际:${outcome.lines}")
        assertTrue(outcome.lines.contains("[缺失] 未找到结果文件: c"), "实际:${outcome.lines}")
        assertTrue(outcome.lines.any { it.contains("message=握手失败") }, "失败时应回显文件内容")
        assertTrue(outcome.lines.contains("结果文件 2 个，期望场景 3 个"), "实际:${outcome.lines}")
    }

    @DisplayName("缺 status 字段记为 None，不当作通过")
    @Test
    fun `缺 status 字段`(@TempDir root: File) {
        val dir = File(root, "results")
        writeResult(dir, "a", "message=没有状态\n")

        val outcome = E2eResultsVerifier.verify(dir, listOf("a"))

        assertEquals(true, outcome.failed)
        assertTrue(outcome.lines.contains("[FAIL] a -> status=None"), "实际:${outcome.lines}")
    }

    @DisplayName("properties 解析：跳注释与空行，按第一个等号切分")
    @Test
    fun `解析 properties`() {
        val props = E2eResultsVerifier.parseProperties("# 注释\n\n  status = PASS  \nmessage=a=b\nno-equals\n")

        assertEquals("PASS", props["status"])
        assertEquals("a=b", props["message"], "只按第一个等号切分")
        assertEquals(null, props["no-equals"])
    }
}
