package top.wcpe.mc.plugin.serverprobe.api.model

/**
 * 单个线程的折叠栈采样集合(M5,启动 agent 增强数据)。
 *
 * 由启动 agent 的守护线程在启动期周期性采样指定线程的**完整调用栈**,按调用路径聚合得到;
 * 是生成该线程火焰图的数据源。一个线程对应一棵火焰图树:把 [stacks] 中所有 [FoldedStack]
 * 逐层并入(同层按帧名归并、计数累加)即得。
 *
 * 仅当挂载启动 agent(`-javaagent:plugins/ServerProbe.jar`)时才有此数据
 * (详见 [StartupProfile.threadStacks])。
 *
 * @property threadName 线程名(如 "Server thread"、"Netty Server IO #1")。
 * @property stacks 该线程的折叠栈列表(已按命中降序)。
 */
data class ThreadStackProfile(
    val threadName: String,
    val stacks: List<FoldedStack>
)
