package top.wcpe.mc.plugin.serverprobe.core.store

import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStore
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStoreRegistration
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.atomic.AtomicReference

/**
 * 可切换的指标存储(FR8.2)。
 *
 * 未安装第三方实现时始终委派 [LocalFileMetricStore]；安装期间所有读取和写入走同一个替换实例。
 * 替换引用以原子方式切换，避免采集线程与第三方插件卸载并发时读到半更新状态。
 */
@Service
class SwitchingMetricStore : MetricStore {

    private val defaultStore: MetricStore
    private val replacement = AtomicReference<MetricStore?>(null)

    constructor() : this(LocalFileMetricStore())

    internal constructor(defaultStore: MetricStore) {
        this.defaultStore = defaultStore
    }

    /** 安装唯一的第三方存储，重复安装明确拒绝。 */
    fun install(store: MetricStore): MetricStoreRegistration {
        require(store !== this) { "第三方存储不能引用切换存储自身" }
        check(replacement.compareAndSet(null, store)) { "已有第三方存储正在使用" }
        return Registration(store)
    }

    override fun saveStartupProfile(profile: StartupProfile) = current().saveStartupProfile(profile)

    override fun lastStartupProfile(): StartupProfile? = current().lastStartupProfile()

    override fun appendHistory(snapshot: MetricSnapshot) = current().appendHistory(snapshot)

    override fun readStartupProfiles(limit: Int): List<StartupProfile> = current().readStartupProfiles(limit)

    override fun readHistory(sinceMs: Long, untilMs: Long, limit: Int): List<MetricSnapshot> =
        current().readHistory(sinceMs, untilMs, limit)

    override fun appendHistory(snapshots: List<MetricSnapshot>) = current().appendHistory(snapshots)

    /** 读取当前生效实例；卸载后立即回退默认本地文件实现。 */
    private fun current(): MetricStore = replacement.get() ?: defaultStore

    /** 句柄只撤销自己成功安装的实例，避免旧句柄影响后续安装。 */
    private inner class Registration(private val installed: MetricStore) : MetricStoreRegistration {

        override fun close() {
            replacement.compareAndSet(installed, null)
        }
    }
}
