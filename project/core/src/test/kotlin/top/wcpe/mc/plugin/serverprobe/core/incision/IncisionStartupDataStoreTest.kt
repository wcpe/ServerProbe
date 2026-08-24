package top.wcpe.mc.plugin.serverprobe.core.incision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [IncisionStartupDataStore] 单元测试。 */
class IncisionStartupDataStoreTest {

    /** 默认关闭时不应产出活动织入状态或样本。 */
    @Test
    fun `默认关闭`() {
        val snapshot = IncisionStartupDataStore().snapshot()

        assertFalse(snapshot.enabled, "默认应关闭")
        assertFalse(snapshot.active, "默认不应活动")
        assertTrue(snapshot.pluginEnableTimings.isEmpty(), "默认不应有耗时样本")
    }

    /** 已激活的织入应把纳秒耗时转换为毫秒并保留插件名。 */
    @Test
    fun `激活后记录逐插件耗时`() {
        val store = IncisionStartupDataStore()
        store.markEnabled()
        store.markActive()
        store.recordPluginEnable("Alpha", 118_900_000L)

        val snapshot = store.snapshot()
        assertTrue(snapshot.enabled, "开关状态应保留")
        assertTrue(snapshot.active, "织入状态应保留")
        assertEquals(1, snapshot.pluginEnableTimings.size, "应记录一个样本")
        assertEquals("Alpha", snapshot.pluginEnableTimings.single().name, "插件名应保留")
        assertEquals(118L, snapshot.pluginEnableTimings.single().enableMs, "应按毫秒截断")
    }
}
