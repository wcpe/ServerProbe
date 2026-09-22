package top.wcpe.mc.plugin.serverprobe.ioc.scan

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import taboolib.common.platform.Platform

/**
 * [ComponentVisitor.platformFromPackage] 纯函数单测(技术债 #26-⑦:plugin 模块补测试)。
 *
 * 该函数是"JDK8 注解解析失败时的平台兜底口径"(审查发现 integration.* 不在三前缀内,
 * 兜底语义为默认放行 + 类自身 @PostConstruct 二次兜底)——用例锁死该行为,防无感知漂移。
 */
class ComponentVisitorPlatformTest {

    /** bukkit. 前缀:仅 Bukkit 平台放行。 */
    @Test
    fun `bukkit 前缀仅 bukkit 平台放行`() {
        val cls = "top.wcpe.mc.plugin.serverprobe.bukkit.collector.BukkitServerCollector"
        assertTrue(ComponentVisitor.platformFromPackage(cls, Platform.BUKKIT))
        assertFalse(ComponentVisitor.platformFromPackage(cls, Platform.BUNGEE))
        assertFalse(ComponentVisitor.platformFromPackage(cls, Platform.VELOCITY))
    }

    /** bungee. 前缀:仅 BungeeCord 放行。 */
    @Test
    fun `bungee 前缀仅 bungee 平台放行`() {
        val cls = "top.wcpe.mc.plugin.serverprobe.bungee.mcp.BungeePlatformControl"
        assertTrue(ComponentVisitor.platformFromPackage(cls, Platform.BUNGEE))
        assertFalse(ComponentVisitor.platformFromPackage(cls, Platform.BUKKIT))
    }

    /** velocity. 前缀:仅 Velocity 放行。 */
    @Test
    fun `velocity 前缀仅 velocity 平台放行`() {
        val cls = "top.wcpe.mc.plugin.serverprobe.velocity.mcp.VelocityPlatformControl"
        assertTrue(ComponentVisitor.platformFromPackage(cls, Platform.VELOCITY))
        assertFalse(ComponentVisitor.platformFromPackage(cls, Platform.BUKKIT))
    }

    /** integration. 前缀(审查已证的兜底盲区):默认放行——语义变更须先评审双层防御是否仍然成立。 */
    @Test
    fun `integration 前缀默认放行`() {
        val cls = "top.wcpe.mc.plugin.serverprobe.integration.multicurrencyeconomy.EconomyProvider"
        assertTrue(ComponentVisitor.platformFromPackage(cls, Platform.BUKKIT))
        assertTrue(ComponentVisitor.platformFromPackage(cls, Platform.BUNGEE))
    }

    /** core. 与 api. 等无平台归属的包:默认放行(任意平台共用组件)。 */
    @Test
    fun `无平台归属包默认放行`() {
        assertTrue(ComponentVisitor.platformFromPackage("top.wcpe.mc.plugin.serverprobe.core.alert.AlertEngine", Platform.BUKKIT))
        assertTrue(ComponentVisitor.platformFromPackage("top.wcpe.mc.plugin.serverprobe.core.alert.AlertEngine", Platform.VELOCITY))
    }
}
