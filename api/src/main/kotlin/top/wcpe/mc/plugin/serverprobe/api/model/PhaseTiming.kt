package top.wcpe.mc.plugin.serverprobe.api.model

import top.wcpe.mc.plugin.serverprobe.api.enums.StartupPhase

/**
 * 单个启动分段的耗时(FR1.4)。
 *
 * @property phase 启动生命周期分段。
 * @property durationMs 该分段耗时(毫秒)。
 */
data class PhaseTiming(
    val phase: StartupPhase,
    val durationMs: Long
)
