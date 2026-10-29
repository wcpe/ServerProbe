package top.wcpe.mc.plugin.serverprobe.core.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.model.metrics.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.startup.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStore

/** 存储扩展切换测试:验证第三方替换、拒绝重复安装及卸载回退。 */
class SwitchingMetricStoreTest {

    /** 安装第三方存储后读写应完整委派，关闭注册后回退默认实现。 */
    @Test
    fun `安装第三方存储后委派且关闭注册后回退默认实现`() {
        val defaultStore = RecordingStore()
        val replacementStore = RecordingStore()
        val store = SwitchingMetricStore(defaultStore)

        store.readStartupProfiles(1)
        val registration = store.install(replacementStore)
        store.readStartupProfiles(1)
        registration.close()
        store.readStartupProfiles(1)

        assertEquals(2, defaultStore.readProfileCalls, "安装前与卸载后应走默认存储")
        assertEquals(1, replacementStore.readProfileCalls, "安装期间应走第三方存储")
    }

    /** 同一生命周期只允许一个第三方存储，重复安装必须明确失败。 */
    @Test
    fun `重复安装第三方存储明确失败`() {
        val store = SwitchingMetricStore(RecordingStore())
        store.install(RecordingStore())

        val error = assertThrows(IllegalStateException::class.java) {
            store.install(RecordingStore())
        }

        assertEquals("已有第三方存储正在使用", error.message)
    }

    /** 已关闭的旧注册不得卸载后续安装的第三方存储。 */
    @Test
    fun `旧注册关闭不影响后续安装`() {
        val store = SwitchingMetricStore(RecordingStore())
        val first = store.install(RecordingStore())
        first.close()
        val secondStore = RecordingStore()
        store.install(secondStore)

        first.close()
        store.readStartupProfiles(1)

        assertEquals(1, secondStore.readProfileCalls, "旧注册重复关闭不得回退后续替换")
    }

    /** 单侧区间读取必须直接委派当前生效实例，不能经由切换层自己的 readHistory 转发。 */
    @Test
    fun `单侧区间读取直接委派当前实例`() {
        val defaultStore = RecordingStore()
        val replacementStore = RecordingStore()
        val store = SwitchingMetricStore(defaultStore)

        val registration = store.install(replacementStore)
        store.readHistorySince(1_000L, 3)
        store.readHistoryUntil(2_000L, 4)
        registration.close()
        store.readHistorySince(3_000L, 5)
        store.readHistoryUntil(4_000L, 6)

        assertEquals(1, replacementStore.readHistorySinceCalls, "安装期间 readHistorySince 应走第三方存储")
        assertEquals(1, replacementStore.readHistoryUntilCalls, "安装期间 readHistoryUntil 应走第三方存储")
        assertEquals(1, defaultStore.readHistorySinceCalls, "卸载后 readHistorySince 应回退默认存储")
        assertEquals(1, defaultStore.readHistoryUntilCalls, "卸载后 readHistoryUntil 应回退默认存储")
        assertEquals(0, replacementStore.readHistoryCalls, "单侧读取不得退化为切换层 readHistory 再转发")
        assertEquals(0, defaultStore.readHistoryCalls, "单侧读取不得退化为切换层 readHistory 再转发")
    }

    /** 仅记录委派次数的存储桩，避免依赖运行期 JSON 实现。 */
    private class RecordingStore : MetricStore {

        var readProfileCalls = 0
        var readHistoryCalls = 0
        var readHistorySinceCalls = 0
        var readHistoryUntilCalls = 0

        override fun saveStartupProfile(profile: StartupProfile) = Unit

        override fun lastStartupProfile(): StartupProfile? = null

        override fun appendHistory(snapshot: MetricSnapshot) = Unit

        override fun readStartupProfiles(limit: Int): List<StartupProfile> {
            readProfileCalls++
            return emptyList()
        }

        override fun readHistory(sinceMs: Long, untilMs: Long, limit: Int): List<MetricSnapshot> {
            readHistoryCalls++
            return emptyList()
        }

        override fun readHistorySince(sinceMs: Long, limit: Int): List<MetricSnapshot> {
            readHistorySinceCalls++
            return emptyList()
        }

        override fun readHistoryUntil(untilMs: Long, limit: Int): List<MetricSnapshot> {
            readHistoryUntilCalls++
            return emptyList()
        }
    }
}
