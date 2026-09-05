package top.wcpe.mc.plugin.serverprobe.bungee.forensics

/** BungeeCord 玩家连接取证的最小生命周期契约。 */
interface BungeeNettyForensicsLifecycle {

    fun attach(player: Any)

    fun detach(player: Any)
}
