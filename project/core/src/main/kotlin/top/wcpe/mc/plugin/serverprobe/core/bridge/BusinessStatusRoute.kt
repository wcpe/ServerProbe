package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainStatus
import top.wcpe.mc.plugin.serverprobe.core.bridge.health.HealthSnapshot
import top.wcpe.mc.plugin.serverprobe.core.json.Json

/**
 * `core.business_status` 路由（Provider 健康规格 §3.4 的权威 schema）。
 *
 * ## 只读缓存，绝不碰 Provider
 * 本路由跑在命令池上（`order=110`，`executor=COMMAND`）。它**只读**
 * [ProviderHealthRegistry] 的内存快照：
 *
 * - **不调用** `provider.health()`——那会占住第二个命令槽（查询自身已占一个），
 *   两个慢域就能让整条命令通道饱和；
 * - 不 `dispatch`、不做 IO、不新建线程。
 *
 * 于是"查询只读内存、O(域数)"这条结论才真正成立。
 *
 * ## 回执体积有界
 * 域数上限由 `bridge.health.max-entries` 决定；每域 `reason`/`detailJson` 在**入库时**
 * 就已按 `reasonMaxChars`/`detailMaxChars` 截断，本类不再二次加工（避免两处限长规则漂移）。
 *
 * ## 可替换的编码器
 * 默认走 [Json] 门面（运行期 nightconfig）。core 单测 classpath 无该库，
 * 故 [encode] 可注入纯逻辑实现，直接断言 Map 结构。
 *
 * @param registry 健康状态缓存（与 `BusinessHostImpl`、刷新线程同一实例）。
 * @param encode payload → JSON 文本。
 * @param decode payload → 过滤域名；返回 null 表示"未指定，全量返回"。
 * @param clock 回执 `timestamp` 的时钟；单测可注入假时钟。
 */
class BusinessStatusRoute(
    private val registry: ProviderHealthRegistry,
    private val encode: (Map<String, Any?>) -> String = Json::encode,
    private val decode: (String) -> String? = ::parseDomain,
    private val clock: () -> Long = System::currentTimeMillis,
) : InboundRoute {

    override val order: Int = ORDER

    override val executor: ExecutorKind = ExecutorKind.COMMAND

    override fun claims(command: BridgeCommand): Boolean =
        command.domain == DOMAIN_CORE && command.action == ACTION

    override fun handle(command: BridgeCommand): BridgeCommandResult {
        val snapshot = registry.snapshot()
        val filter = runCatching { decode(command.payload) }.getOrNull()
        val payload = linkedMapOf<String, Any?>(
            "domains" to domainsOf(snapshot, filter),
            "refresh" to linkedMapOf<String, Any?>(
                "inFlight" to snapshot.refreshInFlight,
                "stalled" to snapshot.stalled,
                "quarantined" to snapshot.quarantined,
                "lastSweepAtMs" to snapshot.lastSweepAtMs,
                "skippedThisSweep" to snapshot.skippedThisSweep,
            ),
            "timestamp" to clock(),
        )
        return BridgeCommandResult.ok(encode(payload))
    }

    /**
     * 逐域子结构。
     *
     * `registered=false` 的域**仅在显式查询该域时**出现；全量查询只列
     * 「已注册域 + 保留窗口内的已注销域」——快照本身已经只含这两类，
     * 而显式查询一个从未注册的域会额外补一条"未注册"记录（见 [missingDomain]）。
     */
    private fun domainsOf(snapshot: HealthSnapshot, filter: String?): Map<String, Any?> {
        if (filter != null) {
            return mapOf(filter to domainOf(singleOrMissing(snapshot, filter)))
        }
        val result = LinkedHashMap<String, Any?>()
        snapshot.domains.forEach { result[it.domain] = domainOf(it) }
        return result
    }

    /** 显式查询单域：快照里有就用它，没有就构造一条"未注册"记录（不报错）。 */
    private fun singleOrMissing(snapshot: HealthSnapshot, domain: String): DomainStatus =
        snapshot.domains.firstOrNull { it.domain == domain } ?: registry.viewOf(domain)

    /** 单域子结构，字段集与规格 §3.4（+ §4）逐字一致。 */
    private fun domainOf(status: DomainStatus): Map<String, Any?> {
        val health = status.health
        val reason = if (status.reasonOverride.isNotEmpty()) status.reasonOverride else health.reason
        val result = linkedMapOf<String, Any?>(
            "registered" to status.registered,
            "health" to health.status.name,
            "reason" to reason,
            "detailJson" to health.detailJson,
            "sinceMs" to health.sinceMs,
            "refreshedAtMs" to status.refreshedAtMs,
            "quarantined" to status.quarantined,
            // --- §4 的新增字段（纯增量，旧消费方读不到也不受影响） ---
            "available" to status.available,
            "degradedReason" to status.degradedReason,
            "manifestDigest" to status.manifestDigest,
            "lastDispatchAt" to status.lastDispatchAt,
            "dispatchCount" to status.dispatchCount,
            "failureCount" to status.failureCount,
        )
        // 从未失败则整个 lastFailure 字段缺席（不是空对象）：空对象会让解析方以为"有失败记录"。
        status.lastFailure?.let { failure ->
            result["lastFailure"] = linkedMapOf<String, Any?>(
                "message" to failure.message,
                "timestampMs" to failure.timestampMs,
                "consecutiveFailures" to failure.consecutiveFailures,
            )
        }
        result["dispatch"] = linkedMapOf<String, Any?>(
            "total" to status.dispatchTotal,
            "failed" to status.dispatchFailed,
            "lastCostMs" to status.lastCostMs,
        )
        return result
    }

    companion object {

        /** 路由序：见线路治理规格的 order 分配表（`config_reload` 100 / 本路由 110 / `subscribe` 120）。 */
        const val ORDER = 110

        /** 动作名。 */
        const val ACTION = "business_status"

        /** 内建治理域。 */
        const val DOMAIN_CORE = "core"

        /**
         * 默认 payload 解析器：取可选的 `domain` 字段。
         *
         * 缺失该键、空 payload 或解析失败都返回 null（→ 全量返回）：
         * 本查询是**只读诊断**，payload 拼错时给全量数据比给一条失败回执更有用，
         * 也不会像 `core.subscribe` 那样因误解析而改变系统状态。
         */
        fun parseDomain(payload: String): String? = runCatching {
            if (payload.isEmpty()) return null
            val tree = Json.parse(payload)
            if (!tree.contains("domain")) null else tree.getString("domain").ifEmpty { null }
        }.getOrNull()
    }
}
