package top.wcpe.mc.plugin.serverprobe.core.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStore

/** 存储扩展切换测试(FR8.2):验证第三方替换、拒绝重复安装及卸载回退。 */
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

    /** 仅记录委派次数的存储桩，避免依赖运行期 JSON 实现。 */
    private class RecordingStore : MetricStore {

        var readProfileCalls = 0

        override fun saveStartupProfile(profile: StartupProfile) = Unit

        override fun lastStartupProfile(): StartupProfile? = null

        override fun appendHistory(snapshot: MetricSnapshot) = Unit

        override fun readStartupProfiles(limit: Int): List<StartupProfile> {
            readProfileCalls++
            return emptyList()
        }
    }
}
