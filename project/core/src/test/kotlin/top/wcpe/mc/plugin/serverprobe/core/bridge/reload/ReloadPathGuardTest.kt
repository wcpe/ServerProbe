package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * [ReloadPathGuard] 单元测试（配置重载规格 §3.3 + §5「越界」一行）。
 *
 * 四条规则各有用例，另外钉住两个容易做错的细节：
 * - **末尾斜杠必须保留**（目录前缀的语义，吃掉它会让监听器静默收不到）；
 * - **符号链接逃逸必须挡住**（字面合法、canonical 后越界，是唯一无法靠词法校验发现的一类）。
 */
class ReloadPathGuardTest {

    @TempDir
    lateinit var tempDir: Path

    // -------------------------------------------------------------- 规则 1：绝对路径 / NUL / 空

    /** 验收「越界」：`/etc/passwd`（前导斜杠）→ null。 */
    @Test
    fun `前导斜杠的绝对路径被拒绝`() {
        assertNull(ReloadPathGuard.normalize("/etc/passwd", tempDir.toFile()))
        assertNull(ReloadPathGuard.normalize("/plugins/Foo/config.yml", tempDir.toFile()), "绝对路径即便落在 plugins 下也不接受")
    }

    /** Windows 盘符同样视为绝对路径。 */
    @Test
    fun `Windows盘符被拒绝`() {
        assertNull(ReloadPathGuard.normalize("C:/plugins/Foo/config.yml", tempDir.toFile()))
        assertNull(ReloadPathGuard.normalize("c:\\plugins\\Foo\\config.yml", tempDir.toFile()))
    }

    /** 验收「越界」：含 `NUL` → null（NUL 会让"校验的路径"与"打开的文件"不一致）。 */
    @Test
    fun `含NUL字符被拒绝`() {
        assertNull(ReloadPathGuard.normalize("plugins/Foo/config.yml${0.toChar()}x", tempDir.toFile()))
    }

    /** 空串与纯空白段 → null。 */
    @Test
    fun `空串被拒绝`() {
        assertNull(ReloadPathGuard.normalize("", tempDir.toFile()))
        assertNull(ReloadPathGuard.normalize(".", tempDir.toFile()))
        assertNull(ReloadPathGuard.normalize("./", tempDir.toFile()))
    }

    // -------------------------------------------------------------- 规则 2：折叠 . 与 ..

    /** 验收「越界」：`../../etc/passwd` → null（`..` 在栈空时出现即逃逸）。 */
    @Test
    fun `向上越界的点点斜杠被拒绝`() {
        assertNull(ReloadPathGuard.normalize("../../etc/passwd", tempDir.toFile()))
        assertNull(ReloadPathGuard.normalize("plugins/../../etc/passwd", tempDir.toFile()))
    }

    /** 验收「越界」：`plugins/../secret` → null（先合法前缀再逃逸）。 */
    @Test
    fun `plugins前缀后逃逸被拒绝`() {
        assertNull(ReloadPathGuard.normalize("plugins/../secret", tempDir.toFile()))
    }

    /** 内部的 `.` 与 `..` 正常折叠到 plugins 内。 */
    @Test
    fun `内部的点段被折叠`() {
        assertEquals(
            "plugins/Foo/config.yml",
            ReloadPathGuard.normalize("plugins/./Foo/../Foo/config.yml", tempDir.toFile()),
        )
    }

    /** 反斜杠统一折叠为斜杠（Windows 客户端拼的 payload 也能用）。 */
    @Test
    fun `反斜杠统一为斜杠`() {
        assertEquals(
            "plugins/Foo/config.yml",
            ReloadPathGuard.normalize("plugins\\Foo\\config.yml", tempDir.toFile()),
        )
    }

    // -------------------------------------------------------------- 规则 3：必须在 plugins/ 下

    /** 不在 `plugins/` 子树的相对路径 → null（限定子树是本功能的攻击面边界）。 */
    @Test
    fun `不在plugins子树的路径被拒绝`() {
        assertNull(ReloadPathGuard.normalize("server.properties", tempDir.toFile()))
        assertNull(ReloadPathGuard.normalize("pluginsx/config.yml", tempDir.toFile()), "前缀匹配必须按路径段，不是按字符")
        assertNull(ReloadPathGuard.normalize("world/level.dat", tempDir.toFile()))
    }

