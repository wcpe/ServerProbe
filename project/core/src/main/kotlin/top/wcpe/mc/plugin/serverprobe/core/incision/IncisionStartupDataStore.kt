package top.wcpe.mc.plugin.serverprobe.core.incision

import top.wcpe.mc.plugin.serverprobe.api.model.PluginTiming
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Incision 启动期数据缓冲(FR7)。
 *
 * 织入回调可能与启动画像的异步装配并发发生,因此以并发队列保存样本,
 * 以易变状态位发布开关与织入结果。该服务只保存平台无关 DTO,不依赖 Bukkit。
 */
@Service
class IncisionStartupDataStore {

    private val pluginEnableTimings = ConcurrentLinkedQueue<PluginTiming>()

    @Volatile
    private var enabled = false

    @Volatile
    private var active = false

    /** 记录配置已请求启用 Incision。 */
    fun markEnabled() {
        enabled = true
    }

    /** 标记目标切点已实际执行并开始采集。 */
    fun markActive() {
        active = true
    }

    /** 在织入回调中记录一次逐插件 onEnable 耗时。 */
    fun recordPluginEnable(pluginName: String, durationNanos: Long) {
        if (!active) {
            return
        }
        pluginEnableTimings += PluginTiming.builder()
            .name(pluginName)
            .enableMs(TimeUnit.NANOSECONDS.toMillis(durationNanos))
            .build()
    }

    /** 定格当前数据供启动画像装配。 */
    fun snapshot(): IncisionStartupData = IncisionStartupData(
        enabled = enabled,
        active = active,
        pluginEnableTimings = pluginEnableTimings.toList()
    )
}
