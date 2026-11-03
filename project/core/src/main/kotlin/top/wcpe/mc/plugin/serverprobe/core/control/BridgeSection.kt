package top.wcpe.mc.plugin.serverprobe.core.control

/**
 * `config.yml` 中 `bridge` 段的**文本级**读取与移除（自动迁移专用）。
 *
 * ## 为什么按文本处理而不是 YAML 库
 * 1. **保注释**：`config.yml` 每个字段上方都有中文说明（见 `.claude/rules/config-files.md`）。用 YAML 库
 *    重新 `save` 会丢掉全部注释，把运维的配置文件洗成骨架——迁移只是搬走一个段，不该产生这种副作用。
 * 2. **可单测**：运行期 YAML 库（重定位后的 nightconfig）不在裸单测类路径，安全关键的迁移逻辑若依赖它
 *    就无法被单元测试覆盖。
 *
 * ## 支持的形态（只覆盖本仓库实际写出的形态）
 * ```yaml
 * bridge:
 *   enabled: true
 *   url: "ws://127.0.0.1:9102/ws/plugin-bridge"
 *   instance: "0b6f..."
 *   token: "eyJ..."
 * ```
 * 只认**顶层** `bridge:` 段 + 其缩进子键。行内流式写法（如 `bridge: {url: ...}`）不解析——此时三要素
 * 读出来是空的，迁移据此"不猜、不迁移"，属于安全方向。
 *
 * ## 安全
 * [BridgeSectionValues] 覆写 `toString` 掩码 token；解析过程中的错误信息也不带值。
 */
internal object BridgeSection {

    /** 段名与子键名。 */
    private const val KEY_BRIDGE = "bridge"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_URL = "url"
    private const val KEY_INSTANCE = "instance"
    private const val KEY_TOKEN = "token"

    /** `config.yml` 文本中是否存在顶层 `bridge` 段。 */
    fun exists(text: String): Boolean = findSection(text) != null

    /**
     * 读取 `bridge` 段三要素（含 enabled 开关）。
     *
     * @param text `config.yml` 全文。
     * @return 段存在时返回其取值（缺失子键为空串）；段不存在返回 null。
     */
    fun read(text: String): BridgeSectionValues? {
        val section = findSection(text) ?: return null
        val body = collectBody(text, section.end)
        return BridgeSectionValues(
            enabled = body[KEY_ENABLED].orEmpty(),
            url = body[KEY_URL].orEmpty(),
            instance = body[KEY_INSTANCE].orEmpty(),
            token = body[KEY_TOKEN].orEmpty(),
        )
    }

    /**
     * 移除顶层 `bridge` 段（含其缩进子行与段内空行），其余内容逐字节保持不变。
     *
     * **重复段一并清理**：迁移后若残留任何一个 `bridge:` 段（例如运维手工追加、
     * 而旧的默认段还在），下次启动会重新命中它并再次触发迁移判定，造成反复迁移／
     * 语义漂移。故此处清除**全部** `bridge:` 段，保证迁移结果幂等且不再复发。
     *
     * @param text `config.yml` 全文。
     * @return 移除后的文本；无该段时原样返回（幂等要求"无 bridge 段则跳过"）。
     */
    fun remove(text: String): String {
        val lines = text.split('\n')
        // 倒序删除：从后往前删才不会让前面的行号偏移
        val ranges = (duplicateSections(text) + listOfNotNull(findSection(text)))
            .sortedByDescending { it.start }
        if (ranges.isEmpty()) {
            return text
        }
        val kept = ArrayList(lines)
        for (section in ranges) {
            val bodyEnd = sectionBodyEnd(kept, section.end)
            kept.subList(section.start, bodyEnd).clear()
        }
        return kept.joinToString("\n")
    }

    /** 段位置：`bridge:` 行的索引与子行起始索引（均为 split('\n') 后的行号）。 */
    private class Section(val start: Int, val end: Int)

    /**
     * 定位顶层 `bridge:` 行；不存在返回 null。
     *
     * **取最后一个重复段**，与 YAML 解析器（重复键后者胜出）保持一致。
     *
     * 这条不是理论洁癖，而是线上踩过的坑：随包默认 `config.yml` 自带一个
     * `bridge:` 段（`enabled: false` + 全空值），运维手工启用时最自然的做法是
     * **在文件末尾再追加一个 `bridge:` 段**。此时 TabooLib 的 YAML 解析读到的是
     * 末尾那个（桥正常连上），而本类若取第一个，就会看到默认段的 `enabled: false`，
     * 判定"不可迁移"并静默停留在独立模式——表现为"桥连上了，但托管模式没生效"。
     */
    private fun findSection(text: String): Section? {
        val lines = text.split('\n')
        for (index in lines.indices.reversed()) {
            if (isTopLevelKey(lines[index], KEY_BRIDGE)) {
                return Section(index, index + 1)
            }
        }
        return null
    }

