package top.wcpe.mc.plugin.serverprobe.core.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * [AtomicJsonWriter] 单元测试。
 *
 * [AtomicJsonWriter] 为纯 NIO 文件读写(写临时文件 → 原子重命名),不依赖任何平台/框架,可直接验证。
 * 覆盖:写后读相等、覆盖写取最新、自动建父目录、读不存在返回 null。所有用例在 [Files.createTempDirectory]
 * 隔离的临时目录内进行,互不干扰。
 */
class AtomicJsonWriterTest {

    /** 写入后读取应得到相同内容,且自动创建不存在的父目录。 */
    @Test
    fun `写后读相等且自动建父目录`() {
        val dir = Files.createTempDirectory("probe-atomic-write")
        // 目标位于尚不存在的多级子目录,验证父目录被自动创建
        val target = dir.resolve("startup/latest.json")
        val content = "{\"k\":\"值-中文\",\"n\":123}"

        AtomicJsonWriter.writeAtomic(target, content)

        assertEquals(content, AtomicJsonWriter.readText(target), "读回内容应与写入一致")
    }

    /** 二次写入应覆盖首次内容,读取得到最新值。 */
    @Test
    fun `覆盖写取最新`() {
        val dir = Files.createTempDirectory("probe-atomic-overwrite")
        val target = dir.resolve("data.json")

        AtomicJsonWriter.writeAtomic(target, "first")
        AtomicJsonWriter.writeAtomic(target, "second")

        assertEquals("second", AtomicJsonWriter.readText(target), "覆盖写后应读到最新内容")
    }

    /** 读取不存在的文件应返回 null。 */
    @Test
    fun `读不存在返回 null`() {
        val dir = Files.createTempDirectory("probe-atomic-absent")
        val absent = dir.resolve("nope.json")

        assertNull(AtomicJsonWriter.readText(absent), "文件不存在应返回 null")
    }
}
