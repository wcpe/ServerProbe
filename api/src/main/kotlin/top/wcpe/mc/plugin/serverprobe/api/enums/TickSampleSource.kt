package top.wcpe.mc.plugin.serverprobe.api.enums

/**
 * TPS/MSPT 采样来源。
 *
 * 标识一份 tick 采样数据是通过哪种途径获取的,用于呈现层说明数据可信度与兼容性背景
 * (TPS/MSPT 是唯一需要多版本/多平台兼容处理的关键指标)。
 *
 * - [PAPER_API]:经 Paper 提供的官方 API 获取(如 `getTPS()`)。
 * - [NMS_RECENT_TPS]:经 NMS 反射读取服务端内部最近 TPS(无 Paper API 的版本/纯 CraftBukkit 兜底)。
 * - [SELF_SAMPLING]:由探针自建 tick 采样器统计得出。
 * - [UNAVAILABLE]:无法采集全局值(如 Folia 无全局 TPS,per-region 语义;此时各 TPS/MSPT 字段为 N/A)。
 */
enum class TickSampleSource {
    /** 经 Paper 官方 API 获取。 */
    PAPER_API,

    /** 经 NMS 反射读取服务端内部最近 TPS。 */
    NMS_RECENT_TPS,

    /** 由探针自建 tick 采样器统计得出。 */
    SELF_SAMPLING,

    /** 无法采集全局值(如 Folia,全局标 N/A)。 */
    UNAVAILABLE
}
