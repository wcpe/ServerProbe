package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * 单个世界的加载耗时(FR1.3)。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public final class WorldTiming {
    /** 世界名称。 */
    String name;
    /** 该世界加载(含 spawn-chunk 预加载)耗时(毫秒)。 */
    long loadMs;
}
