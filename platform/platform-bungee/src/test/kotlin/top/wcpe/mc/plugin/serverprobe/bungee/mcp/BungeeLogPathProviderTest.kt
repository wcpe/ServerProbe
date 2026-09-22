package top.wcpe.mc.plugin.serverprobe.bungee.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * [BungeeLogPathProvider] 纯逻辑单元测试（FR-28）。
 *
 * BungeeCord 根目录 = 进程工作目录：以临时目录切工作目录（`user.dir` 可写属性）验证根路径解析与
 * 字符集供给；**日志文件名的解析用临时目录穷举**——真机实证（BungeeCord #2088 + JDK 21）当前日志
 * 是带代次后缀的 `proxy.log.0` 而非明文 `proxy.log`，另存 `proxy.log.0.lck` 锁文件。
 * 文件存在性降级路径由 core 的 LogTailToolProvider 统一处理（不在本测试范围）。
 */
class BungeeLogPathProviderTest {

    @Test
    fun `根目录为进程工作目录`() {
        // 注:Paths.get("").toAbsolutePath() 解析自 JVM 启动时固定的默认文件系统,
        // 改 user.dir 系统属性对其无效,故以真实工作目录为期望值断言同源性。
        val expectedRoot = java.nio.file.Paths.get("").toAbsolutePath().normalize()
        val provider = BungeeLogPathProvider()

        assertEquals(expectedRoot, provider.serverRoot(), "根目录应为(解析自启动时)工作目录")
    }

    @Test
    fun `明文 proxy_log 存在时优先采用`(@TempDir root: Path) {
        val plain = Files.createFile(root.resolve("proxy.log"))
        Files.createFile(root.resolve("proxy.log.0"))

        assertEquals(plain, BungeeLogPathProvider().resolveLatestLog(root), "明文 proxy.log 应优先于代次文件")
    }

    /** 真机形态：只有 `proxy.log.0`（+ lck），必须解析到它，否则 log_tail 恒降级为"日志文件不存在"。 */
    @Test
    fun `无明文日志时采用代次文件并排除锁文件`(@TempDir root: Path) {
        val newest = Files.createFile(root.resolve("proxy.log.0"))
        Files.createFile(root.resolve("proxy.log.0.lck"))

        assertEquals(newest, BungeeLogPathProvider().resolveLatestLog(root), "应解析到 proxy.log.0")
    }

    /** 多代并存时按修改时间取最新（代次编号排序语义随 JDK 版本而异，故不按编号猜）。 */
    @Test
    fun `多代并存时取修改时间最新的一份`(@TempDir root: Path) {
        val older = Files.createFile(root.resolve("proxy.log.1"))
        val newer = Files.createFile(root.resolve("proxy.log.0"))
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1_000_000L))
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(2_000_000L))

        assertEquals(newer, BungeeLogPathProvider().resolveLatestLog(root), "应按修改时间取最新一代")
    }

    /** 目录里只有压缩轮转档时回退明文路径，交由既有降级路径统一报"日志文件不存在"。 */
    @Test
    fun `仅存在压缩档或锁文件时回退明文路径`(@TempDir root: Path) {
        Files.createFile(root.resolve("proxy.log.0.gz"))
        Files.createFile(root.resolve("proxy.log.2.lck"))

        assertEquals(
            root.resolve("proxy.log"),
            BungeeLogPathProvider().resolveLatestLog(root),
            "无可用日志文件时应回退明文路径以便报缺失",
        )
    }

    @Test
    fun `字符集显式供给且与 JVM file_encoding 一致`() {
        val provider = BungeeLogPathProvider()
        val expected = runCatching {
            java.nio.charset.Charset.forName(System.getProperty("file.encoding")!!)
        }.getOrDefault(java.nio.charset.Charset.defaultCharset())

        assertEquals(expected, provider.charset(), "字符集应取 file.encoding 而非硬编码 UTF-8")
        assertTrue(provider.charset().name().isNotBlank())
    }
}
