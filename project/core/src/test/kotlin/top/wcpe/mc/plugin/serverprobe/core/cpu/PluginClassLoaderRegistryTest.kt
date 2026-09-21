package top.wcpe.mc.plugin.serverprobe.core.cpu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URL
import java.net.URLClassLoader

/**
 * [PluginClassLoaderRegistry] 归并逻辑单测(FR2.6)。
 *
 * 归属判定以**定义加载器**为准:注册后类归属插件仅当该类由该加载器(或其子加载器)定义,
 * 经父委派"能加载"的 JDK/服务端类归 null,且"无归属"结果被缓存(二次查询不再遍历)。
 */
class PluginClassLoaderRegistryTest {

    @Test
    fun `已注册的类可归并到插件`() {
        val registry = PluginClassLoaderRegistry()
        registry.register("TestPlugin", definingLoader())

        val owner = registry.ownerOf(PluginClassLoaderRegistryTest::class.java.name)
        assertEquals("TestPlugin", owner)
    }

    /**
     * 构造"真正定义测试类"的加载器:URL 指向测试类所在目录、父加载器为引导加载器,
     * 由它 [ClassLoader.loadClass] 定义出的类其定义加载器即该加载器(模拟插件自有类)。
     */
    private fun definingLoader(): URLClassLoader =
        URLClassLoader(arrayOf(PluginClassLoaderRegistryTest::class.java.protectionDomain.codeSource.location), null)

    @Test
    fun `无法加载的类归 null`() {
        val registry = PluginClassLoaderRegistry()
        val loader = URLClassLoader(arrayOf<URL>(), javaClass.classLoader)
        registry.register("TestPlugin", loader)

        assertNull(registry.ownerOf("com.nonexistent.UnknownClass"))
        // 无归属结果应被缓存:再次查询仍为 null
        assertNull(registry.ownerOf("com.nonexistent.UnknownClass"))
    }

    @Test
    fun `未注册任何插件时全部归 null`() {
        val registry = PluginClassLoaderRegistry()
        assertNull(registry.ownerOf("java.lang.String"))
    }

    @Test
    fun `注销后类归属被清空`() {
        val registry = PluginClassLoaderRegistry()
        registry.register("TestPlugin", definingLoader())
        val className = PluginClassLoaderRegistryTest::class.java.name
        assertEquals("TestPlugin", registry.ownerOf(className))

        registry.unregister("TestPlugin")
        // 注销清空缓存,类不再归属任何插件
        assertNull(registry.ownerOf(className))
    }

    /**
     * 父委派不等于插件自有类:插件 ClassLoader 经父加载器能加载 **JDK 类**,但那不是插件贡献的类,
     * 不得把 JDK 类归属给插件(否则 CPU 归因会把全部 java.* 帧算到某个插件头上)。
     */
    @Test
    fun `父委派可见的 JDK 类不得归属插件`() {
        val registry = PluginClassLoaderRegistry()
        registry.register("TestPlugin", URLClassLoader(arrayOf<URL>(), javaClass.classLoader))
        assertNull(registry.ownerOf("java.lang.String"), "JDK 类不应归属任何插件(父委派≠插件自有类)")
    }

    @Test
    fun `插件名集合正确`() {
        val registry = PluginClassLoaderRegistry()
        val loader = URLClassLoader(arrayOf<URL>(), javaClass.classLoader)
        registry.register("A", loader)
        registry.register("B", loader)
        assertEquals(setOf("A", "B"), registry.pluginNames())
        assertTrue(registry.pluginNames().isNotEmpty())
    }
}
