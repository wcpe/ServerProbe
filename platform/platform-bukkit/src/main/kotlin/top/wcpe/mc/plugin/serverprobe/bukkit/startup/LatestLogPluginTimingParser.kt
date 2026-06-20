package top.wcpe.mc.plugin.serverprobe.bukkit.startup

import top.wcpe.mc.plugin.serverprobe.api.model.PluginTiming

/**
 * `logs/latest.log` 逐插件 onEnable 耗时解析器(FR1.2,慢插件榜数据源)。
 *
 * 服务端启动时会按顺序为每个插件打印一行形如 `Enabling <Name> v<version>` 的日志,行首带时间戳。
 * 本解析器从这些行中提取"插件名 + 该行时间戳",再以**相邻 Enabling 行的时间差**近似每个插件的
 * onEnable 耗时:某插件耗时 = 「它的 Enabling 时刻」→「下一条 Enabling 时刻 或 启动完成(`Done (...)!`)时刻」之差。
 *
 * **M1 近似与已知局限**(KDoc 标注,M2 完善):
 * - 时间差近似:相邻 Enabling 之间不仅是上一个插件的 onEnable,还可能夹杂世界加载、其它启动日志,
 *   故为粗粒度估算,精确单插件耗时需更细的事件级打点(M2)。
 * - 时间戳仅精确到秒(标准日志格式),故耗时粒度为秒级(以毫秒表达)。
 * - 跨午夜:若启动横跨 00:00,后一时刻会小于前一时刻,本解析器对该单次差值按"加一天"校正以避免负值。
 * - 最后一个插件:其后若无 `Done` 行可参照,则无法测得耗时,记为 0(而非丢弃该插件)。
 *
 * 容错:本解析器为**纯函数**,不抛业务异常;任何不符合预期的行被静默跳过,
 * 仅返回能够成功解析的部分(全部无法解析时返回空列表),保证启动剖析不因日志格式差异而中断。
 */
object LatestLogPluginTimingParser {

    /**
     * 解析日志行,产出逐插件 onEnable 耗时(按日志出现顺序)。
     *
     * @param lines `logs/latest.log` 的文本行(通常为启动至今的全部行)。
     * @return 逐插件耗时列表(按日志顺序);无可解析数据时为空列表。
     */
    fun parse(lines: List<String>): List<PluginTiming> {
        // 第一遍:抽取所有 "Enabling <Name>" 行的「插件名 + 行内秒数」
        val events = ArrayList<EnableEvent>()
        var doneSecond: Int? = null
        for (line in lines) {
            // 一旦遇到启动完成行,记录其时刻作为最后一个插件的耗时上界,并停止继续收集
            val done = parseDoneSecond(line)
            if (done != null) {
                doneSecond = done
                break
            }
            val event = parseEnableEvent(line) ?: continue
            events.add(event)
        }
        if (events.isEmpty()) {
            return emptyList()
        }
        // 第二遍:逐插件以"下一条 Enabling 时刻 / Done 时刻"为上界计算耗时
        val result = ArrayList<PluginTiming>(events.size)
        for (index in events.indices) {
            val current = events[index]
            val nextSecond = if (index + 1 < events.size) events[index + 1].second else doneSecond
            val enableMs = if (nextSecond == null) {
                // 末尾插件且无 Done 行参照:无法测得耗时,记为 0(M1 近似)
                0L
            } else {
                diffSecondsToMillis(current.second, nextSecond)
            }
            result.add(PluginTiming.builder().name(current.name).enableMs(enableMs).build())
        }
        return result
    }

    /**
     * 从单行中解析 `Enabling <Name> v<version>` 事件。
     *
     * 同时要求该行能解析出行首时间戳(秒)与插件名,二者缺一则视为不匹配返回 null。
     *
     * @param line 日志行。
     * @return 解析出的事件;不匹配时为 null。
     */
    private fun parseEnableEvent(line: String): EnableEvent? {
        val match = ENABLING_REGEX.find(line) ?: return null
        val name = match.groupValues[1]
        if (name.isEmpty()) {
            return null
        }
        val second = parseLineSecond(line) ?: return null
        return EnableEvent(name, second)
    }

    /**
     * 若该行为启动完成行(`Done (x.xxxs)! For help, ...`),解析其行首时间戳(秒)。
     *
     * @param line 日志行。
     * @return 启动完成时刻(当日秒数);非完成行时为 null。
     */
    private fun parseDoneSecond(line: String): Int? {
        if (!DONE_REGEX.containsMatchIn(line)) {
            return null
        }
        return parseLineSecond(line)
    }

    /**
     * 解析行首时间戳 `[HH:mm:ss ...]` 为"当日累计秒数"。
     *
     * 兼容 `[12:34:56 INFO]:` 与 `[12:34:56] [Server thread/INFO]:` 等变体——只取第一段 `HH:mm:ss`。
     *
     * @param line 日志行。
     * @return 当日累计秒数(0..86399);无法解析时为 null。
     */
    private fun parseLineSecond(line: String): Int? {
        val match = TIME_REGEX.find(line) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        val second = match.groupValues[3].toIntOrNull() ?: return null
        return hour * SECONDS_PER_HOUR + minute * SECONDS_PER_MINUTE + second
    }

    /**
     * 计算两个"当日秒数"之间的毫秒差;若出现跨午夜(后值小于前值)则按加一天校正。
     *
     * @param fromSecond 起始当日秒数。
     * @param toSecond 结束当日秒数。
     * @return 非负毫秒差。
     */
    private fun diffSecondsToMillis(fromSecond: Int, toSecond: Int): Long {
        var deltaSeconds = toSecond - fromSecond
        if (deltaSeconds < 0) {
            // 跨午夜:结束时刻落到次日,补一天
            deltaSeconds += SECONDS_PER_DAY
        }
        return deltaSeconds.toLong() * MILLIS_PER_SECOND
    }

    /**
     * 单条 Enabling 事件的中间表示。
     *
     * @property name 插件名称。
     * @property second 该行时间戳对应的当日累计秒数。
     */
    private data class EnableEvent(val name: String, val second: Int)

    /** 行首时间戳正则:捕获第一段 `HH:mm:ss`(分组 1/2/3 分别为时、分、秒)。 */
    private val TIME_REGEX = Regex("""\[(\d{1,2}):(\d{2}):(\d{2})""")

    /** 插件启用行正则:捕获 `Enabling ` 之后、` v` 之前的插件名(分组 1)。 */
    private val ENABLING_REGEX = Regex("""Enabling\s+(\S+)\s+v""")

    /**
     * 启动完成行正则:匹配完整的启动完成特征 `Done (x.xxxs)!`,用于标记最后一个插件的耗时上界。
     *
     * 收紧为"括号内为秒数 + 紧随感叹号"(原生启动完成行特征,如 `Done (21.345s)! For help, ...`),
     * 避免被插件/数据包中途打印的 `Done (` 片段(如 `Done (loading ...)`)提前误判为启动完成而截断解析。
     */
    private val DONE_REGEX = Regex("""Done\s+\([\d.]+s\)!""")

    /** 一分钟的秒数。 */
    private const val SECONDS_PER_MINUTE = 60

    /** 一小时的秒数。 */
    private const val SECONDS_PER_HOUR = 3600

    /** 一天的秒数。 */
    private const val SECONDS_PER_DAY = 86_400

    /** 一秒的毫秒数。 */
    private const val MILLIS_PER_SECOND = 1000L
}
