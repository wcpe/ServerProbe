package top.wcpe.mc.plugin.serverprobe.core.agent

import top.wcpe.mc.plugin.serverprobe.api.model.LibraryTiming
import top.wcpe.mc.plugin.serverprobe.api.model.PluginTiming
import top.wcpe.mc.plugin.serverprobe.api.model.StackHotspot
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger

/**
 * 启动 agent 早期数据读取器(A3,跨 ClassLoader 反射桥)。
 *
 * 启动 agent(`ProbeAgent` / `ProbeAgentBridge`)在 **system ClassLoader** 中加载并采集早期数据
 * (premain 时基、JVM 启动时刻、逐插件 load/enable 耗时、库下载耗时、主线程栈采样热点)。
 * 本读取器在插件侧把这些数据反射读出,供启动画像装配([top.wcpe.mc.plugin.serverprobe.core.startup.StartupProfileBuilder])使用。
 *
 * **最大陷阱(务必遵守)**:**严禁**在本类(或任何插件侧代码)里直接 `import` agent 的
 * `ProbeAgentBridge` / `ProbeAgent`。插件由 PluginClassLoader 加载,直接 import 会让 PluginClassLoader
 * 再加载**另一份**这两个类(与采集数据的那份不是同一个 Class 实例),读到的将是**空数据**。
 * 因此本类一律经 `Class.forName` 反射,且只取/传 **纯 JDK 基础类型**(`long` / `String` / `int`),
 * 规避两侧 Class 不一致导致的 `ClassCastException`。
 *
 * **到底反射哪一份(关键,与 agent 修复对应)**:agent 启动期会先把数据桥注入 **bootstrap ClassLoader**
 * (修复"插桩字节码够不到 system CL 上数据桥"的 `NoClassDefFoundError`),正常路径下采集数据落在 **bootstrap**
 * 那一份;仅当 bootstrap 注入失败降级时,数据才退落在 system CL 那一份。故本类按 **先 bootstrap、后 system**
 * 顺序解析(`Class.forName(名, init, null)` 的 `null` 即 bootstrap;失败再回退
 * `ClassLoader.getSystemClassLoader()`),保证读到**有数据的那一份**——即与插桩字节码/栈采样写入的同一份。
 *
 * **挂载判定与降级**:agent 仅在启动加 `-javaagent:plugins/ServerProbe.jar` 时挂载。
 * 判定"已挂载" = `forName` 成功 **且** `getPremainNanos() > 0`;否则([ClassNotFoundException] 或时基为 0)
 * 视为未挂载,[read] 返回 [AgentStartupData.notAttached](全降级,不抛异常、不影响其余画像数据)。
 *
 * 无状态纯反射门面,不持有任何业务状态,故以 `object` 实现且不纳入 IOC 容器;无平台(bukkit)依赖,落位于 core。
 */
object AgentDataReader {

    /**
     * 反射读取启动 agent 的早期数据并解析为结构化 [AgentStartupData]。
     *
     * 流程:① 经 system CL 反射 `ProbeAgentBridge`;② 读 `getPremainNanos()` 判定是否挂载——未挂载
     * 直接返回 [AgentStartupData.notAttached];③ 已挂载则逐项读取并解析序列化串(`name=ms;...`、
     * `frame=count;...`)为结构化列表。任一环节反射失败均 [ProbeLogger.warn] 并降级为未挂载结果,
     * 绝不向外抛异常。
     *
     * @param hotspotTopN 主线程栈采样热点取前若干(对应 `getStackSampleHotspots(int)` 入参,非正时无热点)。
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
            AgentStartupData(
                attached = true,
                premainNanos = premainNanos,
                jvmStartTimeMs = bridge.invokeLong(GET_JVM_START_TIME_MS),
                loadTimings = parsePluginTimings(bridge.invokeString(GET_PLUGIN_LOAD_TIMINGS)),
                enableTimings = parsePluginTimings(bridge.invokeString(GET_PLUGIN_ENABLE_TIMINGS)),
                libraryTimings = parseLibraryTimings(bridge.invokeString(GET_LIBRARY_TIMINGS)),
                hotspots = parseHotspots(bridge.invokeStringWithInt(GET_STACK_SAMPLE_HOTSPOTS, hotspotTopN))
            )
        }.getOrElse {
            ProbeLogger.warn("读取启动 agent 数据失败,按未挂载降级:${it.message}")
            AgentStartupData.notAttached()
        }
    }

    /**
     * 停止启动 agent 的主线程栈采样(由就绪监听器在定格画像前调用)。
     *
     * 经 [resolveAgentClass](先 bootstrap 后 system)反射调用 `ProbeAgent.stopStackSampler()`——注意停采样方法
     * 在 `ProbeAgent`(入口类)而非 `ProbeAgentBridge`(数据桥)。未挂载(类不存在)或反射失败均静默忽略(幂等且空安全,见 agent 实现)。
     */
    fun stopStackSampler() {
        runCatching {
            // 停采样方法在 ProbeAgent(入口类),同样按"先 bootstrap、后 system"解析到采集数据的那一份。
            resolveAgentClass(AGENT_CLASS, initialize = true)
                ?.getMethod(STOP_STACK_SAMPLER)
                ?.invoke(null)
        }
        // 未挂载时本就无采样可停;反射异常亦无需处理(停采样为尽力而为的清理动作)
    }

