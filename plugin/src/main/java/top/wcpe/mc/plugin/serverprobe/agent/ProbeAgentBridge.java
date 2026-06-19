package top.wcpe.mc.plugin.serverprobe.agent;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * 跨 ClassLoader 数据桥（system/bootstrap ClassLoader 静态容器）。
 *
 * <p>本类由 agent（{@link ProbeAgent}）在启动期加载并写入数据，插件侧（core 的 AgentDataReader）
 * 必须通过 {@code Class.forName} 反射读取本类的静态数据；<b>严禁</b>在插件代码里直接 {@code import} 本类——
 * 那会被 PluginClassLoader 加载成<b>另一份空数据</b>（不同 Class 实例），这是跨 CL 通道的最大陷阱。
 *
 * <p>设计约束：
 * <ul>
 *   <li><b>纯 JDK</b>：只依赖 {@code java.*}，不触碰 kotlin / taboolib / bukkit（这些 CL 不可见）。</li>
 *   <li><b>纯 JDK 类型 getter</b>：对外只暴露 {@code long} / {@code String} 等基础类型，跨 CL 反射传基础类型最安全。</li>
 *   <li><b>线程安全</b>：插桩字节码与采样守护线程并发写入，用 {@link ConcurrentHashMap}/{@link LongAdder}/
 *       {@link ConcurrentLinkedQueue} 承载，全程无显式锁。</li>
 * </ul>
 *
 * <p><b>启动窗口（防泄漏，关键）</b>：被插桩的方法（{@code registerEvents}/{@code register}/
 * {@code loadConfiguration}/{@code createWorld} 等）在服务器<b>运行期</b>同样会触发，若不设防会持续向时间线
 * 与聚合表追加 → 内存无界增长。为此设 {@link #profilingActive} 启动窗口标志：插件就绪后由
 * {@link ProbeAgent#stopStackSampler()} 经 {@link #closeStartupWindow()} 置 false，此后所有 {@code record*}
 * 入口直接返回，把采集严格收敛在"启动期"。插桩字节码仍在（无 retransform 卸载），但每次只多跑一次
 * volatile 读后即返回，开销可忽略。
 *
 * <p>全部为静态字段/方法，这是 agent 与插件之间唯一的共享内存锚点，故采用静态容器形态而非实例。
 */
public final class ProbeAgentBridge {

    /** premain/agentmain 执行那一刻的 {@link System#nanoTime()}，作为时间线"相对 premain 偏移"的基准。 */
    private static volatile long premainNanos = 0L;

    /** JVM 启动时刻（毫秒，来自 {@code RuntimeMXBean#getStartTime()}），用于计算"开服总耗时"基准。 */
    private static volatile long jvmStartTimeMs = 0L;

    /** JVM 启动参数（来自 {@code RuntimeMXBean#getInputArguments()}），以空格拼接成单串，便于跨 CL 传递。 */
    private static volatile String jvmArgs = "";

    /** 栈采样周期（毫秒，由 {@link StartupStackSampler} 启动时写入），供展示侧做"采样数 × 周期"的耗时估算。 */
    private static volatile long sampleIntervalMs = 0L;

    /**
     * 启动窗口标志：true 表示仍在启动期、接受采集；false 表示窗口已关闭、所有 {@code record*} 直接返回。
     *
     * <p>{@code volatile} 保证 {@link #closeStartupWindow()} 的写对插桩/采样线程立即可见。
     */
    private static volatile boolean profilingActive = true;

    /** 逐插件 enable 耗时累加表：键为插件名，值为累计毫秒数。 */
    private static final Map<String, Long> PLUGIN_ENABLE_TIMINGS = new ConcurrentHashMap<String, Long>();

    /** 逐插件 load 耗时累加表：键为插件名，值为累计毫秒数（对应 {@code SimplePluginManager#loadPlugin} 出口）。 */
    private static final Map<String, Long> PLUGIN_LOAD_TIMINGS = new ConcurrentHashMap<String, Long>();

    /** 逐插件库下载/加载耗时累加表：键为插件名，值为累计毫秒数（对应 {@code LibraryLoader#createLoader}，1.17+）。 */
    private static final Map<String, Long> LIBRARY_TIMINGS = new ConcurrentHashMap<String, Long>();

    /** 逐世界创建耗时累加表(M5)：键为世界名，值为累计毫秒数。 */
    private static final Map<String, Long> WORLD_TIMINGS = new ConcurrentHashMap<String, Long>();

    /** 逐配置文件加载耗时累加表(M5)：键为文件名，值为累计毫秒数。 */
    private static final Map<String, Long> CONFIG_TIMINGS = new ConcurrentHashMap<String, Long>();

    /** 逐事件注册耗时累加表(M5)：键为插件名，值为累计毫秒数。 */
    private static final Map<String, Long> EVENT_TIMINGS = new ConcurrentHashMap<String, Long>();

    /** 逐命令注册耗时累加表(M5)：键为命令名，值为累计毫秒数。 */
    private static final Map<String, Long> COMMAND_TIMINGS = new ConcurrentHashMap<String, Long>();

    /**
     * 启动期逐事件时间线(M5)：每段格式 {@code type|name|startNanosRel|endNanosRel}，时刻均为相对 premain 的纳秒偏移。
     *
     * <p>用 {@link ConcurrentLinkedQueue}（O(1) 无锁入队）承载高频追加——时间线是"写多读一次"的场景，
     * 若误用 {@code CopyOnWriteArrayList}（每次 add 全数组复制）会退化为 O(n²)，反而拖慢被测启动。
     */
    private static final Queue<String> TIMELINE_EVENTS = new ConcurrentLinkedQueue<String>();

    /**
     * 折叠栈采样表(M5,火焰图数据源)：键为 {@code 线程名|栈底帧;...;栈顶帧}，值为该完整调用路径的命中次数。
     *
     * <p>这是火焰图的标准数据形态：保留每次采样的<b>完整有序调用栈</b>，而非逐帧词频，故下游可逐层并树还原
     * 真正的多层火焰图。由 {@link StartupStackSampler} 守护线程写入。
     */
    private static final Map<String, LongAdder> FOLDED_STACKS = new ConcurrentHashMap<String, LongAdder>();

    /** 工具容器，禁止实例化。 */
    private ProbeAgentBridge() {
    }

    /**
     * 写入 premain 时刻的 nanoTime（由 {@link ProbeAgent} bootstrap 调用，仅一次）。
     *
     * @param nanos {@link System#nanoTime()} 取值
     */
    public static void setPremainNanos(long nanos) {
        premainNanos = nanos;
    }

    /**
     * 读取 premain 时刻的 nanoTime。
     *
     * @return premain 执行时的 {@link System#nanoTime()}；未初始化时为 0
     */
    public static long getPremainNanos() {
        return premainNanos;
    }

    /**
     * 写入 JVM 启动时刻（由 {@link ProbeAgent} bootstrap 调用，仅一次）。
     *
     * @param millis JVM 启动的纪元毫秒
     */
    public static void setJvmStartTimeMs(long millis) {
        jvmStartTimeMs = millis;
    }

    /**
     * 读取 JVM 启动时刻。
     *
     * @return JVM 启动的纪元毫秒；未初始化时为 0
     */
    public static long getJvmStartTimeMs() {
        return jvmStartTimeMs;
    }

    /**
     * 写入 JVM 启动参数串（由 {@link ProbeAgent} bootstrap 调用，仅一次）。
     *
     * @param args 以空格拼接的 JVM 启动参数；不可为 {@code null}（无参数时传空串）
     */
    public static void setJvmArgs(String args) {
        jvmArgs = args == null ? "" : args;
    }

    /**
     * 读取 JVM 启动参数串。
     *
     * @return 以空格拼接的 JVM 启动参数；未初始化时为空串
     */
    public static String getJvmArgs() {
        return jvmArgs;
    }

    /**
     * 写入栈采样周期（由 {@link StartupStackSampler#start()} 调用，仅一次）。
     *
     * @param millis 采样周期（毫秒）
     */
    public static void setSampleIntervalMs(long millis) {
        sampleIntervalMs = millis;
    }

    /**
     * 读取栈采样周期。
     *
     * @return 采样周期（毫秒）；未启动采样时为 0
     */
    public static long getSampleIntervalMs() {
        return sampleIntervalMs;
    }

    /**
     * 关闭启动窗口（插件就绪后由 {@link ProbeAgent#stopStackSampler()} 调用，幂等）。
     *
     * <p>置 false 后所有 {@code record*} 入口直接返回，把采集严格收敛在启动期，杜绝运行期持续追加导致的内存泄漏。
     */
    public static void closeStartupWindow() {
        profilingActive = false;
    }

    /**
     * 同时记录"汇总耗时"与"时间线事件"（M5,由插桩增强器在每个 hook 出口调用）。
     *
     * <p>入参为<b>原始纳秒时刻</b>（入口/出口各取一次 {@link System#nanoTime()}），故耗时与时间线均保留纳秒精度：
     * <ul>
     *   <li>汇总：按 {@code type} 分派到对应累加表，累加 {@code costMs = (end - start) / 1e6}（毫秒口径）；</li>
     *   <li>时间线：写入一条相对 premain 的事件（{@code start - premain}、{@code end - premain}），与
     *       {@code TimelineEvent} 的"相对 premain 偏移"契约一致。</li>
     * </ul>
     * 启动窗口关闭后直接返回，不再采集（防运行期泄漏）。
     *
     * @param type       事件类型（enable/load/library/worldCreate/configLoad/eventRegister/commandRegister）及累加表映射键
     * @param name       被 hook 的对象名
     * @param startNanos 入口时刻（原始 {@code System.nanoTime()}）
     * @param endNanos   出口时刻（原始 {@code System.nanoTime()}）
     */
    public static void recordSpan(String type, String name, long startNanos, long endNanos) {
        if (!profilingActive) {
            return;
        }
        long costMs = (endNanos - startNanos) / 1_000_000L;
        Map<String, Long> timings = typeToTimings(type);
        if (timings != null) {
            accumulate(timings, name, costMs);
        }
        long base = premainNanos;
        recordTimelineEvent(type, name, startNanos - base, endNanos - base);
    }

    /**
     * 记录一次折叠栈采样命中（由 {@link StartupStackSampler} 守护线程调用，M5）。
     *
     * <p>启动窗口关闭后直接返回。空线程名/空栈忽略。
     *
     * @param threadName 线程名
     * @param folded     折叠栈串（{@code 栈底帧;...;栈顶帧}，帧标识为 {@code 类全名#方法名}）
     */
    public static void recordFoldedStack(String threadName, String folded) {
        if (!profilingActive) {
            return;
        }
        if (threadName == null || threadName.isEmpty() || folded == null || folded.isEmpty()) {
            return;
        }
        FOLDED_STACKS.computeIfAbsent(threadName + '|' + folded, k -> new LongAdder()).increment();
    }

    /**
     * 导出全部折叠栈，序列化为字符串（M5,火焰图数据源，不截断）。
     *
     * <p>格式：每行 {@code 线程名|栈底帧;...;栈顶帧|命中次数}，行间以 {@code \n} 分隔。火焰图需要完整调用栈集合
     * 方能还原层级，故此处<b>不做 Top-N 截断</b>；启动期唯一调用栈集合有界（按 distinct 路径计），跨 CL 单串传回可控。
     *
     * @return 序列化的折叠栈串；无数据时为空串
     */
    public static String getFoldedStacks() {
        if (FOLDED_STACKS.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, LongAdder> entry : FOLDED_STACKS.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            // 键已是 "线程名|折叠栈"，追加 "|次数" 即为完整一行
            sb.append(entry.getKey()).append('|').append(entry.getValue().sum());
        }
        return sb.toString();
    }

    /** @return 逐插件 enable 耗时串（{@code name=ms;...}）；无数据时为空串 */
    public static String getPluginEnableTimings() {
        return serializeTimings(PLUGIN_ENABLE_TIMINGS);
    }

    /** @return 逐插件 load 耗时串；无数据时为空串 */
    public static String getPluginLoadTimings() {
        return serializeTimings(PLUGIN_LOAD_TIMINGS);
    }

    /** @return 逐插件库加载耗时串；无数据时为空串 */
    public static String getLibraryTimings() {
        return serializeTimings(LIBRARY_TIMINGS);
    }

    /** @return 逐世界创建耗时串；无数据时为空串 */
    public static String getWorldTimings() {
        return serializeTimings(WORLD_TIMINGS);
    }

    /** @return 逐配置文件加载耗时串；无数据时为空串 */
    public static String getConfigTimings() {
        return serializeTimings(CONFIG_TIMINGS);
    }

    /** @return 逐事件注册耗时串；无数据时为空串 */
    public static String getEventTimings() {
        return serializeTimings(EVENT_TIMINGS);
    }

    /** @return 逐命令注册耗时串；无数据时为空串 */
    public static String getCommandTimings() {
        return serializeTimings(COMMAND_TIMINGS);
    }

    /**
     * 导出全部时间线事件，序列化为字符串。
     *
     * <p>格式：{@code type|name|startNanosRel|endNanosRel;...}（无尾分号），时刻为相对 premain 的纳秒偏移。
     *
     * @return 序列化的时间线事件串；无数据时为空串
     */
    public static String getTimelineEvents() {
        if (TIMELINE_EVENTS.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String event : TIMELINE_EVENTS) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(event);
        }
        return sb.toString();
    }

    /**
     * 根据时间线事件类型返回对应的累加表(M5,内部工具)。
     *
     * @param type 事件类型
     * @return 对应的累加表；未知类型返回 null（仅记时间线，不累加）
     */
    private static Map<String, Long> typeToTimings(String type) {
        switch (type) {
            case "enable": return PLUGIN_ENABLE_TIMINGS;
            case "load": return PLUGIN_LOAD_TIMINGS;
            case "library": return LIBRARY_TIMINGS;
            case "worldCreate": return WORLD_TIMINGS;
            case "configLoad": return CONFIG_TIMINGS;
            case "eventRegister": return EVENT_TIMINGS;
            case "commandRegister": return COMMAND_TIMINGS;
            default: return null;
        }
    }

    /**
     * 记录一次时间线事件(M5,内部工具)。
     *
     * @param type          事件类型
     * @param name          被 hook 的对象名
     * @param startNanosRel 开始时刻（相对 premain 的纳秒偏移）
     * @param endNanosRel   结束时刻（相对 premain 的纳秒偏移）
     */
    private static void recordTimelineEvent(String type, String name, long startNanosRel, long endNanosRel) {
        if (type == null || type.isEmpty() || name == null || name.isEmpty()) {
            return;
        }
        TIMELINE_EVENTS.add(type + '|' + name + '|' + startNanosRel + '|' + endNanosRel);
    }

    /**
     * 向耗时累加表写入一次计时（内部公用）。
     *
     * <p>统一处理名称归一化（{@code null}/空 → {@code "<unknown>"}）与原子累加。
     *
     * @param timings 目标累加表
     * @param name    项名
     * @param costMs  本次耗时（毫秒）
     */
    private static void accumulate(Map<String, Long> timings, String name, long costMs) {
        String key = (name == null || name.isEmpty()) ? "<unknown>" : name;
        // ConcurrentHashMap 的 merge 是原子的，无需外部加锁即可安全累加。
        timings.merge(key, costMs, Long::sum);
    }

    /**
     * 将耗时累加表序列化为 {@code name=ms;...} 字符串（内部公用）。
     *
     * @param timings 待序列化的累加表
     * @return 形如 {@code "A=12;B=3"} 的串；空表时为空串
     */
    private static String serializeTimings(Map<String, Long> timings) {
        if (timings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> entry : timings.entrySet()) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }
}
