package top.wcpe.mc.plugin.serverprobe.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 跨 ClassLoader 数据桥（system ClassLoader 静态容器）。
 *
 * <p>本类由 agent（{@link ProbeAgent}）在 system ClassLoader 中加载并写入数据，插件侧（A3 阶段）
 * 必须通过 {@code Class.forName(name, true, ClassLoader.getSystemClassLoader())} 反射读取本类的
 * 静态数据；<b>严禁</b>在插件代码里直接 {@code import} 本类——那会被 PluginClassLoader 加载成
 * <b>另一份空数据</b>（system CL 与 PluginClassLoader 加载的是两个不同的 Class 实例），这是跨 CL
 * 通道的最大陷阱。
 *
 * <p>设计约束：
 * <ul>
 *   <li><b>纯 JDK</b>：只依赖 {@code java.*}，不触碰 kotlin / taboolib / bukkit（system CL 不可见这些）。</li>
 *   <li><b>纯 JDK 类型 getter</b>：对外只暴露 {@code long} / {@code String} 等基础类型，跨 CL 反射传基础类型最安全
 *       （复杂对象会因两侧 Class 不同而 {@code ClassCastException}）。</li>
 *   <li><b>线程安全</b>：插件 enable 计时由多线程（理论上）写入，用 {@link ConcurrentHashMap} 承载。</li>
 * </ul>
 *
 * <p>全部为静态字段/方法，这是 agent 与插件之间唯一的共享内存锚点，故采用静态容器形态而非实例。
 */
public final class ProbeAgentBridge {

    /** premain/agentmain 执行那一刻的 {@link System#nanoTime()}，用于与后续打点求相对耗时。 */
    private static volatile long premainNanos = 0L;

    /** JVM 启动时刻（毫秒，来自 {@code RuntimeMXBean#getStartTime()}），用于计算"开服总耗时"基准。 */
    private static volatile long jvmStartTimeMs = 0L;

    /** JVM 启动参数（来自 {@code RuntimeMXBean#getInputArguments()}），以空格拼接成单串，便于跨 CL 传递。 */
    private static volatile String jvmArgs = "";

    /**
     * 逐插件 enable 耗时累加表：键为插件名，值为累计毫秒数。
     *
     * <p>同名插件多次 enable（极少见）按累加处理，避免覆盖丢数。
     */
    private static final Map<String, Long> PLUGIN_ENABLE_TIMINGS = new ConcurrentHashMap<String, Long>();

    /**
     * 逐插件 load 耗时累加表：键为插件名，值为累计毫秒数（对应 {@code SimplePluginManager#loadPlugin} 出口）。
     *
     * <p>与 enable 表分离：load（onLoad 阶段）与 enable（onEnable 阶段）是启动生命周期的两个不同分段，
     * 慢插件定位需分别归因，故不可混表。
     */
    private static final Map<String, Long> PLUGIN_LOAD_TIMINGS = new ConcurrentHashMap<String, Long>();

    /**
     * 逐插件库下载/加载耗时累加表：键为插件名，值为累计毫秒数
     * （对应 {@code LibraryLoader#createLoader}，1.17+ 才存在）。
     *
     * <p>库下载常是首次启动慢的隐形大头（远程拉取 Maven 依赖），单列一表以便突出展示。
     */
    private static final Map<String, Long> LIBRARY_TIMINGS = new ConcurrentHashMap<String, Long>();

