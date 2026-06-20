package top.wcpe.mc.plugin.serverprobe.core.agent

import top.wcpe.mc.plugin.serverprobe.api.model.FoldedStack
import top.wcpe.mc.plugin.serverprobe.api.model.HttpCall
import top.wcpe.mc.plugin.serverprobe.api.model.LibraryTiming
import top.wcpe.mc.plugin.serverprobe.api.model.PluginTiming
import top.wcpe.mc.plugin.serverprobe.api.model.StackHotspot
import top.wcpe.mc.plugin.serverprobe.api.model.StartupItemTiming
import top.wcpe.mc.plugin.serverprobe.api.model.ThreadStackProfile
import top.wcpe.mc.plugin.serverprobe.api.model.TimelineEvent
import top.wcpe.mc.plugin.serverprobe.api.model.WorldTiming
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger

/**
 * 启动 agent 早期数据读取器(A3/M5,跨 ClassLoader 反射桥)。
 *
 * 启动 agent(`ProbeAgent` / `ProbeAgentBridge`)在 **system/bootstrap ClassLoader** 中加载并采集早期数据
 * (premain 时基、JVM 启动时刻、逐插件 load/enable 耗时、库下载耗时、折叠栈采样、逐事件时间线、
 * 世界/配置/事件/命令耗时)。本读取器在插件侧把这些数据反射读出,供启动画像装配
 * ([top.wcpe.mc.plugin.serverprobe.core.startup.StartupProfileBuilder])使用。
 *
 * **最大陷阱(务必遵守)**:**严禁**在本类(或任何插件侧代码)里直接 `import` agent 的
 * `ProbeAgentBridge` / `ProbeAgent`。插件由 PluginClassLoader 加载,直接 import 会读到**空数据**(不同 Class 实例)。
 * 因此本类一律经 `Class.forName` 反射,且只取/传 **纯 JDK 基础类型**(`long` / `String`)。
 *
 * **到底反射哪一份**:agent 启动期会先把数据桥注入 **bootstrap ClassLoader**,正常路径数据落在 bootstrap 那一份;
 * 仅 bootstrap 注入失败降级时才退落 system CL。故本类按 **先 bootstrap、后 system** 顺序解析。
 *
 * **挂载判定与降级**:判定"已挂载" = `forName` 成功 **且** `getPremainNanos() > 0`;否则视为未挂载,
 * [read] 返回 [AgentStartupData.notAttached](全降级,不抛异常)。
 *
 * 无状态纯反射门面,无平台依赖,落位于 core。
 */
object AgentDataReader {

    /**
     * 反射读取启动 agent 的早期数据并解析为结构化 [AgentStartupData]。
     *
     * 流程:① 反射 `ProbeAgentBridge`;② 读 `getPremainNanos()` 判定是否挂载;③ 已挂载则逐项读取并解析。
     * 任一环节失败均降级为未挂载结果,绝不向外抛异常。
     *
     * @param hotspotTopN 主线程栈采样热点取前若干(由折叠栈派生时截断;非正时无热点)。
     * @return 解析后的 agent 早期数据;未挂载或读取失败时为 [AgentStartupData.notAttached]。
     */
    fun read(hotspotTopN: Int): AgentStartupData {
        val bridge = resolveAgentClass(BRIDGE_CLASS, initialize = true) ?: run {
            // ClassNotFoundException:agent 未挂载(未加 -javaagent),静默降级(非异常路径,不告警刷屏)
            return AgentStartupData.notAttached()
        }
        return runCatching {
            val premainNanos = bridge.invokeLong(GET_PREMAIN_NANOS)
            // 判定挂载:forName 成功且 premainNanos>0;否则视为未挂载(如类存在但未真正引导)
            if (premainNanos <= 0L) {
                return@runCatching AgentStartupData.notAttached()
            }
            val threadStacks = parseFoldedStacks(bridge.invokeString(GET_FOLDED_STACKS))
            AgentStartupData(
                attached = true,
                premainNanos = premainNanos,
                jvmStartTimeMs = bridge.invokeLong(GET_JVM_START_TIME_MS),
                loadTimings = parsePluginTimings(bridge.invokeString(GET_PLUGIN_LOAD_TIMINGS)),
                enableTimings = parsePluginTimings(bridge.invokeString(GET_PLUGIN_ENABLE_TIMINGS)),
                libraryTimings = parseLibraryTimings(bridge.invokeString(GET_LIBRARY_TIMINGS)),
                hotspots = deriveMainThreadHotspots(threadStacks, hotspotTopN),
                timelineEvents = parseTimelineEvents(bridge.invokeString(GET_TIMELINE_EVENTS)),
                threadStacks = threadStacks,
                worldTimings = parseWorldTimings(bridge.invokeString(GET_WORLD_TIMINGS)),
                configTimings = parseItemTimings(bridge.invokeString(GET_CONFIG_TIMINGS)),
                eventTimings = parseItemTimings(bridge.invokeString(GET_EVENT_TIMINGS)),
                commandTimings = parseItemTimings(bridge.invokeString(GET_COMMAND_TIMINGS)),
                sampleIntervalMs = bridge.invokeLong(GET_SAMPLE_INTERVAL_MS),
                // 启动期外呼快照(定格画像用):取缓冲内最近若干条,封顶以控制画像体积
                httpCalls = readHttpCallsSince(0L).first.takeLast(STARTUP_HTTP_SNAPSHOT_CAP)
            )
        }.getOrElse {
            ProbeLogger.warn("读取启动 agent 数据失败,按未挂载降级:${it.message}")
            AgentStartupData.notAttached()
        }
    }

