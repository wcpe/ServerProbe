package top.wcpe.mc.plugin.serverprobe.core.config

/**
 * 一次 `config_push` 的处理结果(协议规格 §3.4.2 的 `config_applied`)。
 *
 * @property result 结果码:[RESULT_APPLIED] / [RESULT_FAILED] / [RESULT_REJECTED] / [RESULT_CONFLICT]。
 * @property configVersion 本次**被处理的那一版**(即控制面下发的版本),不是探针当前版本——
 *   控制面据此判断"我发的 7 版到底怎么了"。
 * @property effective 处理后**内存里真实生效的值**([ManagedConfigStore.currentValues]);失败时是回退后的值,
 *   不是"我收到了什么"。
 * @property selfCheck 自检结果;被拒(`rejected`/`conflict`)时为 null——那一版根本没进入自检环节,
 *   填一份全 false 的自检会与"自检失败"混淆。
 * @property error 失败/被拒的中文原因;成功时为 null(缓存落盘失败等"已生效但需知会"的情况除外)。
 * @property appliedAt 生效时刻(ISO-8601 UTC,秒精度);未生效时为 null。
 */
internal class ConfigPushOutcome(
    val result: String,
    val configVersion: Long,
    val effective: Map<String, Any?>,
    val selfCheck: SelfCheckResult?,
    val error: String?,
    val appliedAt: String?,
) {

    /** 是否成功生效。 */
    val applied: Boolean
        get() = result == RESULT_APPLIED

    companion object {

        /** 已生效。 */
        const val RESULT_APPLIED = "applied"

        /** 已应用但自检失败,内存已回退。 */
        const val RESULT_FAILED = "failed"

        /** 整包被拒(版本陈旧 / 含禁止键 / 越界 / 未知键)。 */
        const val RESULT_REJECTED = "rejected"

        /** 并发冲突(`baseVersion` 与当前版本不符)。 */
        const val RESULT_CONFLICT = "conflict"
    }
}
