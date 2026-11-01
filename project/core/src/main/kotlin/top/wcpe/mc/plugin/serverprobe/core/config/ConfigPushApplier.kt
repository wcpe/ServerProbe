package top.wcpe.mc.plugin.serverprobe.core.config

import top.wcpe.mc.plugin.serverprobe.core.control.ConfigTier
import top.wcpe.mc.plugin.serverprobe.core.util.IsoInstant
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger

/**
 * `config_push` 的应用链路:版本校验 → 档位校验 → 内存替换 → 自检 → 落盘 / 回退(FR-44)。
 *
 * 状态机严格照协议规格 §3.5:
 * ```
 * 版本 <= 当前版本 ────────→ rejected(stale)      [不降级]
 * baseVersion != 当前版本 ─→ rejected(conflict)
 * 含 FORBIDDEN 键 ─────────→ rejected(forbidden)  [整包拒绝]
 * GUARDED 越界 ────────────→ rejected(out_of_range)
 * 校验通过
 *   ├─ 应用(内存原子替换两代快照)
 *   ├─ 自检三项
 *   │   ├─ 全通过 → 原子写 managed-cache → applied(effective)
 *   │   └─ 任失败 → 回退上一版 → failed(error)   [缓存**不写**]
 * ```
 *
 * ## 为什么是本类持有"回退"
 * 回退需要"应用前那对两代快照",只有本链路拿得到;故 [ManagedConfigStore.restore] 的调用点收在这里,
 * 不让别处也能"随手回退"(那会把版本号与内存值搞得不一致)。
 *
 * ## 线程
 * 生产侧由桥的**命令池**（多线程）调用，不是读线程。因此 [apply] 必须**串行**：
 * “版本校验 → 替换内存 → 自检 → 落盘”是一段 check-then-act，并发执行会让两条推送
 * 双双通过 baseVersion 校验、互相覆盖生效值与缓存，甚至把版本号写回去。
 * 全程仍在后台线程，不触碰 MC 主线程；所有可能抛的环节（自检回调、缓存落盘）都就地降级。
 *
 * @property selfCheck 执行自检三项;生产侧读采集器/快照/桥状态,单测注入可控结果。
 * @property writeCache 原子落盘托管缓存;单测注入记录器以断言"失败时不写盘"。
 * @property clockMillis 当前时刻(epoch 毫秒);仅供单测固定时间。
 */
