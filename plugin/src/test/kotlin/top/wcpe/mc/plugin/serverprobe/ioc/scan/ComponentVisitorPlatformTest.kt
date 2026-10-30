package top.wcpe.mc.plugin.serverprobe.ioc.scan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import taboolib.common.platform.Platform

/**
 * [ComponentVisitor.platformOfPackage] 纯函数单测（技术债 #26-⑦：plugin 模块补测试）。
 *
 * 该函数是「JDK8 注解解析失败时的平台兜底口径」：按模块包名约定判定所属平台，
 * 三前缀之外的包返回 null（= 不限定平台，默认放行 + 类自身 @PostConstruct 二次兜底）。
 * 用例锁死该行为，防无感知漂移。
 */
class ComponentVisitorPlatformTest {

    /** bukkit. 前缀 → Bukkit。 */
    @Test
    fun `bukkit 前缀归 bukkit 平台`() {
        val cls = "top.wcpe.mc.plugin.serverprobe.bukkit.collector.BukkitServerCollector"
        assertEquals(Platform.BUKKIT, ComponentVisitor.platformOfPackage(cls))
    }

    /** bungee. 前缀 → BungeeCord。 */
    @Test
    fun `bungee 前缀归 bungee 平台`() {
        val cls = "top.wcpe.mc.plugin.serverprobe.bungee.mcp.BungeePlatformControl"
        assertEquals(Platform.BUNGEE, ComponentVisitor.platformOfPackage(cls))
    }

    /** velocity. 前缀 → Velocity。 */
    @Test
    fun `velocity 前缀归 velocity 平台`() {
        val cls = "top.wcpe.mc.plugin.serverprobe.velocity.mcp.VelocityPlatformControl"
        assertEquals(Platform.VELOCITY, ComponentVisitor.platformOfPackage(cls))
    }

    /** 三前缀之外（core. / api. 等无平台归属的包）→ null，即不限定平台、默认放行。 */
    @Test
    fun `无平台归属包返回 null 表示默认放行`() {
        assertNull(ComponentVisitor.platformOfPackage("top.wcpe.mc.plugin.serverprobe.core.alert.AlertEngine"))
        assertNull(ComponentVisitor.platformOfPackage("top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi"))
    }
}
