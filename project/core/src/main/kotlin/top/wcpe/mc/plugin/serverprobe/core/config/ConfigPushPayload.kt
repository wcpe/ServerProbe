package top.wcpe.mc.plugin.serverprobe.core.config

import top.wcpe.mc.plugin.serverprobe.core.json.JsonText

/**
 * 一次 `config_push` 下发的载荷（协议规格 §3.4.1）。
 *
 * @property configVersion 控制面给的目标版本（单调递增）。
 * @property baseVersion 乐观并发基线：探针当前版本必须等于它，否则视为冲突。
 * @property values 本次下发的键值（键为 `config.yml` 的键名，可为点分）。
 * @property removals 需显式删除的点分键路径（等价于 YAML `null`）。
 * @property reason 变更原因（仅用于审计展示，探针不解释）。
 * @property operator 操作人（仅用于审计展示，探针不解释）。
 */
internal class ConfigPushPayload(
    val configVersion: Long,
    val baseVersion: Long,
    val values: Map<String, Any?>,
    val removals: List<String>,
    val reason: String,
    val operator: String,
) {

    companion object {

        /** 版本号缺失时的哨兵值（合法的版本号恒 >= 0）。 */
        private const val MISSING_VERSION = -1L

        /**
         * 解析载荷 JSON。
         *
         * 只做**结构**校验：版本号必须存在且合法。档位与物理边界校验在 [ConfigPushApplier] 里
         * 复用 [top.wcpe.mc.plugin.serverprobe.core.control.ConfigTier] 完成，避免同一规则出现两处实现。
         *
         * 解析走 [JsonText]（严格、零运行期依赖）而非 `Json` 门面：这条路径的"载荷残缺 → 中文拒绝"
         * 必须能被单测覆盖（同 `managed-cache.json` 的理由，见 `JsonText` 的说明）。
         *
         * @param payloadJson 载荷 JSON 文本。
         * @return 解析结果。
         * @throws IllegalArgumentException 载荷为空、结构非法或缺少必要版本字段
         *   （由调用方转成中文失败回执）。
         */
        fun parse(payloadJson: String): ConfigPushPayload {
            require(payloadJson.isNotBlank()) { "载荷为空" }
            val root = JsonText.parseObject(payloadJson)
            val configVersion = longOf(root["configVersion"]) ?: MISSING_VERSION
            require(configVersion >= 0L) { "缺少合法的 configVersion" }
            val baseVersion = longOf(root["baseVersion"]) ?: MISSING_VERSION
            require(baseVersion >= 0L) { "缺少合法的 baseVersion" }
            return ConfigPushPayload(
                configVersion = configVersion,
                baseVersion = baseVersion,
                values = asValues(root["values"]),
                removals = (root["removals"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
                reason = root["reason"] as? String ?: "",
                operator = root["operator"] as? String ?: "",
            )
        }

        /** 取整数值：接受整型与整值浮点（JSON 数字可能被解析成 Double），其余返回 null。 */
        private fun longOf(value: Any?): Long? = when (value) {
            is Int, is Long, is Short, is Byte -> (value as Number).toLong()
            is Double -> value.toLong().takeIf { it.toDouble() == value }
            is Float -> value.toLong().takeIf { it.toFloat() == value }
            else -> null
        }

        /** 取嵌套对象为取值表；非对象（缺失/类型不符）视为空表（无下发值）。 */
        @Suppress("UNCHECKED_CAST")
        private fun asValues(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()
    }
}
