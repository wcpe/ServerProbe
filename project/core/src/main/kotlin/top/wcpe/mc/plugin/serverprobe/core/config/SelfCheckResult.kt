package top.wcpe.mc.plugin.serverprobe.core.config

/**
 * 配置应用后的自检三项结果(协议规格 §3.4.2 的 `selfCheck`)。
 *
 * 三项都是**可判定的真实探针状态**,不是恒真的空壳——自检的意义正是"发下去的东西真的能用",
 * 若某一项写成常量,回退机制就永远不触发,等于把控制面的对账建立在谎报上。
 *
 * @property collectorAlive 指标采集服务是否在跑(见 [top.wcpe.mc.plugin.serverprobe.core.orchestrator.MetricOrchestrator])。
 * @property metricProducible 是否能取到一份非空指标快照。
 * @property bridgeUsable 当前桥上是否可发帧(上行通道已就绪)。
 */
internal class SelfCheckResult(
    val collectorAlive: Boolean,
    val metricProducible: Boolean,
    val bridgeUsable: Boolean,
) {

    /** 三项是否全通过。 */
    val passed: Boolean
        get() = collectorAlive && metricProducible && bridgeUsable

    /** 帧内对象形态(键名与协议规格 §3.4.2 一致)。 */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "collectorAlive" to collectorAlive,
        "metricProducible" to metricProducible,
        "bridgeUsable" to bridgeUsable,
    )

    /**
     * 失败项的中文说明(拼进 `config_applied` 的 `error`,让运维一眼看出是哪一项没过)。
     *
     * @return 全通过时为空串。
     */
    fun failureText(): String = listOfNotNull(
        if (collectorAlive) null else "指标采集未在运行",
        if (metricProducible) null else "取不到非空指标快照",
        if (bridgeUsable) null else "插件桥上行通道不可用",
    ).joinToString("；")

    companion object {

        /** 全部失败:自检过程本身抛异常时使用(宁可判失败回退,也不放行一份未经校验的配置)。 */
        val FAILED: SelfCheckResult = SelfCheckResult(
            collectorAlive = false,
            metricProducible = false,
            bridgeUsable = false,
        )
    }
}
