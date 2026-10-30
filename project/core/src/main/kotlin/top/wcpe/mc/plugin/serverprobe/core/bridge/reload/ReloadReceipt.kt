package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadErrorCode

/**
 * 回执里的一条失败项（配置重载规格 §3.7）。
 *
 * @property path 规范化后的路径（`PATH_ESCAPE` 时为**原始输入**，否则 JM 无法把回执与它下发的值对上）。
 * @property code 错误码。
 * @property message 可读原因（已脱敏、限长）。
 */
data class ReloadFailure(
    val path: String,
    val code: ConfigReloadErrorCode,
    val message: String,
) {

    /** 渲染成 §3.7 的 `failed[]` 元素。 */
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "path" to path,
        "code" to code.name,
        "message" to message,
    )
}

/**
 * 一条自报事实（§3.7 的 `applied` 子结构）。
 *
 * 三个字段都可空：**缺省表示"未上报"**，不要写 0 或空串——`count=0` 是合法事实
 * （热重载后条目确实清零），与"没上报"必须区分。
 *
 * @property md5 插件自报「实际加载内容的 md5」。
 * @property version 插件自报「实际解析出的版本标识」。
 * @property count 插件自报「实际加载的条目数」。
 */
data class ReloadApplied(
    val md5: String? = null,
    val version: String? = null,
    val count: Int? = null,
) {

    /** 三个字段都没上报 → 不该在 `applied` 里出现这一项。 */
    fun isEmpty(): Boolean = md5 == null && version == null && count == null

    /** 渲染成 §3.7 的子结构；未上报的字段整个缺席。 */
    fun toPayload(): Map<String, Any?> {
        val payload = linkedMapOf<String, Any?>()
        md5?.let { payload["md5"] = it }
        version?.let { payload["version"] = it }
        count?.let { payload["count"] = it }
        return payload
    }

    /** 合并同一路径上多个监听器的自报值：**先到先得**，后到不覆盖已上报字段。 */
    fun merge(other: ReloadApplied): ReloadApplied = ReloadApplied(
        md5 = md5 ?: other.md5,
        version = version ?: other.version,
        count = count ?: other.count,
    )
}

/**
 * 一次配置重载的完整回执（形态见配置重载规格 §3.7）。
 *
 * ## 唯一权威的成败判定
 * `success = failed.isEmpty() && error.isEmpty()`
 *
 * - `failed` 为空、`error` 为空 → 整体成功（**即使全部是 `bestEffort`，也算成功**）；
 * - 任一失败 → 整体失败，但 `reloaded` 仍如实列出成功的部分（部分成功由调用方自行决策）；
 * - 请求级错误（路径数超限、payload 非法）不产生 `failed` 条目，只写 [error]。
 *
 * @property reloaded 至少一个监听器返回 `ok()` 的路径。
 * @property bestEffort **没有任何监听器认领**的路径（通知已派发，无人确认）——算成功，单列。
 * @property failed 任一监听器失败 / 超时 / 越界 / 在途 / 卡死的路径。
 * @property applied 自报事实，按路径。
 * @property costMs 本次编排耗时（毫秒）。
 * @property error 请求级错误文本；无则空串。
 */
data class ReloadReceipt(
    val reloaded: List<String>,
    val bestEffort: List<String>,
    val failed: List<ReloadFailure>,
    val applied: Map<String, ReloadApplied>,
    val costMs: Long,
    val error: String = "",
) {

    /** 整体成败：唯一权威判定式。 */
    val success: Boolean
        get() = failed.isEmpty() && error.isEmpty()

    /** 渲染成 §3.7 的回执 Map（供 [ConfigReloadRoute] 编码）。 */
    fun toPayload(): Map<String, Any?> = linkedMapOf(
        "success" to success,
        "reloaded" to reloaded,
        "bestEffort" to bestEffort,
        "failed" to failed.map { it.toPayload() },
        "applied" to appliedPayload(),
        "costMs" to costMs,
        "error" to error,
    )

    /**
     * `applied` 子结构：**无可上报路径时输出空 Map**（不是缺键）。
     *
     * 缺键与空 Map 对 JM 的解析后果不同（前者常被当成"字段不存在所以跳过"），
     * 而"本次没有任何插件上报"是一个明确结论，应当表达出来。
     */
    private fun appliedPayload(): Map<String, Any?> =
        applied.filterValues { !it.isEmpty() }.mapValues { it.value.toPayload() }
}
