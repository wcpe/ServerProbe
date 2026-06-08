package top.wcpe.mc.plugin.serverprobe.core.registry

import taboolib.common.platform.function.warning
import top.wcpe.mc.plugin.serverprobe.api.collector.JvmMetricsCollector
import top.wcpe.mc.plugin.serverprobe.api.collector.ProxyMetricsCollector
import top.wcpe.mc.plugin.serverprobe.api.collector.ServerMetricsCollector
import top.wcpe.mc.plugin.serverprobe.api.sampler.ServerTickSampler
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 探针组件注册中心。
 *
 * 在 core 与各平台模块之间充当解耦的装配点:core 不在编译期依赖任何平台实现,
 * 平台实现在运行时(各自就绪后)调用本中心的 `register` 完成自注册,从而实现服务发现。
 *
 * 持有四类组件:
 * - [JvmMetricsCollector]:JVM 指标采集器,P4(core 内通用实现)就绪后自注册;
 * - [ServerMetricsCollector]:服务器指标采集器,P5(platform-bukkit)就绪后自注册;
 * - [ServerTickSampler]:tick 采样器,P5(platform-bukkit)就绪后自注册;
 * - [ProxyMetricsCollector]:代理端指标采集器,P9(platform-bungee)就绪后自注册。
 *
 * 并发模型:注册多发生于启动期、读取贯穿运行期且跨线程(编排任务为异步线程),
 * 读多写少,故底层用 [CopyOnWriteArrayList] 保证读无锁、写安全。
 */
@Service
class ProbeRegistry {

    /** JVM 指标采集器集合。 */
    private val jvmCollectorList = CopyOnWriteArrayList<JvmMetricsCollector>()

    /** 服务器指标采集器集合。 */
    private val serverCollectorList = CopyOnWriteArrayList<ServerMetricsCollector>()

    /** tick 采样器集合。 */
    private val tickSamplerList = CopyOnWriteArrayList<ServerTickSampler>()

    /** 代理端指标采集器集合。 */
    private val proxyCollectorList = CopyOnWriteArrayList<ProxyMetricsCollector>()

    /**
     * 注册一个 JVM 指标采集器;重复实例会被忽略。
     *
     * @param collector 待注册的采集器。
     */
    fun register(collector: JvmMetricsCollector) {
        if (!jvmCollectorList.addIfAbsent(collector)) {
            warning("重复注册 JVM 指标采集器,已忽略:${collector.javaClass.name}")
        }
    }

    /**
     * 注册一个服务器指标采集器;重复实例会被忽略。
     *
     * @param collector 待注册的采集器。
     */
    fun register(collector: ServerMetricsCollector) {
        if (!serverCollectorList.addIfAbsent(collector)) {
            warning("重复注册服务器指标采集器,已忽略:${collector.javaClass.name}")
        }
    }

    /**
     * 注册一个 tick 采样器;重复实例会被忽略。
     *
     * @param sampler 待注册的采样器。
     */
    fun register(sampler: ServerTickSampler) {
        if (!tickSamplerList.addIfAbsent(sampler)) {
            warning("重复注册 tick 采样器,已忽略:${sampler.javaClass.name}")
        }
    }

    /**
     * 注册一个代理端指标采集器;重复实例会被忽略。
     *
     * @param collector 待注册的采集器。
     */
    fun register(collector: ProxyMetricsCollector) {
        if (!proxyCollectorList.addIfAbsent(collector)) {
            warning("重复注册代理端指标采集器,已忽略:${collector.javaClass.name}")
        }
    }

    /**
     * 已注册的 JVM 指标采集器只读视图。
     *
     * @return 当前已注册采集器的不可变快照副本。
     */
    val jvmCollectors: List<JvmMetricsCollector>
        get() = jvmCollectorList.toList()

    /**
     * 已注册的服务器指标采集器只读视图。
     *
     * @return 当前已注册采集器的不可变快照副本。
     */
    val serverCollectors: List<ServerMetricsCollector>
        get() = serverCollectorList.toList()

    /**
     * 已注册的 tick 采样器只读视图。
     *
     * @return 当前已注册采样器的不可变快照副本。
     */
    val tickSamplers: List<ServerTickSampler>
        get() = tickSamplerList.toList()

    /**
     * 已注册的代理端指标采集器只读视图。
     *
     * @return 当前已注册采集器的不可变快照副本。
     */
    val proxyCollectors: List<ProxyMetricsCollector>
        get() = proxyCollectorList.toList()
}
