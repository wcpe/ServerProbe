package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommand
import top.wcpe.mc.plugin.serverprobe.core.bridge.ExecutorKind
import top.wcpe.mc.plugin.serverprobe.core.bridge.InboundRoute
import top.wcpe.mc.plugin.serverprobe.core.json.Json

/**
 * `core.config_reload` 路由（配置重载规格 §3.1，序位 100）。
 *
 * ## 匹配与执行分离
 * [claims] 只做纯同步字符串判定（跑在桥读线程上，绝不阻塞、绝不 IO）；
 * [handle] 在 **reload 协调池**执行，编排与收割都在那里，读线程立即返回。
 *
 * ## payload 语义
 * `payload={"paths":[...]}`；**空数组或省略 `paths` 键 = 全量**（向所有已注册监听器各通知一次）。
 * payload 非法（不是 JSON、`paths` 不是字符串数组）→ 请求级错误：`success=false`、
 * `error` 非空、**`failed` 为空**（它不是"某个路径失败"）。
 *
 * ## 可替换的编解码
 * 默认走 [Json] 门面（运行期 nightconfig）；core 单测 classpath 无该库，
 * 故 [decode] / [encode] 均可注入，从而在没有 JSON 库的裸类路径上断言完整回执结构
 * （与 `BridgeStatusRoute` / `SubscribeRoute` 的处理方式一致）。
 *
 * ## 传输层成败映射
 * 成功 → `ok(回执 JSON)`；失败 → `ofFailure(回执 JSON, 可读摘要)`。
 * **两种情形都带结构化回执**：失败时 `reloaded` / `bestEffort` / `applied` 仍须如实上报
 * （规格 §3.7「`reloaded` 仍如实列出成功的部分」），可读原因另走 `error`。
 * 不要退回 `fail(...)`——后者的 output 恒为空串，会把机器可读明细整段丢掉。
 *
 * @param coordinator 编排器（跑在 reload 协调池上）。
 * @param decode payload → 路径列表；返回 null 表示 payload 非法。
 * @param encode 回执 Map → JSON 文本。
 */
class ConfigReloadRoute(
    private val coordinator: ConfigReloadCoordinator,
    private val decode: (String) -> List<String>? = ::parsePaths,
    private val encode: (Map<String, Any?>) -> String = Json::encode,
) : InboundRoute {

    override val order: Int = ORDER

    override val executor: ExecutorKind = ExecutorKind.RELOAD

    override fun claims(command: BridgeCommand): Boolean =
        command.domain == DOMAIN_CORE && command.action == ACTION

    override fun handle(command: BridgeCommand): BridgeCommandResult {
        val paths = runCatching { decode(command.payload) }.getOrNull()
            ?: return BridgeCommandResult.fail(MESSAGE_BAD_PAYLOAD)
        val receipt = runCatching { coordinator.reload(paths) }
            .getOrElse { ReloadReceipt(emptyList(), emptyList(), emptyList(), emptyMap(), 0L, MESSAGE_INTERNAL) }
        // 成败两边都要带结构化回执：失败时 `reloaded`/`bestEffort`/`applied` 仍须如实上报
        // （规格 §3.7「reloaded 仍如实列出成功的部分」），而可读原因走 error。
        // 用 ofFailure 而非 fail——后者的 output 恒为空串，会丢掉机器可读明细。
        return if (receipt.success) {
            BridgeCommandResult.ok(encodeReceipt(receipt))
        } else {
            BridgeCommandResult.ofFailure(encodeReceipt(receipt), failureSummary(receipt))
        }
    }

    /** 编码回执；编码失败退化为可读摘要（绝不因编码器缺失吞掉整个回执）。 */
    private fun encodeReceipt(receipt: ReloadReceipt): String = runCatching {
        encode(receipt.toPayload())
    }.getOrElse { failureSummary(receipt) }

    /**
     * 失败摘要：优先用请求级 [ReloadReceipt.error]；否则逐条列"路径=错误码(原因)"。
     *
     * 长失败清单按 [MAX_SUMMARY_ITEMS] 截断并注明剩余条数——回执本身可能上千字符，
     * 而这段文本会进 `command_result.error` 被前端直接展示。
     */
    private fun failureSummary(receipt: ReloadReceipt): String {
        if (receipt.error.isNotEmpty()) return receipt.error
        val head = "配置重载失败:${receipt.failed.size} 项未成功" +
            if (receipt.reloaded.isEmpty()) "" else "(已成功 ${receipt.reloaded.size} 项)"
        val items = receipt.failed.take(MAX_SUMMARY_ITEMS).joinToString("; ") {
            "${it.path}=${it.code.name}(${it.message})"
        }
        val tail = if (receipt.failed.size > MAX_SUMMARY_ITEMS) {
            ";…另有 ${receipt.failed.size - MAX_SUMMARY_ITEMS} 项(详见探针日志)"
        } else {
            ""
        }
        return "$head;$items$tail"
    }

    companion object {

        /** 路由序：见线路治理规格的 order 分配表（`config_reload` 100 / `business_status` 110 / `subscribe` 120）。 */
        const val ORDER = 100

        /** 内建治理域。 */
        const val DOMAIN_CORE = "core"

        /** 动作名。 */
        const val ACTION = "config_reload"

        /** payload 非法时的失败原因。 */
        const val MESSAGE_BAD_PAYLOAD = "payload 非法:需要 {\"paths\":[...]} 或省略 paths(全量重载)"

        /** 编排器抛异常时的兜底原因（理论不可达）。 */
        const val MESSAGE_INTERNAL = "探针内部错误:重载编排异常(未执行任何重载)"

        /** 失败摘要里最多列出的条数。 */
        private const val MAX_SUMMARY_ITEMS = 8

        /**
         * 默认 payload 解析器：取可选的 `paths` 字符串数组。
         *
         * - 空 payload → 空列表（= 全量）；
         * - 缺 `paths` 键 → 空列表（= 全量，规格 §3.7 的"省略 = 全量"）；
         * - `paths: []` → 空列表（= 全量）；
         * - 其它形态（不是 JSON / `paths` 不是数组）→ null（→ 请求级错误）。
         *
         * 依赖运行期 nightconfig；单测通过构造参数替换为纯逻辑解析器。
         */
        fun parsePaths(payload: String): List<String>? {
            if (payload.isBlank()) return emptyList()
            val tree = Json.parse(payload)
            if (!tree.contains("paths")) return emptyList()
            return tree.getStringList("paths").toList()
        }
    }
}
