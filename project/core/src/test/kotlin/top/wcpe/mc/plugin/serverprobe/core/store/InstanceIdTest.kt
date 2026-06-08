package top.wcpe.mc.plugin.serverprobe.core.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * [InstanceId] 单元测试。
 *
 * 经内部 [InstanceId.resolveAt] 注入临时数据目录,绕开平台 `getDataFolder`,即可纯逻辑验证:
 * 配置名非空白优先采用;配置空白时生成稳定 ID 并落盘,二次调用复用同一 ID(跨重启稳定)。
 */
class InstanceIdTest {

    /** 配置名非空白时直接采用,不读写磁盘。 */
    @Test
    fun `配置非空时优先采用`() {
        val dir = Files.createTempDirectory("probe-instance-configured").toFile()

        assertEquals("my-server", InstanceId.resolveAt("my-server", dir), "配置名非空白应直接返回")
    }

    /** 配置名空白时回退自动生成。 */
    @Test
    fun `配置空白时回退生成`() {
        val dir = Files.createTempDirectory("probe-instance-blank").toFile()

        val id = InstanceId.resolveAt("   ", dir)

        assertTrue(id.isNotBlank(), "空白配置应回退生成非空标识")
    }

    /** 配置为空时生成并落盘,二次调用复用同一 ID(稳定跨重启)。 */
    @Test
    fun `配置空时生成并复用同一 ID`() {
        val dir = Files.createTempDirectory("probe-instance-reuse").toFile()

        val first = InstanceId.resolveAt(null, dir)
        val second = InstanceId.resolveAt(null, dir)

        assertTrue(first.isNotBlank(), "首次应生成非空标识")
        assertEquals(first, second, "二次调用应复用同一持久化标识")
    }
}
