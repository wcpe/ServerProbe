package top.wcpe.mc.plugin.serverprobe.core.cpu

import taboolib.common.platform.function.submit
import taboolib.common.platform.service.PlatformExecutor
import top.wcpe.mc.plugin.serverprobe.api.model.PluginCpuMetric
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 运行期 CPU 采样归因(FR2.6,M3,P2)。
 *
 * 周期在异步线程对全部线程栈采样(`ThreadMXBean.dumpAllThreads`),把每个栈帧的类名
 * 经 [PluginClassLoaderRegistry] 归并到插件,得到"各插件样本计数"作为运行期 CPU 占用近似。
 * 这是**轻量采样归因**(spark 模式、无 agent),不替代 spark 火焰图,仅回答"哪个插件运行期
 * 更吃线程"。
 *
 * 聚合口径:维护最近 [ProbeConfig.cpuWindowRounds] 轮的环形计数,输出窗口内各插件累计
 * 样本数与占比([PluginCpuMetric])。
 *
 * 生命周期:作为 IOC [Service],[start] 在 [PostEnable](所有 Bean 就绪)启动周期任务,
 * [stop] 在 [PreDestroy] 取消。**默认关闭**([ProbeConfig.cpuEnabled] 为 false 时[start] 直接返回,
 * 零采样开销)——P2 增强,默认不采样。
 *
 * 线程安全:采样任务在单线程执行,计数用 [ConcurrentHashMap] + [AtomicLong] 兜底并发读。
 */
@Service
class CpuAttributionSampler {

    /** 插件 ClassLoader 注册表,用于栈帧归并。 */
    @Inject
    lateinit var registry: PluginClassLoaderRegistry

    /** 定时采样任务句柄;未启动或已停止时为 null。 */
    private var task: PlatformExecutor.PlatformTask? = null

    /** 插件名 → 窗口内累计样本数。 */
    private val pluginCounts = ConcurrentHashMap<String, AtomicLong>()

    /** 窗口内累计总样本数。 */
    private val totalSamples = AtomicLong(0)

    /** 环形窗口:每轮各插件本轮样本数(用于滑动淘汰最旧一轮)。 */
    private val windowRounds = ArrayDeque<Map<String, Long>>()

    /** 是否已启动(避免重复启停)。 */
    private var started = false

    /**
     * 在所有 Bean 就绪后启动周期采样(默认关闭时直接跳过)。
     */
    @PostEnable
    fun start() {
        if (started) return
        started = true
        if (!ProbeConfig.cpuEnabled()) {
            ProbeLogger.info("运行期 CPU 归因未启用(cpu.enabled=false),跳过采样")
            return
        }
        val window = ProbeConfig.cpuWindowRounds()
        val period = ProbeConfig.cpuSamplePeriodTicks()
        task = submit(period = period, async = true) {
            runCatching { sample() }
                .onFailure { ProbeLogger.error("CPU 采样失败", it) }
        }
        ProbeLogger.info("已启动运行期 CPU 采样(周期 ${period} ticks,窗口 $window 轮)")
    }

    /**
     * 停止采样任务。
     */
    @PreDestroy
    fun stop() {
        task?.cancel()
    }

    /**
     * 采集当前窗口聚合结果。
     *
     * @param limit 返回的 Top-N 条数。
     * @return 各插件样本计数与占比(按样本数降序);无任何样本时为空列表。
     */
    fun snapshot(limit: Int): List<PluginCpuMetric> {
        val total = totalSamples.get()
        if (total <= 0) return emptyList()
        return pluginCounts.entries
            .sortedByDescending { it.value.get() }
            .take(limit.coerceAtLeast(1))
            .map { (plugin, count) ->
                val n = count.get()
                PluginCpuMetric.builder()
                    .plugin(plugin)
                    .sampleCount(n)
                    .percent(round1(n * 100.0 / total))
                    .build()
            }
    }

    /**
     * 执行一轮采样:dump 全部线程栈,逐帧归并计数,并滑动淘汰窗口最旧一轮。
     *
     * 单轮失败(如 JDK 不支持 dumpAllThreads)被上层 runCatching 兜底,不影响后续轮。
     */
    private fun sample() {
        val round = HashMap<String, Long>()
        val threadInfos = ManagementFactory.getThreadMXBean().dumpAllThreads(false, false)
        for (info in threadInfos) {
            val frames = info.stackTrace
            // 每线程至多取前 40 帧,避免深栈稀释归并焦点
            val limit = minOf(frames.size, MAX_FRAMES_PER_THREAD)
            for (i in 0 until limit) {
                val owner = registry.ownerOf(frames[i].className)
                if (owner != null) {
                    round.merge(owner, 1L, Long::plus)
                }
            }
        }
        if (round.isEmpty()) return

        // 窗口滑动:本轮入窗,超窗淘汰最旧一轮,同步更新累计计数
        synchronized(windowRounds) {
            windowRounds.addLast(round)
            if (windowRounds.size > ProbeConfig.cpuWindowRounds()) {
                val oldest = windowRounds.removeFirst()
                oldest.forEach { (plugin, n) ->
                    pluginCounts.getOrPut(plugin) { AtomicLong(0) }.addAndGet(-n)
                }
            }
            var roundTotal = 0L
            round.forEach { (plugin, n) ->
                pluginCounts.getOrPut(plugin) { AtomicLong(0) }.addAndGet(n)
                roundTotal += n
            }
            totalSamples.addAndGet(roundTotal)
        }
    }

    /**
     * 保留一位小数的百分比(用于展示)。
     *
     * @param value 原始百分比(0.0–100.0)。
     * @return 四舍五入到一位小数的百分比。
     */
    private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

    private companion object {

        /** 每线程最多参与归并的栈帧数(控制单轮开销)。 */
        private const val MAX_FRAMES_PER_THREAD = 40
    }
}
