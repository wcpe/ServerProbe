package top.wcpe.mc.plugin.serverprobe.core.mcp

/** 把工具元数据拼成 `tools/list` 下发的结构化中文描述（FR-23）。 */
object ToolDescriptionBuilder {

    private const val MAX_DESCRIPTION_CHARS = 1_500

    /**
     * 生成最终 description 文本，包含：用途 / 参数（必填与默认值）/ 示例 / 异步或同步 / 输出字段 / 限制。
     *
     * 描述总长受限（防超出客户端工具描述长度限制）；超限时优先截断输出字段段。
     */
    fun describe(tool: McpTool): String {
        val builder = StringBuilder()
        builder.append("用途：").append(tool.description).append('\n')
        appendParameters(builder, tool)
        appendExample(builder, tool)
        appendWorkflow(builder, tool)
        appendOutput(builder, tool)
        // 按码点截断，避免切断 UTF-16 代理对（补充平面字符）导致孤立代理项
        return truncateByCodePoint(builder.toString(), MAX_DESCRIPTION_CHARS)
    }

    /** 按 Unicode 码点截断到上限字符数，保证不产生孤立代理项。 */
    private fun truncateByCodePoint(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.codePoints().limit(maxChars.toLong()).toArray().let { codepoints ->
            String(codepoints, 0, codepoints.size)
        }
    }

    private fun appendParameters(builder: StringBuilder, tool: McpTool) {
        if (tool.inputSchema.isEmpty()) {
            builder.append("参数：无\n")
            return
        }
        builder.append("参数：\n")
        tool.inputSchema.forEach { (name, schema) ->
            val type = (schema as? Map<*, *>)?.get("type")?.toString() ?: "string"
            val description = (schema as? Map<*, *>)?.get("description")?.toString()
            val required = (schema as? Map<*, *>)?.get("required") == true
            builder.append("  - ").append(name).append(" (").append(type)
                .append(if (required) "，必填" else "，可选")
                .append(")：").append(description ?: "无说明").append('\n')
        }
    }

    private fun appendExample(builder: StringBuilder, tool: McpTool) {
        val example = tool.usageExample ?: return
        builder.append("示例：").append(example).append('\n')
    }

    private fun appendWorkflow(builder: StringBuilder, tool: McpTool) {
        val workflow = tool.workflow ?: "同步调用，直接返回结果"
        builder.append("工作流：").append(workflow).append('\n')
    }

    private fun appendOutput(builder: StringBuilder, tool: McpTool) {
        val fields = tool.outputFields
        if (fields.isNullOrEmpty()) {
            builder.append("输出：结构化 JSON（字段见调用结果）")
            return
        }
        builder.append("输出：\n")
        fields.forEach { (name, meaning) ->
            builder.append("  - ").append(name).append("：").append(meaning).append('\n')
        }
    }
}