    /**
     * 停止启动 agent 的栈采样并关闭采集窗口(由就绪监听器在定格画像前调用)。
     *
     * 经 [resolveAgentClass] 反射调用 `ProbeAgent.stopStackSampler()`——该方法同时停采样线程并关闭采集窗口
     * (避免运行期被插桩方法持续追加导致内存泄漏)。未挂载或反射失败均静默忽略(尽力而为的清理动作)。
     */
    fun stopStackSampler() {
        runCatching {
            resolveAgentClass(AGENT_CLASS, initialize = true)
                ?.getMethod(STOP_STACK_SAMPLER)
                ?.invoke(null)
        }
    }

    /**
     * 设置 agent 侧 HTTP/TCP 外呼监控开关(由插件读配置后调用)。未挂载/失败静默忽略。
     *
     * @param enabled true 开启、false 关闭
     */
    fun setHttpMonitorEnabled(enabled: Boolean) {
        runCatching {
            resolveAgentClass(BRIDGE_CLASS, initialize = true)
                ?.getMethod(SET_HTTP_MONITOR_ENABLED, Boolean::class.javaPrimitiveType)
                ?.invoke(null, enabled)
        }
    }

    /**
     * 增量读取序号大于 [sinceSeq] 的外呼记录,并返回新的最大序号(供下次拉取做游标)。
     *
     * 未挂载/失败时返回(空列表, [sinceSeq])。解析自 agent 的制表符分隔串(详见 `ProbeAgentBridge`)。
     *
     * @param sinceSeq 上次已读到的最大序号;传 0 取全部缓冲
     * @return 外呼记录列表 与 新的最大序号
     */
    fun readHttpCallsSince(sinceSeq: Long): Pair<List<HttpCall>, Long> {
        val bridge = resolveAgentClass(BRIDGE_CLASS, initialize = true) ?: return emptyList<HttpCall>() to sinceSeq
        return runCatching {
            val raw = bridge.getMethod(GET_HTTP_CALLS_SINCE, java.lang.Long.TYPE)
                .invoke(null, sinceSeq) as String
            if (raw.isEmpty()) return@runCatching emptyList<HttpCall>() to sinceSeq
            var maxSeq = sinceSeq
            val calls = raw.split(HTTP_LINE_SEP).mapNotNull { line ->
                val f = line.split(HTTP_FIELD_SEP)
                if (f.size < 10) return@mapNotNull null
                val seq = f[0].toLongOrNull() ?: return@mapNotNull null
                if (seq > maxSeq) maxSeq = seq
                HttpCall.builder()
                    .seq(seq)
                    .startRelNanos(f[1].toLongOrNull() ?: 0L)
                    .durationMs(f[2].toLongOrNull() ?: 0L)
                    .method(f[3])
                    .responseCode(f[4].toIntOrNull() ?: -1)
                    .error(f[5] == "1")
                    .host(f[6])
                    .url(f[7])
                    .headers(splitSub(f[8]))
                    .callerFrames(splitSub(f[9]))
                    .loaderHashes(if (f.size > 10) splitSub(f[10]).mapNotNull { it.toIntOrNull() } else emptyList())
                    .build()
            }
            calls to maxSeq
        }.getOrElse {
            ProbeLogger.warn("读取外呼记录失败,跳过本轮:${it.message}")
            emptyList<HttpCall>() to sinceSeq
        }
    }