    /**
     * "Server thread" 主线程栈采样热点表：键为栈帧标识（{@code 类全名#方法名}），值为命中采样次数。
     *
     * <p>由 {@link StartupStackSampler} 守护线程周期性写入；用 {@link LongAdder} 承载高频自增，
     * 其在并发自增场景下吞吐优于 {@code AtomicLong}，且采样线程与读取侧（插件 A3）天然分离。
     * 同样置于本数据桥（system ClassLoader），保证插件侧可跨 CL 反射读取热点榜。
     */
    private static final Map<String, LongAdder> STACK_SAMPLE_HOTSPOTS = new ConcurrentHashMap<String, LongAdder>();

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
     * 记录一次插件 enable 耗时（由插桩注入的字节码在 {@code enablePlugin} 出口调用）。
     *
     * <p>同名插件累加；插件名为 {@code null} 或空时归并到 {@code "<unknown>"}，避免丢点。
     *
     * @param pluginName 插件名（来自 {@code Plugin#getName()}）
     * @param costMs     本次 enable 耗时（毫秒）
     */
    public static void recordPluginEnable(String pluginName, long costMs) {
        accumulate(PLUGIN_ENABLE_TIMINGS, pluginName, costMs);
    }

    /**
     * 导出逐插件 enable 耗时，序列化为简单字符串。
     *
     * <p>格式：{@code name1=ms1;name2=ms2}（无尾分号）。跨 CL 反射只传 {@link String} 这一基础类型，
     * 由插件侧自行解析，规避两侧 Class 不一致导致的反序列化问题。
     *
     * @return 形如 {@code "A=12;B=3"} 的耗时串；无数据时为空串
     */
    public static String getPluginEnableTimings() {
        return serializeTimings(PLUGIN_ENABLE_TIMINGS);
    }

    /**
     * 记录一次插件 load 耗时（由插桩注入的字节码在 {@code loadPlugin} 出口调用）。
     *
     * <p>语义同 {@link #recordPluginEnable(String, long)}，仅归入 load 表；插件名来自出口处返回的
     * {@code Plugin#getName()}（loadPlugin 入参仅为文件，插件名只能从返回值取）。
     *
     * @param pluginName 插件名（来自 {@code Plugin#getName()}）
     * @param costMs     本次 load 耗时（毫秒）
     */
    public static void recordPluginLoad(String pluginName, long costMs) {
        accumulate(PLUGIN_LOAD_TIMINGS, pluginName, costMs);
    }

    /**
     * 导出逐插件 load 耗时，序列化为简单字符串。
     *
     * @return 形如 {@code "A=12;B=3"} 的耗时串；无数据时为空串
     */
    public static String getPluginLoadTimings() {
        return serializeTimings(PLUGIN_LOAD_TIMINGS);
    }

    /**
     * 记录一次插件库下载/加载耗时（由插桩注入的字节码在 {@code LibraryLoader#createLoader} 出口调用）。
     *
     * @param pluginName 插件名（来自 {@code PluginDescriptionFile#getName()}）
     * @param costMs     本次库加载耗时（毫秒）
     */
    public static void recordLibraryLoad(String pluginName, long costMs) {
        accumulate(LIBRARY_TIMINGS, pluginName, costMs);
    }

    /**
     * 导出逐插件库加载耗时，序列化为简单字符串。
     *
     * @return 形如 {@code "A=120;B=30"} 的耗时串；无数据时为空串
     */
    public static String getLibraryTimings() {
        return serializeTimings(LIBRARY_TIMINGS);
    }

    /**
     * 记录一次主线程栈采样命中（由 {@link StartupStackSampler} 守护线程调用）。
     *
     * <p>对同一栈帧标识自增计数；空标识忽略。{@code computeIfAbsent} + {@link LongAdder#increment()}
     * 全程无显式锁，契合采样线程"只读栈、不持锁"的安全要求。
     *
     * @param frame 栈帧标识（{@code 类全名#方法名}）；为 {@code null} 或空时忽略
     */
    public static void recordStackSample(String frame) {
        if (frame == null || frame.isEmpty()) {
            return;
        }
        STACK_SAMPLE_HOTSPOTS.computeIfAbsent(frame, k -> new LongAdder()).increment();
    }

    /**
     * 导出主线程栈采样热点榜 Top-N，按命中次数降序序列化为字符串。
     *
     * <p>格式：{@code frame1=count1;frame2=count2}（无尾分号），与耗时串同构，便于插件侧统一解析。
     *
     * @param topN 取前若干热点；小于等于 0 时返回空串
     * @return 形如 {@code "a.b.C#m=120;d.E#n=88"} 的热点串；无数据时为空串
     */
    public static String getStackSampleHotspots(int topN) {
        if (topN <= 0 || STACK_SAMPLE_HOTSPOTS.isEmpty()) {
            return "";
        }
        // 拷贝出快照再排序：避免对并发 Map 的视图直接排序；LongAdder.sum() 读取当前累计值。
        List<Map.Entry<String, LongAdder>> entries =
                new ArrayList<Map.Entry<String, LongAdder>>(STACK_SAMPLE_HOTSPOTS.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> e.getValue().sum()).reversed());
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(topN, entries.size());
        for (int i = 0; i < limit; i++) {
            Map.Entry<String, LongAdder> entry = entries.get(i);
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue().sum());
        }
        return sb.toString();
    }

    /**
     * 向耗时累加表写入一次计时（内部公用）。
     *
     * <p>统一处理插件名归一化（{@code null}/空 → {@code "<unknown>"}）与原子累加，
     * 消除 enable/load/library 三表的重复代码。
     *
     * @param timings 目标累加表
     * @param name    插件名
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
     * <p>跨 CL 反射只传 {@link String} 这一基础类型，规避两侧 Class 不一致导致的反序列化问题。
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
