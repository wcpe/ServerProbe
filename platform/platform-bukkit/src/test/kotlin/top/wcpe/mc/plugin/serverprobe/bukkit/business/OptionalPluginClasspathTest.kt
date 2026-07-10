package top.wcpe.mc.plugin.serverprobe.bukkit.business

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLClassLoader

/**
 * 可选业务插件缺席时的类加载回归测试。
 *
 * ServerProbe 必须能在未安装 MultiCurrencyEconomy / AllinInventorySync 的独立服上启用；IoC 扫描会对候选类
 * 执行反射，若 @Service / 监听器的方法描述符直接暴露可选插件 API 类型，缺依赖环境会在 declaredMethods 阶段
 * 抛 NoClassDefFoundError，进而造成后续 Bean 扫描缺失。
 */
class OptionalPluginClasspathTest {

    /** 无业务插件 API 时，软依赖相关类仍应允许 IoC 读取方法列表。 */
    @Test
    fun `缺少业务插件 API 时软依赖类可被反射扫描`() {
        val urls = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { it.contains("allininventorysync", ignoreCase = true) }
            .filterNot { it.contains("multicurrencyeconomy", ignoreCase = true) }
            .map { File(it).toURI().toURL() }
            .toTypedArray()

        URLClassLoader(urls, null).use { loader ->
            val classes = listOf(
                "top.wcpe.mc.plugin.serverprobe.bukkit.business.EconomyProvider",
                "top.wcpe.mc.plugin.serverprobe.bukkit.business.BukkitEconomyEventListener",
                "top.wcpe.mc.plugin.serverprobe.bukkit.business.InventoryProvider",
                "top.wcpe.mc.plugin.serverprobe.bukkit.business.BukkitInventoryEventListener",
            )
            for (className in classes) {
                val type = Class.forName(className, false, loader)
                assertDoesNotThrow({ type.declaredMethods.toList() }, "$className 不应暴露缺席的可选 API 方法描述符")
            }
        }
    }
}
