package top.wcpe.mc.plugin.serverprobe.core.control

/**
 * 探针运行模式（协议规格 §3.5 的启动三态判定结果）。
 *
 * 只有两态落在这里:三态判定的"存在但解析失败"分支**归入** [STANDALONE](降级独立模式 + WARN),
 * 区别只体现在日志上,不影响运行模式。
 */
enum class ProbeMode {

    /** 独立模式:全部行为由本地 `config.yml` 决定(与未引入托管能力前完全一致)。 */
    STANDALONE,

    /** 托管模式:身份文件存在且合法,配置真源在外部控制面。 */
    MANAGED,
}