    /**
     * 按 **先 bootstrap、后 system** 顺序反射解析 agent 类(数据桥或入口类)。
     *
     * agent 修复后正常路径把类注入 bootstrap ClassLoader,数据落在 bootstrap 那一份;仅 bootstrap 注入失败降级时
     * 才退落 system CL。先以 `null`(bootstrap)解析、命中即返回该份;`ClassNotFoundException` 时回退
     * `ClassLoader.getSystemClassLoader()`。两处都查不到(真未挂载)返回 `null`,由调用方按未挂载降级。
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
            // bootstrap 未命中(降级路径或未挂载):回退 system CL——agent jar 在 -javaagent 时本就在 system CL。
            Class.forName(className, initialize, ClassLoader.getSystemClassLoader())
        }.getOrNull()

    /**
     * 反射调用无参、返回 `long` 的静态 getter。
     *
     * @param method 方法名。
     * @return 该 getter 的返回值。
     */
    private fun Class<*>.invokeLong(method: String): Long =
        getMethod(method).invoke(null) as Long

    /**
     * 反射调用无参、返回 `String` 的静态 getter。
     *
     * @param method 方法名。
     * @return 该 getter 的返回值(恒非 null,agent 侧约定无数据时为空串)。
     */
    private fun Class<*>.invokeString(method: String): String =
        getMethod(method).invoke(null) as String

    /**
     * 反射调用入参为 `int`、返回 `String` 的静态 getter(用于 `getStackSampleHotspots(int)`)。
     *
     * @param method 方法名。
     * @param arg int 入参。
     * @return 该 getter 的返回值(恒非 null)。
     */
    private fun Class<*>.invokeStringWithInt(method: String, arg: Int): String =
        getMethod(method, Int::class.javaPrimitiveType).invoke(null, arg) as String

    /**
     * 解析 agent 的 `name=ms;name=ms` 串为逐插件耗时列表。
     *
     * 空串返回空列表;单项格式异常(无 `=`、毫秒非数字)按容错跳过,不影响其余项。
     *
     * @param raw agent 序列化串(形如 `A=12;B=3`)。
     * @return 逐插件耗时列表(保持串内顺序)。
     */
    private fun parsePluginTimings(raw: String): List<PluginTiming> =
        parseNameValue(raw).map { (name, value) -> PluginTiming(name, value) }

    /**
     * 解析 agent 的 `name=ms;name=ms` 串为逐插件库加载耗时列表。
     *
     * @param raw agent 序列化串(形如 `A=120;B=30`)。
     * @return 逐插件库加载耗时列表(保持串内顺序)。
     */
    private fun parseLibraryTimings(raw: String): List<LibraryTiming> =
        parseNameValue(raw).map { (name, value) -> LibraryTiming(name, value) }

    /**
     * 解析 agent 的 `frame=count;frame=count` 串为主线程栈采样热点列表(已按命中降序,agent 侧保证)。
     *
     * @param raw agent 序列化串(形如 `a.b.C#m=120;d.E#n=88`)。
     * @return 热点列表(保持串内顺序,即命中降序)。
     */
    private fun parseHotspots(raw: String): List<StackHotspot> =
        parseNameValue(raw).map { (frame, count) -> StackHotspot(frame, count) }

    /**
     * 通用解析:把 `key=value;key=value`(value 为 long)拆成有序键值对。
     *
     * 容错:空串返回空列表;以 `;` 分段,每段以**最后一个** `=` 拆分(键名不含 `=`,但栈帧标识理论上不含,
     * 仍用 `substringBeforeLast` 稳妥);键为空、值非 long 的段跳过。三类数据(load/enable/library/hotspot)
     * 串结构同构,故公用此解析消除重复(规范第 5 条)。
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

    /** agent 数据桥全限定名(经 [resolveAgentClass] 先 bootstrap 后 system 反射读各 getter,严禁直接 import)。 */
    private const val BRIDGE_CLASS = "top.wcpe.mc.plugin.serverprobe.agent.ProbeAgentBridge"

    /** agent 入口类全限定名(停采样方法在此类,非数据桥)。 */
    private const val AGENT_CLASS = "top.wcpe.mc.plugin.serverprobe.agent.ProbeAgent"

    /** getter:premain 时刻 nanoTime(用于挂载判定)。 */
    private const val GET_PREMAIN_NANOS = "getPremainNanos"

    /** getter:JVM 启动时刻(epoch 毫秒)。 */
    private const val GET_JVM_START_TIME_MS = "getJvmStartTimeMs"

    /** getter:逐插件 load 耗时串。 */
    private const val GET_PLUGIN_LOAD_TIMINGS = "getPluginLoadTimings"

    /** getter:逐插件 enable 耗时串。 */
    private const val GET_PLUGIN_ENABLE_TIMINGS = "getPluginEnableTimings"

    /** getter:逐插件库加载耗时串。 */
    private const val GET_LIBRARY_TIMINGS = "getLibraryTimings"

    /** getter:主线程栈采样热点串(入参 int topN)。 */
    private const val GET_STACK_SAMPLE_HOTSPOTS = "getStackSampleHotspots"

    /** 停采样方法名(在 `ProbeAgent`)。 */
    private const val STOP_STACK_SAMPLER = "stopStackSampler"

    /** 序列化串的段分隔符。 */
    private const val SEGMENT_SEPARATOR = ";"

    /** 序列化串的键值分隔符。 */
    private const val KEY_VALUE_SEPARATOR = '='
}
