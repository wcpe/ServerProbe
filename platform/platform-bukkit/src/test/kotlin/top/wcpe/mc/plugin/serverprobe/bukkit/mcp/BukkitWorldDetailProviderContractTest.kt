package top.wcpe.mc.plugin.serverprobe.bukkit.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.bukkit.collector.BukkitWorldCollector
import top.wcpe.mc.plugin.serverprobe.bukkit.folia.FoliaObservedRegionService
import top.wcpe.mc.plugin.serverprobe.core.mcp.WorldDetail
import top.wcpe.mc.plugin.serverprobe.core.mcp.WorldDetailProvider
import java.io.File
import java.util.List

/**
 * BukkitWorldDetailProvider（FR-17）源码级契约测试。
 *
 * 不依赖真机 Bukkit 环境：以反射校验接口与注入字段类型，以源码文本校验
 * 平台注解、IOC 服务注解与"复用采集器缓存、不重复遍历实体"的实现约束，
 * 并以"非 Bukkit 平台门返回空列表"覆盖纯 JVM 测试环境下的降级行为。
 */
class BukkitWorldDetailProviderContractTest {

    @Test
    fun `实现 core 的世界明细契约`() {
        assertTrue(WorldDetailProvider::class.java.isAssignableFrom(BukkitWorldDetailProvider::class.java))
        assertEquals(List::class.java, BukkitWorldDetailProvider::class.java.getMethod("details").returnType)
        // 复用采集器缓存与 region 服务，而非自行采集
        assertEquals(
            BukkitWorldCollector::class.java,
            BukkitWorldDetailProvider::class.java.getDeclaredField("worldCollector").type,
        )
        assertEquals(
            FoliaObservedRegionService::class.java,
            BukkitWorldDetailProvider::class.java.getDeclaredField("foliaObservedRegionService").type,
        )
    }

    @Test
    fun `仅在 Bukkit 平台作为 IOC 服务生效`() {
        val source = source()
        assertTrue(source.contains("@PlatformSide(Platform.BUKKIT)"))
        assertTrue(source.contains("@Service"))
        assertTrue(source.contains("@Inject"))
    }

    @Test
    fun `非 Bukkit 平台下明细为空列表`() {
        // 纯 JVM 测试环境非 Bukkit：平台门生效，无需注入任何依赖即可安全降级
        assertEquals(emptyList<WorldDetail>(), BukkitWorldDetailProvider().details())
    }

    @Test
    fun `复用采集器缓存而非直接遍历 Bukkit 实体`() {
        val source = source()
        assertTrue(source.contains("worldCollector.collect()"), "必须复用采集器缓存")
        assertTrue(source.contains("entitiesByType"), "实体类型分布必须透传采集器缓存口径")
        assertFalse(source.contains("world.entities"), "不得直接遍历 Bukkit 实体")
        assertFalse(source.contains("Bukkit.getWorlds()"), "不得自行枚举世界")
    }

    private fun source(): String =
        File("src/main/kotlin/top/wcpe/mc/plugin/serverprobe/bukkit/mcp/BukkitWorldDetailProvider.kt").readText()
}