    /** 位于最后一个 `bridge:` 段之前的重复段（倒序），供 [remove] 一并清理。 */
    private fun duplicateSections(text: String): List<Section> {
        val lines = text.split('\n')
        val found = ArrayList<Section>()
        lines.forEachIndexed { index, line ->
            if (isTopLevelKey(line, KEY_BRIDGE)) {
                found.add(Section(index, index + 1))
            }
        }
        // 最后一个由 findSection 负责,重复段只需处理它之前的
        return if (found.size <= 1) emptyList() else found.subList(0, found.size - 1)
    }

    /** 收集段内一级子键取值。 */
    private fun collectBody(text: String, from: Int): Map<String, String> {
        val lines = text.split('\n')
        val bodyEnd = sectionBodyEnd(lines, from)
        val values = HashMap<String, String>()
        for (index in from until bodyEnd) {
            val (key, value) = splitChildLine(lines[index]) ?: continue
            values.putIfAbsent(key, value)
        }
        return values
    }

    /** 段体结束行号：遇到非缩进且非空的行即止（空行后若仍是缩进行则仍属本段）。 */
    private fun sectionBodyEnd(lines: List<String>, from: Int): Int {
        var index = from
        while (index < lines.size && isSectionBodyLine(lines, index)) {
            index++
        }
        return index
    }

    /** 该行是否属于段体：缩进行,或"空行且其后的下一个非空行仍缩进"。 */
    private fun isSectionBodyLine(lines: List<String>, index: Int): Boolean {
        val line = lines[index]
        return isIndented(line) || (line.isBlank() && nextNonBlankIsIndented(lines, index))
    }

    /** 空行之后的下一个非空行是否缩进（用于判断段内空行是否仍属于本段）。 */
    private fun nextNonBlankIsIndented(lines: List<String>, from: Int): Boolean {
        var index = from + 1
        while (index < lines.size) {
            val line = lines[index]
            if (line.isNotBlank()) {
                return isIndented(line)
            }
            index++
        }
        return false
    }

    /** 拆出 `  key: value` 形式的一级子键；注释行/非键值行返回 null。 */
    private fun splitChildLine(line: String): Pair<String, String>? {
        if (!isIndented(line)) {
            return null
        }
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('#')) {
            return null
        }
        val colon = trimmed.indexOf(':')
        if (colon <= 0) {
            return null
        }
        return trimmed.substring(0, colon).trim() to parseValue(trimmed.substring(colon + 1))
    }

    /** 解析 YAML 标量值：去引号、去行尾注释（`#` 前必须是空白才算注释）。 */
    private fun parseValue(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) {
            return ""
        }
        val quote = value[0]
        if (quote == '"' || quote == '\'') {
            val closing = value.indexOf(quote, 1)
            return if (closing > 0) value.substring(1, closing) else value.substring(1)
        }
        if (value.startsWith('#')) {
            return ""
        }
        val comment = value.indexOf('#')
        if (comment > 0 && value[comment - 1].isWhitespace()) {
            return value.substring(0, comment).trim()
        }
        return value
    }

    /** 行首是否为顶层键（无缩进且以 `key:` 开头）。 */
    private fun isTopLevelKey(line: String, key: String): Boolean {
        if (line.isEmpty() || isIndented(line) || !line.startsWith(key)) {
            return false
        }
        var index = key.length
        while (index < line.length && line[index] == ' ') {
            index++
        }
        // 允许 `bridge: {}` / `bridge:` / `bridge :` 几种写法,但键名必须完整
        return index < line.length && line[index] == ':'
    }

    /** 是否缩进行（YAML 子层内容）。 */
    private fun isIndented(line: String): Boolean = line.startsWith(' ') || line.startsWith('\t')
}

/**
 * `bridge` 段取值（迁移的输入）。
 *
 * 覆写 [toString] 掩码 [token]：本类型可能被打进日志/异常信息，而 token 是凭证明文。
 *
 * @property enabled `bridge.enabled` 原样文本；缺失为空串。
 * @property url 控制面 WS 地址；缺失为空串。
 * @property instance 实例标识；缺失为空串。
 * @property token 实例级凭证；缺失为空串，**永不出现在日志中**。
 */
internal data class BridgeSectionValues(
    val enabled: String,
    val url: String,
    val instance: String,
    val token: String,
) {

    /** `enabled` 是否显式为 true（大小写不敏感）。 */
    fun isEnabled(): Boolean = enabled.trim().equals("true", ignoreCase = true)

    /** 三要素是否齐备（任一缺失/空白即不可迁移：不猜、不兜底）。 */
    fun isComplete(): Boolean = url.isNotBlank() && instance.isNotBlank() && token.isNotBlank()

    /** 掩码凭证，避免凭证明文进入日志。 */
    override fun toString(): String =
        "BridgeSectionValues(enabled=$enabled, url=$url, instance=$instance, token=<已隐藏>, complete=${isComplete()})"
}
