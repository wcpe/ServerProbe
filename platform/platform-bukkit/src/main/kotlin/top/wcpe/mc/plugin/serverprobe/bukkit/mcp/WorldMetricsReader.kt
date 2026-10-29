package top.wcpe.mc.plugin.serverprobe.bukkit.mcp

import top.wcpe.mc.plugin.serverprobe.api.model.metrics.WorldMetrics
import top.wcpe.mc.plugin.serverprobe.bukkit.collector.BukkitWorldCollector

/**
 * 世界指标缓存的读取接缝。
 *
 * <h2>为什么需要这个接口</h2>
 * <p>[BukkitWorldDetailProvider] 的全部行为就是“读采集器缓存、映射成明细”，
 * 但它的依赖 [BukkitWorldCollector] 是具体类且依赖 Bukkit 运行时，
 * 在单元测试里无法构造——于是那个类的测试只能去**反射查字段类型**，
 * 而那只能证明“字段声明是这个类型”，证明不了“真的在透传数据”。</p>
 *
 * <p>引入本接口后，测试可以传一个固定数据的假实现，直接断言
 * `details()` 返回的 `entityTypeCounts` / `regionStats` 是否正确。</p>
 *
 * <h2>为什么不直接依赖 `WorldMetricsCollectorApi`</h2>
 * <p>该接口在 `api` 模块，但这个 provider 只需要其中一个方法（`collect`），
 * 而 `WorldMetricsCollectorApi` 还包含注册/生命周期语义，拿它当接缝会把测试
 * 绑到无关的契约上。保持最小接口。</p>
 */
fun interface WorldMetricsReader {

    /**
     * 读最近一次世界采样结果缓存。
     *
     * @return 世界指标列表；未采样时为空列表（不返回 null）
     */
    fun collect(): List<WorldMetrics>
}

/** 把真实采集器适配成读取接缝。 */
fun BukkitWorldCollector.asWorldMetricsReader(): WorldMetricsReader = WorldMetricsReader { this.collect() }
