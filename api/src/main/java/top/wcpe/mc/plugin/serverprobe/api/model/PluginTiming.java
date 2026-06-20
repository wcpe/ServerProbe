package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * 单个插件的 onEnable 耗时(FR1.2,慢插件榜数据项)。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public final class PluginTiming {
    /** 插件名称。 */
    String name;
    /** 该插件 onEnable 耗时(毫秒)。 */
    long enableMs;
}