    /** 按子分隔符拆分(空串→空列表,过滤空项)。 */
    private fun splitSub(s: String): List<String> =
        if (s.isEmpty()) emptyList() else s.split(HTTP_SUB_SEP).filter { it.isNotEmpty() }

    /**
     * 按 **先 bootstrap、后 system** 顺序反射解析 agent 类(数据桥或入口类)。
     *
     * @param className agent 类全限定名。
     * @param initialize 是否在解析时初始化该类(读 getter/调方法均需类已初始化,故传 `true`)。
     * @return 解析到的 [Class];两个 ClassLoader 都查不到时为 `null`。
     */
    private fun resolveAgentClass(className: String, initialize: Boolean): Class<*>? =
        runCatching {
            // null = bootstrap ClassLoader:正常路径数据桥/入口类被注入到此,优先命中。
            Class.forName(className, initialize, null)
        }.recoverCatching {
            // bootstrap 未命中(降级路径或未挂载):回退 system CL。
            Class.forName(className, initialize, ClassLoader.getSystemClassLoader())
        }.getOrNull()

    /**
     * 反射调用无参、返回 `long` 的静态 getter。
     */
    private fun Class<*>.invokeLong(method: String): Long =
        getMethod(method).invoke(null) as Long

    /**
     * 反射调用无参、返回 `String` 的静态 getter(恒非 null,agent 侧约定无数据时为空串)。
     */
    private fun Class<*>.invokeString(method: String): String =
        getMethod(method).invoke(null) as String

    /**
     * 解析 agent 的 `name=ms;name=ms` 串为逐插件耗时列表。
     */
    private fun parsePluginTimings(raw: String): List<PluginTiming> =
        parseNameValue(raw).map { (name, value) -> PluginTiming.builder().name(name).enableMs(value).build() }

    /**
     * 解析 agent 的 `name=ms;name=ms` 串为逐插件库加载耗时列表。
     */
    private fun parseLibraryTimings(raw: String): List<LibraryTiming> =
        parseNameValue(raw).map { (name, value) -> LibraryTiming.builder().name(name).loadMs(value).build() }

    /**
     * 解析 agent 的 `name=ms;name=ms` 串为逐世界创建耗时列表(M5)。
     */
    internal fun parseWorldTimings(raw: String): List<WorldTiming> =
        parseNameValue(raw).map { (name, value) -> WorldTiming.builder().name(name).loadMs(value).build() }

    /**
     * 解析 agent 的 `name=ms;name=ms` 串为通用命名项耗时列表(M5,用于配置/事件/命令)。
     */
    internal fun parseItemTimings(raw: String): List<StartupItemTiming> =
        parseNameValue(raw).map { (name, value) -> StartupItemTiming.builder().name(name).costMs(value).build() }

