package top.wcpe.mc.plugin.serverprobe.core.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * [IdentityFileStore] / [AtomicFileWrite] 单元测试（协议规格 §3.2 的写入与半写防御约束）。
 *
 * 覆盖：原子写后读回一致、不残留临时文件、内容相同不重写、文件不存在/截断/超限的降级，
 * 以及 POSIX 平台上的 0600 权限（Windows 跳过该断言，权限收紧为尽力而为）。
 */
class IdentityFileStoreTest {

    /** 原子写入后读回逐字段一致，且不残留 `.tmp` 文件。 */
    @Test
    fun `原子写后读回一致且无临时文件残留`() {
        val dir = Files.createTempDirectory("probe-identity-write")
        val target = dir.resolve("agent-identity.json")

        assertTrue(IdentityFileStore.write(target, sample()), "首次写入应发生")

        val loaded = IdentityFileStore.read(target)
        assertTrue(loaded is IdentityLoadResult.Loaded, "写入后应能读回，实际:$loaded")
        assertEquals(sample(), (loaded as IdentityLoadResult.Loaded).identity)
        assertFalse(Files.exists(dir.resolve("agent-identity.json.tmp")), "不得残留临时文件")
    }

    /** 内容相同则跳过写入（迁移幂等要求"内容相同不重写"）。 */
    @Test
    fun `内容相同不重复写入`() {
        val dir = Files.createTempDirectory("probe-identity-idempotent")
        val target = dir.resolve("agent-identity.json")
        val identity = sample()

        assertTrue(IdentityFileStore.write(target, identity), "首次写入应发生")
        val firstModified = Files.getLastModifiedTime(target)

        assertFalse(IdentityFileStore.write(target, identity), "内容相同应跳过写入")
        assertEquals(firstModified, Files.getLastModifiedTime(target), "跳过写入时不应触碰文件")
    }

    /** 文件不存在 = 未托管（不是错误）。 */
    @Test
    fun `文件不存在返回 Absent`() {
        val dir = Files.createTempDirectory("probe-identity-absent")

        assertEquals(IdentityLoadResult.Absent, IdentityFileStore.read(dir.resolve("agent-identity.json")))
    }

    /** 半写/截断文件 = 视为不存在，但绝不抛异常。 */
    @Test
    fun `截断文件降级为不可用且不抛异常`() {
        val dir = Files.createTempDirectory("probe-identity-truncated")
        val target = dir.resolve("agent-identity.json")
        Files.write(target, """{"schemaVersion":1,"provider":"p","endpo""".toByteArray(Charsets.UTF_8))

        val result = IdentityFileStore.read(target)

        assertTrue(result is IdentityLoadResult.Unusable, "截断文件应判为不可用，实际:$result")
        assertEquals(IdentityUnusableReason.MALFORMED, (result as IdentityLoadResult.Unusable).reason)
    }

    /** 超大文件（疑似被写入垃圾）= 不可用，且不把整份文件读进内存。 */
    @Test
    fun `超限文件判定为不可用`() {
        val dir = Files.createTempDirectory("probe-identity-oversize")
        val target = dir.resolve("agent-identity.json")
        Files.write(target, ByteArray(65 * 1024) { 'a'.code.toByte() })

        val result = IdentityFileStore.read(target)

        assertTrue(result is IdentityLoadResult.Unusable, "超限文件应判为不可用，实际:$result")
        assertEquals(IdentityUnusableReason.MALFORMED, (result as IdentityLoadResult.Unusable).reason)
    }

    /** POSIX 平台：身份文件权限必须是 0600（凭证文件不得被其他用户读取）。 */
    @Test
    fun `POSIX 平台权限收紧为 0600`() {
        val dir = Files.createTempDirectory("probe-identity-perm")
        assumeTrue(supportsPosix(dir), "当前文件系统不支持 POSIX 权限，跳过权限断言")

        val target = dir.resolve("agent-identity.json")
        IdentityFileStore.write(target, sample())

        val permissions = Files.getPosixFilePermissions(target)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions)
    }

    /** 当前文件系统是否支持 POSIX 权限（Windows 为 false）。 */
    private fun supportsPosix(path: Path): Boolean =
        runCatching { Files.getFileStore(path).supportsFileAttributeView("posix") }.getOrDefault(false)

    /** 构造一份合法身份样本。 */
    private fun sample(): ControlPlaneIdentity = ControlPlaneIdentity(
        schemaVersion = ControlPlaneIdentity.SUPPORTED_SCHEMA_VERSION,
        provider = "sample-vendor",
        endpoint = "ws://127.0.0.1:31001/ws/plugin-bridge",
        instanceId = "0b6f-instance",
        credential = "opaque-credential",
        capabilities = listOf("config_push"),
        createdAt = "2026-10-19T03:12:44Z",
    )
}