internal class ConfigPushApplier(
    private val selfCheck: () -> SelfCheckResult,
    private val writeCache: (ManagedCacheStore.Snapshot) -> Unit,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * 处理一次下发。
     *
     * **必须串行**：命令池是多线程的，而本方法是一段 check-then-act（校验 baseVersion →
     * 替换内存 → 自检 → 落盘）。并发下两条推送会各自通过校验、互相覆盖，并让版本号倒退。
     * `config_push` 是低频管理操作，串行执行的代价可接受。
     *
     * @param payload 已解析的载荷。
     * @return 处理结果(用于回 `config_applied`)。
     */
    @Synchronized
    fun apply(payload: ConfigPushPayload): ConfigPushOutcome {
        val currentVersion = ManagedConfigState.configVersion()
        if (payload.configVersion <= currentVersion) {
            return reject(payload, REASON_STALE, "目标版本 ${payload.configVersion} 不大于当前版本 $currentVersion,不降级")
        }
        if (payload.baseVersion != currentVersion) {
            return reject(
                payload,
                REASON_CONFLICT,
                "baseVersion=${payload.baseVersion} 与当前版本 $currentVersion 不一致(控制面应基于最新版本重发)",
            )
        }
        // 合并后的完整取值集:既是校验跨键约束的上下文,也是最终要替换进内存的一代
        val merged = ManagedConfigValues.merge(ManagedConfigStore.currentValues(), payload.values, payload.removals)
        val rejection = validate(payload, merged)
        if (rejection != null) {
            return reject(payload, rejection.reason, rejection.message)
        }
        return applyChecked(payload, merged)
    }

    /** 应用 + 自检 + 落盘/回退。 */
    private fun applyChecked(payload: ConfigPushPayload, merged: Map<String, Any?>): ConfigPushOutcome {
        // 先留好"应用前"的两代快照:自检失败要能**整对**还原,不是只把 current 换回去
        val before = ManagedConfigStore.generations()
        ManagedConfigStore.replace(merged, payload.removals)
        val check = runSelfCheck()
        if (!check.passed) {
            return rollback(payload, before, check)
        }
        val appliedAtMillis = clockMillis()
        val appliedAt = IsoInstant.format(appliedAtMillis)
        // 缓存与内存必须逐键一致：落盘的是**内存里真实生效的那一代**（replace 已按同一规则过滤过 null），
        // 否则重启后加载到的值与崩溃前生效的值会悄悄不同
        val effective = ManagedConfigStore.currentValues()
        val cacheError = persistCache(payload.configVersion, effective, appliedAt)
        ManagedConfigState.markApplied(payload.configVersion, appliedAtMillis / MILLIS_PER_SECOND)
        ProbeLogger.info("托管配置已生效:configVersion=${payload.configVersion}, 键数=${effective.size}")
        return ConfigPushOutcome(
            result = ConfigPushOutcome.RESULT_APPLIED,
            configVersion = payload.configVersion,
            effective = effective,
            selfCheck = check,
            error = cacheError,
            appliedAt = appliedAt,
        )
    }

    /**
     * 自检失败:把两代快照整对还原,并回 `failed`。
     *
     * **缓存不写**——写下去等于把一份已知不可用的配置固化成下次启动的真源,比不回退更糟。
     */
    private fun rollback(payload: ConfigPushPayload, before: ManagedConfigStore.Generations, check: SelfCheckResult): ConfigPushOutcome {
        ManagedConfigStore.restore(before)
        val error = "自检失败:${check.failureText()}"
        ManagedConfigState.recordError(error)
        ProbeLogger.warn("配置自检失败,已回退上一版(configVersion=${payload.configVersion}):$error")
        return ConfigPushOutcome(
            result = ConfigPushOutcome.RESULT_FAILED,
            configVersion = payload.configVersion,
            // 回退后内存里**真实**生效的是上一版,如实回传让控制面对账
            effective = ManagedConfigStore.currentValues(),
            selfCheck = check,
            error = error,
            appliedAt = null,
        )
    }

    /**
     * 执行自检;自检本身抛异常一律按"三项全失败"处理并回退。
     *
     * 宁可回退一份可能没问题的配置,也不放行一份未经校验的配置——后者会让"托管面以为生效了"
     * 与"探针实际不可用"同时成立。
     */
    // 自检要读采集/桥等外部状态,任何异常都只能降级为"判失败回退",广捕是有意为之
    @Suppress("TooGenericExceptionCaught")
    private fun runSelfCheck(): SelfCheckResult = try {
        selfCheck()
    } catch (e: Exception) {
        ProbeLogger.warn("配置自检执行异常,按失败处理并回退:${e.message}")
        SelfCheckResult.FAILED
    }

    /**
     * 原子落盘托管缓存。
     *
     * 落盘失败**不回退内存**:配置本身已被自检证明可用,因磁盘问题把它撤掉是拿"确定的可用"
     * 换"确定的不可用";代价是重启后退回上一版缓存,故把原因放进 `error` 让控制面看得见。
     */
    // 落盘可能因磁盘/权限抛任意异常,探针不能因此中断应用链路,广捕是有意为之
    @Suppress("TooGenericExceptionCaught")
    private fun persistCache(configVersion: Long, values: Map<String, Any?>, appliedAt: String): String? = try {
        val epochSeconds = IsoInstant.parseEpochSeconds(appliedAt) ?: (clockMillis() / MILLIS_PER_SECOND)
        writeCache(ManagedCacheStore.Snapshot(configVersion, values, appliedAt, epochSeconds))
        null
    } catch (e: Exception) {
        val reason = "托管配置缓存落盘失败(内存已生效,重启将退回上一版):${e.message}"
        ProbeLogger.warn(reason)
        reason
    }

    /**
     * 档位与边界校验(规则全部复用 [ConfigTier],本类不另写一份)。
     *
     * @param payload 载荷。
     * @param merged 合并后的完整取值集(跨键约束要用它,而不是"推送前"的当前值)。
     * @return 通过返回 null。
     */
    private fun validate(payload: ConfigPushPayload, merged: Map<String, Any?>): Rejection? {
        payload.values.forEach { (key, value) ->
            // 跨键约束(aggregation.window <= history-capacity)必须看合并后的值,否则同时下发两个键会误拒
            val reason = ConfigTier.validate(key, value) { effectiveKey -> ManagedConfigStore.valueOf(merged, effectiveKey) }
            if (reason != null) {
                return Rejection(classify(key), reason)
            }
        }
        payload.removals.firstOrNull { ConfigTier.isForbiddenPath(it) }?.let { path ->
            return Rejection(REASON_FORBIDDEN, "removals 中出现禁止远程档路径 \"$path\",整包拒绝")
        }
        return null
    }

    /**
     * 拒绝原因码:禁止远程档(含未定档的"失败关闭"键)与越界/类型不符分开报。
     *
     * [ConfigTier.tierOf] 对未定档键的兜底就是 FORBIDDEN(失败关闭),故"未知键"同样归入 `forbidden`,
     * 与控制面"这个键不该被远程下发"的认知一致。
     */
    private fun classify(key: String): String =
        if (ConfigTier.isForbiddenPath(key) || ConfigTier.tierOf(key) == ConfigTier.FORBIDDEN) {
            REASON_FORBIDDEN
        } else {
            REASON_OUT_OF_RANGE
        }

    /** 整包拒绝:记错误状态并回 `rejected`(effective 保持当前生效值,如实上报)。 */
    private fun reject(payload: ConfigPushPayload, reason: String, message: String): ConfigPushOutcome {
        val error = "$reason: $message"
        ManagedConfigState.recordError(error)
        ProbeLogger.warn("拒绝 config_push(configVersion=${payload.configVersion}):$error")
        return ConfigPushOutcome(
            result = ConfigPushOutcome.RESULT_REJECTED,
            configVersion = payload.configVersion,
            effective = ManagedConfigStore.currentValues(),
            selfCheck = null,
            error = error,
            appliedAt = null,
        )
    }

    /** 一次校验失败([reason] 为上报给控制面的原因码,[message] 为可读中文)。 */
    private class Rejection(val reason: String, val message: String)

    private companion object {

        /** 原因码:版本陈旧(不降级)。 */
        private const val REASON_STALE = "stale"

        /** 原因码:乐观并发冲突。 */
        private const val REASON_CONFLICT = "conflict"

        /** 原因码:载荷含禁止远程档键。 */
        private const val REASON_FORBIDDEN = "forbidden"

        /** 原因码:危险档越界或类型不符。 */
        private const val REASON_OUT_OF_RANGE = "out_of_range"

        /** 毫秒/秒换算。 */
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
