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
    },

    /**
     * Old GC 频繁(FR-29):观测老年代 GC **次数速率**(次/秒),高于阈值即越线。
     *
     * 单快照只有单调累计计数,速率须跨快照差分——故本类型不实现 [extract](返回 null,普通单快照
     * 路径恒 N/A),而实现 [extractDifferential]:由告警引擎以上一次采集快照计算
     * `(count − prevCount) ÷ 秒`(与 [top.wcpe.mc.plugin.serverprobe.core.aggregator.MetricAggregator]
     * 的 GC 速率同口径);无上一次快照(首采)或计数回绕(重启,差分为负)时为 null(N/A),
     * 按引擎既有"数据缺失只清状态不误报"语义处理。
     */
    GC_OLD_HIGH {
        override fun extract(snapshot: MetricSnapshot): Double? = null

        override fun extractDifferential(current: MetricSnapshot, previous: MetricSnapshot): Double? {
            val elapsedSeconds = (current.timestampMs - previous.timestampMs) / 1000.0
            if (elapsedSeconds <= 0.0) return null
            val delta = current.jvm.gcOldCount - previous.jvm.gcOldCount
            if (delta < 0) return null
            return delta / elapsedSeconds
        }

        override fun violated(value: Double, threshold: Double): Boolean = value > threshold
    },

    /**
     * 启动超基线(FR-29):观测最近一次启动总耗时(秒),高于阈值即越线。
     *
     * 数据源为进程内最近一次启动画像(内存值,与 /probe startup 同源);无画像(代理端、首采前)
     * 时为 null(N/A)——按引擎既有"数据缺失只清状态不误报"语义,不会在无画像平台误触发。
     * 阈值单位为**秒**(如 60.0 = 启动超过 60 秒即告警),运维可直接按"本次开服耗时"理解。
     *
     * 注:wiki(Data-Output)曾宣称"启动超基线 ×1.5 倍数"口径,FR-29 落地采用**绝对秒数**——
     * 倍数需要"历史基线集合"做参照,而首启/单启场景无基线可言;绝对秒数配 config 阈值即可表达
     * "超基线"(把正常启动耗时上浮后填入),语义更直接。默认阈值实现期结合常见服状配置。
     */
    STARTUP_SLOW {
        override fun extract(snapshot: MetricSnapshot): Double? = null

        override fun extractStartup(totalMs: Long?): Double? = totalMs?.div(1000.0)

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
     * 跨快照差分取值(仅速率类类型覆盖,如 [GC_OLD_HIGH])。
     *
     * 默认返回 null(普通单快照类型不走差分路径);引擎在持有上一次采集快照时优先调用本方法,
     * 为 null 时回退 [extract]。
     *
     * @param current 当前采集快照。
     * @param previous 上一次采集快照(引擎缓存);由调用方保证不为同一快照。
     * @return 差分观测值;不可计算(首采、时间倒退、累计回绕)时为 null。
     */
    open fun extractDifferential(current: MetricSnapshot, previous: MetricSnapshot): Double? = null

    /**
     * 从启动画像总时长取值(仅 [STARTUP_SLOW] 覆盖,数据源在快照之外)。
     *
     * @param totalMs 最近一次启动画像的总时长(毫秒);无画像时为 null。
     * @return 观测值(秒);无画像时为 null。
     */
    open fun extractStartup(totalMs: Long?): Double? = null

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