    /**
     * 解析 agent 的时间线事件串(M5)。
     *
     * 格式:`type|name|startNanos|endNanos;...`,每段 `|` 分隔为 4 字段,段间 `;` 分隔。时刻为相对 premain 的纳秒偏移。
     *
     * @param raw agent 序列化串。
     * @return 时间线事件列表(保持串内顺序)。
     */
    internal fun parseTimelineEvents(raw: String): List<TimelineEvent> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEGMENT_SEPARATOR).mapNotNull { segment ->
            val parts = segment.split(FIELD_SEPARATOR)
            if (parts.size < 4) return@mapNotNull null
            val type = parts[0]
            val name = parts[1]
            val startNanos = parts[2].toLongOrNull() ?: return@mapNotNull null
            val endNanos = parts[3].toLongOrNull() ?: return@mapNotNull null
            TimelineEvent.builder().type(type).name(name).startNanos(startNanos).endNanos(endNanos).build()
        }
    }

    /**
     * 解析 agent 的折叠栈串(M5,火焰图数据源)。
     *
     * 格式:每行 `线程名|栈底帧;...;栈顶帧|命中次数`,行间以 `\n` 分隔。按线程名分组,组内按命中降序;
     * 帧序列以 `;` 切分(已是栈底→栈顶顺序)。
     *
     * 容错:空串返回空列表;字段不全、命中非数字、折叠栈为空的行跳过。线程名/帧不含 `|`,故以
     * **首个**与**最后一个** `|` 框定折叠栈段,稳妥还原三字段。
     *
     * @param raw agent 序列化串。
     * @return 按线程分组的折叠栈列表。
     */
    internal fun parseFoldedStacks(raw: String): List<ThreadStackProfile> {
        if (raw.isEmpty()) return emptyList()
        val grouped = LinkedHashMap<String, MutableList<FoldedStack>>()
        raw.split(LINE_SEPARATOR).forEach { line ->
            if (line.isEmpty()) return@forEach
            val firstPipe = line.indexOf(FIELD_SEPARATOR)
            val lastPipe = line.lastIndexOf(FIELD_SEPARATOR)
            // 需至少两个 '|' 且线程名非空、折叠栈段非空
            if (firstPipe <= 0 || lastPipe <= firstPipe) return@forEach
            val threadName = line.substring(0, firstPipe)
            val folded = line.substring(firstPipe + 1, lastPipe)
            val count = line.substring(lastPipe + 1).toLongOrNull() ?: return@forEach
            if (folded.isEmpty()) return@forEach
            val frames = folded.split(FRAME_SEPARATOR)
            grouped.getOrPut(threadName) { mutableListOf() }.add(FoldedStack.builder().frames(frames).sampleCount(count).build())
        }
        return grouped.map { (name, stacks) ->
            ThreadStackProfile.builder().threadName(name).stacks(stacks.sortedByDescending { it.sampleCount }).build()
        }
    }

    /**
     * 从折叠栈派生主线程的扁平热点榜 Top-N(M5)。
     *
     * 选主线程画像(线程名以 [MAIN_THREAD_NAME] 开头者;无则取采样总数最多者),把其每条折叠栈的每一帧
     * 按命中次数累加(同一栈内同帧多次出现按多次计,与旧逐帧计数口径一致),按累计降序取前 [topN]。
     *
     * @param threadStacks 多线程折叠栈。
     * @param topN 取前若干;非正或无数据时返回空列表。
     * @return 主线程扁平热点榜(命中降序)。
     */
    internal fun deriveMainThreadHotspots(threadStacks: List<ThreadStackProfile>, topN: Int): List<StackHotspot> {
        if (threadStacks.isEmpty() || topN <= 0) return emptyList()
        val main = threadStacks.firstOrNull { it.threadName.startsWith(MAIN_THREAD_NAME) }
            ?: threadStacks.maxByOrNull { profile -> profile.stacks.sumOf { it.sampleCount } }
            ?: return emptyList()
        val counts = LinkedHashMap<String, Long>()
        main.stacks.forEach { stack ->
            stack.frames.forEach { frame ->
                counts.merge(frame, stack.sampleCount) { a, b -> a + b }
            }
        }
        return counts.entries
            .sortedByDescending { it.value }
            .take(topN)
            .map { StackHotspot.builder().frame(it.key).sampleCount(it.value).build() }
    }

    /**
     * 通用解析:把 `key=value;key=value`(value 为 long)拆成有序键值对。
     *
     * 容错:空串返回空列表;以 `;` 分段,每段以**最后一个** `=` 拆分;键为空、值非 long 的段跳过。
     *
     * @param raw 待解析串。
     * @return 有序键值对列表(保持串内顺序)。
     */
    private fun parseNameValue(raw: String): List<Pair<String, Long>> {
        if (raw.isEmpty()) {
            return emptyList()
        }
        return raw.split(SEGMENT_SEPARATOR).mapNotNull { segment ->
            val sep = segment.lastIndexOf(KEY_VALUE_SEPARATOR)
            if (sep <= 0) {
                // 无 '=' 或 '=' 在首位(键为空):格式异常,跳过
                return@mapNotNull null
            }
            val key = segment.substring(0, sep)
            val value = segment.substring(sep + 1).toLongOrNull() ?: return@mapNotNull null
            key to value
        }
    }

    /** agent 数据桥全限定名(严禁直接 import)。 */
    private const val BRIDGE_CLASS = "top.wcpe.mc.plugin.serverprobe.agent.ProbeAgentBridge"

    /** agent 入口类全限定名(停采样/关窗方法在此类)。 */
    private const val AGENT_CLASS = "top.wcpe.mc.plugin.serverprobe.agent.ProbeAgent"

    /** 主线程名前缀(派生扁平热点榜时优先选此线程)。 */
    private const val MAIN_THREAD_NAME = "Server thread"

    /** getter:premain 时刻 nanoTime(用于挂载判定)。 */
    private const val GET_PREMAIN_NANOS = "getPremainNanos"

    /** getter:JVM 启动时刻(epoch 毫秒)。 */
    private const val GET_JVM_START_TIME_MS = "getJvmStartTimeMs"

    /** getter:栈采样周期(毫秒,M5)。 */
    private const val GET_SAMPLE_INTERVAL_MS = "getSampleIntervalMs"

    /** getter:逐插件 load 耗时串。 */
    private const val GET_PLUGIN_LOAD_TIMINGS = "getPluginLoadTimings"

    /** getter:逐插件 enable 耗时串。 */
    private const val GET_PLUGIN_ENABLE_TIMINGS = "getPluginEnableTimings"

    /** getter:逐插件库加载耗时串。 */
    private const val GET_LIBRARY_TIMINGS = "getLibraryTimings"

    /** getter:逐世界创建耗时串(M5)。 */
    private const val GET_WORLD_TIMINGS = "getWorldTimings"

    /** getter:逐配置文件加载耗时串(M5)。 */
    private const val GET_CONFIG_TIMINGS = "getConfigTimings"

    /** getter:逐事件注册耗时串(M5)。 */
    private const val GET_EVENT_TIMINGS = "getEventTimings"

    /** getter:逐命令注册耗时串(M5)。 */
    private const val GET_COMMAND_TIMINGS = "getCommandTimings"

    /** getter:折叠栈串(M5,无参,不截断)。 */
    private const val GET_FOLDED_STACKS = "getFoldedStacks"

    /** getter:增量外呼记录串(M5,入参 long sinceSeq)。 */
    private const val GET_HTTP_CALLS_SINCE = "getHttpCallsSince"

    /** setter:外呼监控开关(M5,入参 boolean)。 */
    private const val SET_HTTP_MONITOR_ENABLED = "setHttpMonitorEnabled"

    /** 外呼记录字段分隔符(制表符,用字符码构造以规避源码转义歧义)。 */
    private val HTTP_FIELD_SEP = 9.toChar()

    /** 外呼记录子列表分隔符(SOH,U+0001)。 */
    private val HTTP_SUB_SEP = 1.toChar()

    /** 外呼记录行分隔符(换行)。 */
    private val HTTP_LINE_SEP = 10.toChar()

    /** 启动画像内嵌的外呼快照封顶条数(控制画像体积)。 */
    private const val STARTUP_HTTP_SNAPSHOT_CAP = 200

    /** getter:时间线事件串(M5,无参)。 */
    private const val GET_TIMELINE_EVENTS = "getTimelineEvents"

    /** 停采样/关窗方法名(在 `ProbeAgent`)。 */
    private const val STOP_STACK_SAMPLER = "stopStackSampler"

    /** 序列化串的段分隔符(`name=ms;...` 与时间线 `event;...`)。 */
    private const val SEGMENT_SEPARATOR = ";"

    /** 折叠栈串的行分隔符。 */
    private const val LINE_SEPARATOR = "\n"

    /** 键值分隔符(`name=ms` 中的 `=`)。 */
    private const val KEY_VALUE_SEPARATOR = '='

    /** 字段分隔符(时间线 `type|name|...` 与折叠栈 `线程名|折叠栈|次数` 中的 `|`)。 */
    private const val FIELD_SEPARATOR = "|"

    /** 折叠栈内帧分隔符(`栈底;...;栈顶` 中的 `;`)。 */
    private const val FRAME_SEPARATOR = ";"
}