    /** `plugins/` 自身合法（声明"整个插件目录"是允许的）。 */
    @Test
    fun `plugins根本身合法`() {
        assertEquals("plugins/", ReloadPathGuard.normalize("plugins/", tempDir.toFile()))
        assertEquals("plugins", ReloadPathGuard.normalize("plugins", tempDir.toFile()))
    }

    /** 末尾斜杠是目录前缀语义，必须保留。 */
    @Test
    fun `末尾斜杠被保留`() {
        assertEquals("plugins/Foo/", ReloadPathGuard.normalize("plugins/Foo/", tempDir.toFile()))
        assertEquals("plugins/Foo/", ReloadPathGuard.normalize("plugins/Foo/./", tempDir.toFile()))
    }

    // -------------------------------------------------------------- 规则 4：canonical 后仍在 plugins 内

    /** 正常文件路径原样通过（文件不存在也允许：JM 可能对"即将写入的配置"下发重载）。 */
    @Test
    fun `不存在的文件路径依然通过`() {
        assertNotNull(ReloadPathGuard.normalize("plugins/NotInstalled/config.yml", tempDir.toFile()))
    }

    /**
     * 符号链接逃逸：`plugins/Foo/link -> <工作目录外>`。
     *
     * 这是**唯一**无法靠词法校验发现的一类越界，因此第 4 条规则不可省。
     */
    @Test
    fun `符号链接逃逸被拒绝`() {
        val outside = tempDir.resolve("outside")
        Files.createDirectories(outside)
        // 被访问的文件必须真实存在：Windows 的 File.canonicalFile 不会跟随
        // **不存在**路径末尾的符号链接（Linux 会）。
        val secret = outside.resolve("secret.yml")
        Files.write(secret, ByteArray(0))
        val plugins = tempDir.resolve("plugins")
        Files.createDirectories(plugins.resolve("Foo"))
        val link = plugins.resolve("Foo/escape")
        val created = runCatching { Files.createSymbolicLink(link, outside) }.isSuccess
        // Windows 非管理员态无法建符号链接：跳过而不是假通过。
        assumeTrue(created, "当前环境不支持创建符号链接，跳过该用例")
        // 再验一道：该环境是否真的**解析**符号链接。某些 Windows 配置能建链接，
        // 但 canonicalFile 不跟随，此时这条规则无法在此环境上被验证——跳过而不是假失败。
        assumeTrue(
            runCatching { File(link.toFile(), "secret.yml").canonicalFile }.getOrNull()?.canonicalPath == secret.toFile().canonicalPath,
            "当前环境的 canonicalPath 不解析符号链接，跳过该用例",
        )

        assertNull(
            ReloadPathGuard.normalize("plugins/Foo/escape/secret.yml", tempDir.toFile()),
            "canonical 后落在工作目录外 → 必须判定越界",
        )
    }

    /** 指向 plugins 内部的符号链接不算逃逸。 */
    @Test
    fun `指向plugins内部的符号链接仍合法`() {
        val plugins = tempDir.resolve("plugins")
        Files.createDirectories(plugins.resolve("Real"))
        // 同上：目标文件真实存在，Windows 才会在 canonical 时跟随链接。
        val realConfig = plugins.resolve("Real/config.yml")
        Files.write(realConfig, ByteArray(0))
        val link = plugins.resolve("Alias")
        val created = runCatching { Files.createSymbolicLink(link, plugins.resolve("Real")) }.isSuccess
        assumeTrue(created, "当前环境不支持创建符号链接，跳过该用例")
        assumeTrue(
            runCatching { File(link.toFile(), "config.yml").canonicalFile }.getOrNull()?.canonicalPath == realConfig.toFile().canonicalPath,
            "当前环境的 canonicalPath 不解析符号链接，跳过该用例",
        )

        assertEquals("plugins/Alias/config.yml", ReloadPathGuard.normalize("plugins/Alias/config.yml", tempDir.toFile()))
    }
}
