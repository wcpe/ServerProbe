package top.wcpe.mc.plugin.serverprobe.velocity.forensics

/** Velocity 玩家连接取证的最小生命周期契约。 */
interface VelocityNettyForensicsLifecycle {

    fun attach(player: Any)

    fun detach(player: Any)
}
