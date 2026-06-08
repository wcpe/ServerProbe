package top.wcpe.mc.plugin.serverprobe.api.enums

/**
 * 启动生命周期分段。
 *
 * 用于启动剖析(FR1.4)中对各阶段耗时进行归类。各分段语义对应插件启动生命周期的
 * 依次推进:常量初始化 → 初始化 → 加载 → 启用 → 服务器完全就绪。
 *
 * 注意:本枚举刻意**不依赖** TabooLib 的 `LifeCycle`,以保持 api 模块零平台/零框架依赖;
 * 由 core/platform 实现在装配时完成与具体框架生命周期的映射。
 *
 * - [CONST]:常量构造阶段。
 * - [INIT]:初始化阶段。
 * - [LOAD]:加载阶段(对应各插件 onLoad)。
 * - [ENABLE]:启用阶段(对应各插件 onEnable)。
 * - [ACTIVE]:服务器完全启动、对外就绪阶段。
 */
enum class StartupPhase {
    /** 常量构造阶段。 */
    CONST,

    /** 初始化阶段。 */
    INIT,

    /** 加载阶段(对应各插件 onLoad)。 */
    LOAD,

    /** 启用阶段(对应各插件 onEnable)。 */
    ENABLE,

    /** 服务器完全启动、对外就绪阶段。 */
    ACTIVE
}
