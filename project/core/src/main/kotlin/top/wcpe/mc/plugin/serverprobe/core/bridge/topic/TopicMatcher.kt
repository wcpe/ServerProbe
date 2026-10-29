package top.wcpe.mc.plugin.serverprobe.core.bridge.topic

/**
 * 主题表达式匹配（事件主题订阅规格 §3.4）。
 *
 * 纯字符串前缀比较，**不做正则、不做层级继承**：滤镜跑在事件发送路径上，
 * 每个业务事件都要过一次，正则的编译与回溯在这里都不划算。
 */
object TopicMatcher {

    /** 通配后缀：`expr` 以它结尾时才启用前缀匹配。 */
    private const val WILDCARD_SUFFIX = ".*"

    /**
     * 订阅表达式是否匹配主题。
     *
     * 规则（纯字符串前缀）：
     * - 精确：`expr == topic`
     * - 通配：`expr` 以 `.*` 结尾 → `topic.startsWith(expr.dropLast(1))`（`activity.*` → 前缀 `activity.`）
     * - 其他位置含 `*` → **视为不匹配**（`act*ity` 既不匹配 `activity` 也不匹配别的东西，避免歧义）
     *
     * **层级语义是刻意的**：`activity.*` 匹配 `activity.12345`，也匹配 `activity.12345.sub`。
     * 若改成层级继承，会出现"订阅了父主题却收不到子主题"的陷阱；
     * 前缀匹配更符合"我要这个业务域下的一切"的直觉。
     *
     * @param expr 订阅表达式（来自 JM 的 `core.subscribe`）。
     * @param topic 帧上声明的主题。
     * @return true 表示该主题被此表达式覆盖。
     */
    fun matches(expr: String, topic: String): Boolean {
        if (expr.isEmpty() || topic.isEmpty()) return false
        if (expr == topic) return true
        if (!expr.endsWith(WILDCARD_SUFFIX)) return false
        return topic.startsWith(expr.dropLast(1))
    }
}
