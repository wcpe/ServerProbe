package top.wcpe.mc.plugin.serverprobe.bukkit.forensics

import org.bukkit.entity.Player

/** Bukkit 玩家连接取证的最小生命周期契约。 */
interface BukkitNettyForensicsLifecycle {

    fun attach(player: Player)

    fun detach(player: Player)
}
