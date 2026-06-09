package top.wcpe.mc.plugin.serverprobe.core.alert

import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot

/**
 * 告警类型(FR5)。
 *
 * 规则种类有限且已知,故以**枚举多态**驱动告警判定:每个常量自封装两件事——
 * - [extract]:从一份 [MetricSnapshot] 取出该类型关心的观测值(不可用时返回 null,表示该项 N/A);
 * - [violated]:给定观测值与阈值,判断是否越线。
 *
 * 如此把"取什么值、怎么比"内聚在类型自身,告警引擎无需对类型做任何 `if-else`/`switch` 分发
 * (规范第 6/10 条:以枚举多态消灭类型判断),新增类型只需新增常量。
 *
 * 取值约定与各指标模型一致:代理端 [MetricSnapshot.server] 为 null(无 TPS/MSPT 概念),
 * Folia 下 TPS/MSPT 字段为 null,堆上限缺失时占用率不可计算——这些情形 [extract] 一律返回 null。
 */
enum class AlertType {

    /**
     * TPS 过低:观测最近 1 分钟平均 TPS,低于阈值即越线。
     *
     * 代理端无服务器维度、Folia 无全局 TPS 时取值为 null(N/A)。
     */
    TPS_LOW {
        override fun extract(snapshot: MetricSnapshot): Double? = snapshot.server?.tick?.tps1m
        override fun violated(value: Double, threshold: Double): Boolean = value < threshold
    },

    /**
     * MSPT 过高:观测 MSPT p95 分位,高于阈值即越线。
     *
     * 代理端无服务器维度、Folia 无全局 MSPT 时取值为 null(N/A)。
     */
    MSPT_HIGH {
        override fun extract(snapshot: MetricSnapshot): Double? = snapshot.server?.tick?.msptP95
        override fun violated(value: Double, threshold: Double): Boolean = value > threshold
    },

    /**
     * 堆内存占用率过高:观测堆已用 / 堆上限的百分比(0–100),高于阈值即越线。
     *
     * 堆无上限(`heapMaxBytes <= 0`,JVM 约定 -1 表示无上限)时占用率不可计算,取值为 null(N/A)。
     */
    HEAP_USAGE_HIGH {
        override fun extract(snapshot: MetricSnapshot): Double? {
            val jvm = snapshot.jvm
            return if (jvm.heapMaxBytes > 0) jvm.heapUsedBytes * PERCENT_SCALE / jvm.heapMaxBytes else null
        }

        override fun violated(value: Double, threshold: Double): Boolean = value > threshold
    },

    /**
     * 死锁:观测死锁线程数,大于阈值即越线。
     *
     * 属事件型——阈值语义为"个数下限",通常配 `threshold = 0`(出现任一死锁线程即触发);
     * 死锁数恒有值(无死锁归一为 0),故取值不为 null。
     */
    DEADLOCK {
        override fun extract(snapshot: MetricSnapshot): Double? = snapshot.jvm.deadlockedThreadCount.toDouble()
        override fun violated(value: Double, threshold: Double): Boolean = value > threshold
    };

    /**
     * 从快照中取出本类型关心的观测值。
     *
     * @param snapshot 指标快照。
     * @return 观测值;该项不可用(N/A,如代理端无服务器维度、Folia 无 TPS、堆无上限)时为 null。
     */
    abstract fun extract(snapshot: MetricSnapshot): Double?

    /**
     * 判断观测值相对阈值是否越线(违规)。
     *
     * @param value 观测值(由 [extract] 取得,非空)。
     * @param threshold 规则阈值。
     * @return 越线返回 true。
     */
    abstract fun violated(value: Double, threshold: Double): Boolean

    private companion object {

        /** 百分比换算因子:占用率以 0–100 表达。 */
        private const val PERCENT_SCALE = 100.0
    }
}
