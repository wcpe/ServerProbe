package top.wcpe.mc.plugin.serverprobe.api.model;

import top.wcpe.mc.plugin.serverprobe.api.enums.ProbePlatform;

/**
 * 启动画像:一次启动的结构化耗时报告(FR1)。
 *
 * 汇总端到端总时长、各生命周期分段、慢插件榜、世界加载耗时与 JVM 参数快照,
 * 用于"开服慢"定位及与上次/基线对比。每次启动落盘为一份 JSON(详见 PRD §9)。
 *
 * **启动 agent 增强字段(A3/M5,向后兼容)**:末尾的 agentAttached/premainNanos/
 * agentPluginLoadTimings/agentPluginEnableTimings/libraryTimings/mainThreadHotspots/
 * timelineEvents/threadStacks/configTimings/eventTimings/commandTimings
 * 仅当 JVM 以 `-javaagent:plugins/ServerProbe.jar` 挂载启动 agent 时才有值;
 * **未挂载时全为默认**(agentAttached = false,其余为 null)。
 * agent 在 system ClassLoader 中从字节码插桩与栈采样直接测得,其逐插件 load/enable 耗时
 * **精度高于**基于 `logs/latest.log` 时间差的日志解析(pluginTimings);二者并存,展示侧择优。
 * 这些字段均带默认值,故旧档(无这些字段)反序列化时降级为默认值,不破坏既有落盘格式。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public final class StartupProfile {
    /** 落盘格式版本号,M5 起 = 3(A3 = 2,M1 = 1);用于格式演进与向后兼容。 */
    int schemaVersion;
    /**
     * 实例标识(自动生成或配置覆盖的 server-name)。
     * 实现保证恒有值:配置未指定时自动生成实例 ID(故为非空)。
     */
    String serverId;
    /** 启动来源平台。 */
    ProbePlatform platform;
    /** Minecraft 版本(如 "1.21.4");代理端为其对应版本标识。 */
    String mcVersion;
    /** JVM 启动时刻(epoch 毫秒)。 */
    long jvmStartTimeMs;
    /** 端到端启动总时长(毫秒)。 */
    long totalMs;
    /** 各启动分段耗时明细。 */
    java.util.List<PhaseTiming> phaseTimings;
    /** 各插件 onEnable 耗时明细(慢插件榜数据源;来自日志解析)。 */
    java.util.List<PluginTiming> pluginTimings;
    /** 各世界加载耗时明细。 */
    java.util.List<WorldTiming> worldTimings;
    /** JVM 启动参数快照。 */
    java.util.List<String> jvmArgs;
    /** 本画像生成时刻(epoch 毫秒)。 */
    long createdAtMs;
    /** 启动 agent 是否挂载;true 时下列 agent 字段方有意义。 */
    @lombok.Builder.Default
    Boolean agentAttached = false;
    /** agent premain 执行时刻的 `System.nanoTime()`;未挂载时为 null。 */
    Long premainNanos;
    /** agent 实测的逐插件 onLoad 耗时(精度高于日志解析);未挂载时为 null。 */
    java.util.List<PluginTiming> agentPluginLoadTimings;
    /** agent 实测的逐插件 onEnable 耗时(精度高于日志解析);未挂载时为 null。 */
    java.util.List<PluginTiming> agentPluginEnableTimings;
    /** 逐插件库下载/加载耗时("首次启动慢"隐形大头);未挂载时为 null。 */
    java.util.List<LibraryTiming> libraryTimings;
    /** 启动期"Server thread"主线程栈采样热点榜;未挂载时为 null。 */
    java.util.List<StackHotspot> mainThreadHotspots;
    /**
     * 启动期逐事件时间线(M5,agent 增强);每个被 hook 的方法调用的精确
     * 开始/结束时刻(纳秒级、相对 premain),据此生成时间线瀑布图。未挂载时为 null。
     */
    java.util.List<TimelineEvent> timelineEvents;
    /**
     * 多线程折叠栈采样(M5,agent 增强);按线程分组的完整调用栈,
     * 用于生成真正的多层、多线程火焰图。未挂载时为 null。
     */
    java.util.List<ThreadStackProfile> threadStacks;
    /** agent 实测的逐配置文件加载耗时(M5);未挂载时为 null。 */
    java.util.List<StartupItemTiming> configTimings;
    /** agent 实测的逐插件事件注册耗时(M5);未挂载时为 null。 */
    java.util.List<StartupItemTiming> eventTimings;
    /** agent 实测的逐命令注册耗时(M5);未挂载时为 null。 */
    java.util.List<StartupItemTiming> commandTimings;
    /** 栈采样周期(毫秒,M5);用于由"采样命中数 × 周期"估算各调用栈的墙钟耗时。未挂载时为 null。 */
    Long sampleIntervalMs;
    /** 启动期对外网络外呼快照(M5);定格画像时缓冲内的外呼记录,用于报告呈现。未挂载时为 null。 */
    java.util.List<HttpCall> httpCalls;
}
