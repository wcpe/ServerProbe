package top.wcpe.mc.plugin.serverprobe.api.model

/**
 * 启动期"Server thread"主线程栈采样热点(FR1,启动 agent 早期数据)。
 *
 * 由启动 agent 的守护线程在启动期周期性采样主线程调用栈,按栈帧聚合命中次数得到;
 * 命中越多代表该帧在启动期占用主线程时间越长,据此定位"开服慢"的主线程热点。
 * 仅当挂载启动 agent(`-javaagent:plugins/ServerProbe.jar`)时才有此数据
 * (详见 [StartupProfile.mainThreadHotspots])。
 *
 * @property frame 栈帧标识(`类全名#方法名`)。
 * @property sampleCount 该栈帧的采样命中次数。
 */
data class StackHotspot(
    val frame: String,
    val sampleCount: Long
)
