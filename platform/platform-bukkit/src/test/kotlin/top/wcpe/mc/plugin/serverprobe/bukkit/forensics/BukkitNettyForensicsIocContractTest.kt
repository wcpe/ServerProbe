package top.wcpe.mc.plugin.serverprobe.bukkit.forensics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BukkitNettyForensicsIocContractTest {

    @Test
    fun `监听器仅注入 Bukkit 取证生命周期契约`() {
        val field = BukkitNettyForensicsListener::class.java.getDeclaredField("forensics")

        assertEquals(
            "top.wcpe.mc.plugin.serverprobe.bukkit.forensics.BukkitNettyForensicsLifecycle",
            field.type.name,
        )
    }
}
