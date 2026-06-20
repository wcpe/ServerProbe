package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * 启动期通用"命名项耗时"(M5,启动 agent 增强数据)。
 *
 * 用于承载结构同构的三类启动瓶颈聚合:配置文件加载、事件注册、命令注册——三者均为
 * `名称 → 累计耗时(毫秒)` 的二元组,故共用同一模型而非各立一类(避免复制粘贴反模式)。
 * 具体语义由所在字段区分:StartupProfile.configTimings / StartupProfile.eventTimings /
 * StartupProfile.commandTimings。
 *
 * 这些聚合由 agent 在对应字节码 hook 出口测得;仅当挂载启动 agent
 * (`-javaagent:plugins/ServerProbe.jar`)时才有此数据。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public final class StartupItemTiming {
    /** 项名(配置文件名 / 插件名 / 命令名)。 */
    String name;
    /** 该项的累计耗时(毫秒)。 */
    long costMs;
}
