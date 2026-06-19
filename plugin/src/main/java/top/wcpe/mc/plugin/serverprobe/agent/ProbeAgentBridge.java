package top.wcpe.mc.plugin.serverprobe.agent;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
     * HTTP/TCP 外呼监控开关（运行期常驻，<b>独立于启动窗口</b> {@link #profilingActive}）。
     *
     * <p>默认开;由插件读取配置后经 {@link #setHttpMonitorEnabled(boolean)} 设定。关闭时所有外呼 hook 入口
     * 立即返回(仅多一次 volatile 读),开销可忽略。
     */
    private static volatile boolean httpMonitorEnabled = true;

    /** 外呼记录单调序号:读取侧据此按游标做增量拉取(避免重复打印)。 */
    private static final AtomicLong HTTP_SEQ = new AtomicLong(0L);

    /** 外呼记录环形缓冲(有界):每条为制表符分隔的序列化串;超过 {@link #HTTP_CALLS_CAP} 淘汰最旧,防内存无界。 */
    private static final Queue<String> HTTP_CALLS = new ConcurrentLinkedQueue<String>();

    /** {@link #HTTP_CALLS} 的近似计数(O(1) 维护,避免对并发队列调 size() 的 O(n) 开销)。 */
    private static final AtomicInteger HTTP_COUNT = new AtomicInteger(0);

    /** 外呼记录上限。 */
    private static final int HTTP_CALLS_CAP = 2000;

    /** 单条 URL 最大记录长度(超长截断,防超大 query 撑爆日志)。 */
    private static final int URL_MAX_LEN = 2000;

    /**
     * 线程内 HttpURLConnection 调用深度:>0 表示正处于 HTTP 请求中。
     *
     * <p>用途:① Socket.connect hook 据此跳过"HTTP 请求自身的 socket"(已由 HTTP 通道记录),实现去重;
     * ② 配合 {@link #HTTP_RECORDING} 防止读取连接属性时再入 getInputStream 造成递归。
     */
    private static final ThreadLocal<int[]> HTTP_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    /** 线程内"正在生成外呼记录"标志:防止 {@code getResponseCode → getInputStream} 等再入导致递归记录。 */
    private static final ThreadLocal<boolean[]> HTTP_RECORDING = ThreadLocal.withInitial(() -> new boolean[1]);

    /** 取调用栈 Class[] 的辅助器(惰性创建);某 JDK 不支持时为 null,归因降级到包前缀匹配。 */
    private static volatile CallerResolver callerResolver;

    /** {@link #callerResolver} 是否已尝试初始化(避免每次外呼重复 try)。 */
    private static volatile boolean callerResolverInit;

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

    // ===================== HTTP/TCP 外呼监控(运行期常驻) =====================

    /**
     * 设置外呼监控开关(由插件读配置后调用)。
     *
     * @param enabled true 开启、false 关闭
     */
    public static void setHttpMonitorEnabled(boolean enabled) {
        httpMonitorEnabled = enabled;
    }

    /** @return 外呼监控是否开启 */
    public static boolean isHttpMonitorEnabled() {
        return httpMonitorEnabled;
    }

    /**
     * {@code HttpURLConnection.getInputStream} 入口钩子:标记进入 HTTP 请求(供 socket 去重 + 递归保护)。
     *
     * <p>由插桩字节码在方法<b>入口</b>调用。开销极小:开关关闭直接返回,否则仅自增线程内计数。
     */
    public static void httpEnter() {
        if (!httpMonitorEnabled) {
            return;
        }
        HTTP_DEPTH.get()[0]++;
    }

    /**
     * {@code HttpURLConnection.getInputStream} 出口钩子:在最外层调用且未递归时,读取连接信息生成一条外呼记录。
     *
     * <p>由插桩字节码在方法<b>所有出口</b>(正常返回 + 异常)调用。全程 {@code try/catch} 兜底——
     * 监控绝不影响业务网络调用。
     *
     * @param conn       发起请求的连接(插桩传入的 {@code this})
     * @param startNanos 入口时刻 {@code System.nanoTime()}
     */
    public static void httpExit(HttpURLConnection conn, long startNanos) {
        int[] depth = HTTP_DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
        if (!httpMonitorEnabled || conn == null) {
            return;
        }
        boolean[] recording = HTTP_RECORDING.get();
        if (recording[0]) {
            return; // 递归保护:正在记录(如 getResponseCode 触发的嵌套 getInputStream)则跳过
        }
        recording[0] = true;
        try {
            long durMs = (System.nanoTime() - startNanos) / 1_000_000L;
            recordHttp(conn, durMs);
        } catch (Throwable ignored) {
            // 监控绝不成为事故源:读取连接属性的任何异常一律吞掉
        } finally {
            recording[0] = false;
        }
    }

    /**
     * {@code java.net.Socket.connect} 出口钩子:记录一次原始 TCP 外呼(目标地址 + 调用栈)。
     *
     * <p>去重:若当前线程正处于 HttpURLConnection 请求中({@link #HTTP_DEPTH} > 0),说明这是 HTTP 自身的 socket,
     * 已由 {@link #httpExit} 以更丰富的 HTTP 语义记录,这里跳过。用于捕获绕过 HttpURLConnection 的客户端
     * (OkHttp/Apache/数据库等)的对外连接。
     *
     * @param socket     发起连接的 socket(插桩传入的 {@code this})
     * @param startNanos 入口时刻 {@code System.nanoTime()}
     */
    public static void recordSocketConnect(Socket socket, long startNanos) {
        if (!httpMonitorEnabled || socket == null) {
            return;
        }
        if (HTTP_DEPTH.get()[0] > 0) {
            return; // HTTP 请求自身的 socket,已记录,跳过
        }
        boolean[] recording = HTTP_RECORDING.get();
        if (recording[0]) {
            return;
        }
        recording[0] = true;
        try {
            long durMs = (System.nanoTime() - startNanos) / 1_000_000L;
            InetAddress addr = socket.getInetAddress();
            String host = addr == null ? "" : safe(addr.getHostAddress());
            int port = socket.getPort();
            // method=TCP 标识原始 socket 连接(无 HTTP 语义);url 记为 host:port
            appendHttpCall(durMs, "TCP", -1, false, host, host + ":" + port, "", captureFrames(), captureLoaderHashes());
        } catch (Throwable ignored) {
            // 同样兜底
        } finally {
            recording[0] = false;
        }
    }

    /**
     * 读取连接信息并落一条 HTTP 外呼记录。
     *
     * @param conn  连接
     * @param durMs 本次请求耗时(毫秒)
     */
    private static void recordHttp(HttpURLConnection conn, long durMs) {
        String method = safe(conn.getRequestMethod());
        URL u = conn.getURL();
        String url = u == null ? "" : redactUrl(u.toString());
        String host = u == null ? "" : safe(u.getHost());
        int code = -1;
        boolean error = false;
        try {
            // getInputStream 已成功返回时 responseCode 已解析、此处取缓存值不会再次发起请求;
            // 失败出口则可能取不到,标记 error。
            code = conn.getResponseCode();
        } catch (Throwable t) {
            error = true;
        }
        String headers = redactHeaders(conn.getRequestProperties());
        appendHttpCall(durMs, method, code, error, host, url, headers, captureFrames(), captureLoaderHashes());
    }

    /**
     * 序列化并追加一条外呼记录到环形缓冲(有界,超量淘汰最旧)。
     *
     * <p>格式(制表符分隔):{@code seq\tstartRelNanos\tdurMs\tmethod\tcode\terr(0/1)\thost\turl\theaders\tframes};
     * 其中 headers、frames 为 {@code \u0001} 连接的子列表。所有取值已 {@link #sanitize} 去除分隔符。
     */
    private static void appendHttpCall(long durMs, String method, int code, boolean error,
                                       String host, String url, String headers, String frames, String loaderHashes) {
        long seq = HTTP_SEQ.incrementAndGet();
        long relNanos = System.nanoTime() - premainNanos;
        String line = seq + "\t" + relNanos + "\t" + durMs + "\t" + method + "\t" + code
                + "\t" + (error ? "1" : "0") + "\t" + host + "\t" + url + "\t" + headers
                + "\t" + frames + "\t" + loaderHashes;
        HTTP_CALLS.add(line);
        if (HTTP_COUNT.incrementAndGet() > HTTP_CALLS_CAP && HTTP_CALLS.poll() != null) {
            HTTP_COUNT.decrementAndGet();
        }
    }

    /**
     * 导出序号大于 {@code sinceSeq} 的外呼记录(增量拉取),换行分隔。
     *
     * @param sinceSeq 上次已读到的最大序号;传 0 取全部缓冲
     * @return 序列化的外呼记录串;无新记录时为空串
     */
    public static String getHttpCallsSince(long sinceSeq) {
        if (HTTP_CALLS.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : HTTP_CALLS) {
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            long seq;
            try {
                seq = Long.parseLong(line.substring(0, tab));
            } catch (NumberFormatException ex) {
                continue;
            }
            if (seq > sinceSeq) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    /**
     * 捕获当前线程调用栈(用于"哪个插件/哪段代码触发"归因),保留至多 25 帧、跳过采样/agent 自身帧。
     *
     * @return {@code \u0001} 连接的 {@code 类全名#方法名} 串
     */
    private static String captureFrames() {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (StackTraceElement e : st) {
            String cn = e.getClassName();
            // 跳过取栈本身与本 agent 桥的帧(无归因价值);其余(含 JDK 网络栈)保留,供插件侧定位调用方
            if (cn.startsWith("java.lang.Thread") || cn.startsWith("top.wcpe.mc.plugin.serverprobe.agent.")) {
                continue;
            }
            sb.append(cn).append('#').append(e.getMethodName()).append('\u0001');
            if (++kept >= 25) {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 捕获调用栈各类的 ClassLoader 身份哈希(归因关键)。
     *
     * <p>用 {@link SecurityManager#getClassContext()}(JDK 8–21 通用,不需设为活动 SM)取栈上 Class[];自栈顶向下
     * 跳过 JDK 与本 agent 帧,按顺序收集其 ClassLoader 的 {@link System#identityHashCode}(去重、至多 12 个)。
     * 插件侧据此把外呼归因到"拥有该类的插件"——即便插件自身代码不在栈上(如连接池/驱动后台线程发起连接),
     * 其打包进插件的库类(mysql/h2/apache 等)的 ClassLoader 仍指向该插件。{@code identityHashCode} 与观察者所在
     * ClassLoader 无关,故 agent 与插件侧对同一 ClassLoader 对象取值一致,可跨 CL 直接比对。
     *
     * <p>{@link SecurityManager} 已弃用待移除;若某 JDK 上不可用则降级返回空串(归因回退到包前缀匹配)。
     *
     * @return 子分隔符连接的 ClassLoader 身份哈希(十进制)串;不可用/无应用类时为空串
     */
    private static String captureLoaderHashes() {
        CallerResolver resolver = resolver();
        if (resolver == null) {
            return "";
        }
        Class<?>[] ctx;
        try {
            ctx = resolver.context();
        } catch (Throwable t) {
            return "";
        }
        if (ctx == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        java.util.HashSet<Integer> seen = new java.util.HashSet<Integer>();
        int kept = 0;
        for (Class<?> c : ctx) {
            if (c == null) {
                continue;
            }
            String n = c.getName();
            if (n.startsWith("java.") || n.startsWith("sun.") || n.startsWith("jdk.") || n.startsWith("javax.")
                    || n.startsWith("top.wcpe.mc.plugin.serverprobe.agent.")) {
                continue;
            }
            ClassLoader cl = c.getClassLoader();
            if (cl == null) {
                continue; // bootstrap 加载的类无归属插件
            }
            int h = System.identityHashCode(cl);
            if (seen.add(h)) {
                if (sb.length() > 0) {
                    sb.append((char) 1);
                }
                sb.append(h);
                if (++kept >= 12) {
                    break;
                }
            }
        }
        return sb.toString();
    }

    /** 取(惰性创建、失败则缓存为 null)用于读取调用栈 Class[] 的 {@link CallerResolver}。 */
    private static CallerResolver resolver() {
        if (!callerResolverInit) {
            synchronized (ProbeAgentBridge.class) {
                if (!callerResolverInit) {
                    try {
                        callerResolver = new CallerResolver();
                    } catch (Throwable t) {
                        callerResolver = null; // 该 JDK 不支持 SecurityManager 子类化时降级
                    }
                    callerResolverInit = true;
                }
            }
        }
        return callerResolver;
    }

    /**
     * 借 {@link SecurityManager#getClassContext()} 读取当前调用栈的 Class 数组(用于归因)。
     *
     * <p>仅<b>实例化</b>并调用其受保护方法,<b>从不</b> {@code System.setSecurityManager} 设为活动 SM,
     * 故不触发 JDK 18+ 对设置 SM 的限制;这是 Log4j 等广泛使用的取调用方类技巧。
     */
    private static final class CallerResolver extends SecurityManager {
        Class<?>[] context() {
            return getClassContext();
        }
    }

    /**
     * 脱敏请求头:敏感头(authorization/cookie/token/secret/api-key/password 等)值打码为 {@code ***}。
     *
     * @param props {@code HttpURLConnection.getRequestProperties()} 结果
     * @return {@code \u0001} 连接的 {@code name=value} 串
     */
    private static String redactHeaders(Map<String, List<String>> props) {
        if (props == null || props.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : props.entrySet()) {
            String name = e.getKey();
            if (name == null) {
                continue;
            }
            String value = isSensitiveKey(name) ? "***" : safe(String.valueOf(e.getValue()));
            sb.append(safe(name)).append('=').append(value).append('\u0001');
        }
        return sb.toString();
    }

    /**
     * 脱敏 URL:对查询串中敏感参数的值打码;并截断超长 URL。
     *
     * @param url 原始 URL
     * @return 脱敏 + 截断后的 URL(已 {@link #sanitize})
     */
    private static String redactUrl(String url) {
        if (url == null) {
            return "";
        }
        int q = url.indexOf('?');
        String result;
        if (q < 0) {
            result = url;
        } else {
            String base = url.substring(0, q);
            String query = url.substring(q + 1);
            StringBuilder qb = new StringBuilder();
            for (String pair : query.split("&")) {
                if (qb.length() > 0) {
                    qb.append('&');
                }
                int eq = pair.indexOf('=');
                if (eq > 0 && isSensitiveKey(pair.substring(0, eq))) {
                    qb.append(pair, 0, eq + 1).append("***");
                } else {
                    qb.append(pair);
                }
            }
            result = base + "?" + qb;
        }
        if (result.length() > URL_MAX_LEN) {
            result = result.substring(0, URL_MAX_LEN) + "…";
        }
        return sanitize(result);
    }

    /** 判定键名(头名/参数名)是否敏感。 */
    private static boolean isSensitiveKey(String name) {
        String n = name.toLowerCase();
        return n.contains("authorization") || n.contains("cookie") || n.contains("token")
                || n.contains("secret") || n.contains("api-key") || n.contains("apikey")
                || n.contains("password") || n.contains("x-auth") || n.contains("access_key");
    }

    /** 去除分隔符(制表符/换行/\u0001),保证序列化不串字段。 */
    private static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').replace('\u0001', ' ');
    }

    /** {@link #sanitize} 的空安全包装。 */
    private static String safe(String s) {
        return s == null ? "" : sanitize(s);
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
