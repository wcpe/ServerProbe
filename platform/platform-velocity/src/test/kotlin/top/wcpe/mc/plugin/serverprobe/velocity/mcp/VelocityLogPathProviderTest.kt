package top.wcpe.mc.plugin.serverprobe.velocity.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * [VelocityLogPathProvider] 纯逻辑单元测试（FR-28）。
 *
 * Velocity 根目录 = 进程工作目录、日志为根下 `logs/latest.log`（3.1.1–4.x 无差异）：
 * 以临时目录切工作目录（`user.dir` 可写属性）验证路径解析与字符集供给；
 * 文件存在性降级路径由 core 的 LogTailToolProvider 统一处理（不在本测试范围）。
 */
class VelocityLogPathProviderTest {

    @Test
    fun `根目录为进程工作目录且日志指向 logs_latest_log`() {
        // 注:Paths.get("").toAbsolutePath() 解析自 JVM 启动时固定的默认文件系统,
        // 改 user.dir 系统属性对其无效,故以真实工作目录为期望值断言同源性。
        val expectedRoot = java.nio.file.Paths.get("").toAbsolutePath().normalize()
        val provider = VelocityLogPathProvider()

        assertEquals(expectedRoot, provider.serverRoot(), "根目录应为(解析自启动时)工作目录")
        assertEquals(
            expectedRoot.resolve("logs").resolve("latest.log"),
            provider.latestLog(),
            "最新日志应为根下 logs/latest.log"
        )
    }

    @Test
    fun `字符集显式供给且与 JVM file_encoding 一致`() {
        val provider = VelocityLogPathProvider()
        val expected = runCatching {
            java.nio.charset.Charset.forName(System.getProperty("file.encoding")!!)
        }.getOrDefault(java.nio.charset.Charset.defaultCharset())

        assertEquals(expected, provider.charset(), "字符集应取 file.encoding 而非硬编码 UTF-8")
        assertTrue(provider.charset().name().isNotBlank())
    }
}
