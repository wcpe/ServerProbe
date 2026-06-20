package top.wcpe.mc.plugin.serverprobe.api.model;

import top.wcpe.mc.plugin.serverprobe.api.enums.StartupPhase;

/**
 * 单个启动分段的耗时(FR1.4)。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public class PhaseTiming {
    /** 启动生命周期分段。 */
    StartupPhase phase;
    /** 该分段耗时(毫秒)。 */
    long durationMs;
}
