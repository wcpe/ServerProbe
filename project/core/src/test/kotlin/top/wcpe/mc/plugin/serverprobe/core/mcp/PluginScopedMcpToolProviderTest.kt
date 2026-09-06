package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.model.PluginCpuMetric
import top.wcpe.mc.plugin.serverprobe.core.cpu.PluginClassLoaderRegistry
import top.wcpe.mc.plugin.serverprobe.core.json.JsonObject
import java.net.URL
import java.net.URLClassLoader

/**
 * [PluginScopedMcpToolProvider] 插件维度诊断工具单测(FR-15)。
 *
 * 覆盖:入参校验、归属线程过滤、线程排序与有界上限、类清单降级、CPU 归因降级与零样本。
 * 全部经可替换的注入点(threadSource/cpuEnabled/cpuSnapshot)与假注册表驱动,不依赖真机环境。
 */
class PluginScopedMcpToolProviderTest {

    @Suppress("LongParameterList")
    private fun provider(
        registry: PluginClassLoaderRegistry = PluginClassLoaderRegistry(),
        metadata: PluginMetadataProviderRegistry = PluginMetadataProviderRegistry(),
        resolver: PluginClassResolverRegistry = PluginClassResolverRegistry(),
        cpuEnabled: Boolean = true,
        cpuSamples: List<PluginCpuMetric> = emptyList(),
        threads: List<PluginThreadSample> = emptyList(),
    ): PluginScopedMcpToolProvider {
        val provider = PluginScopedMcpToolProvider()
        provider.classLoaderRegistry = registry
        provider.metadataRegistry = metadata
        provider.classResolverRegistry = resolver
        provider.cpuEnabled = { cpuEnabled }
        provider.cpuSnapshot = { cpuSamples }
        provider.threadSource = { threads }
        return provider
    }
    /** 造一个可解析本测试类的插件 ClassLoader(同 PluginClassLoaderRegistryTest 手法)。 */
    private fun loaderFor(name: String): PluginClassLoaderRegistry {
        val registry = PluginClassLoaderRegistry()
        registry.register(name, URLClassLoader(arrayOf<URL>(), javaClass.classLoader))
        return registry
    }

    @Test
    fun `插件清单返回元数据并标注平台支持`() {
        val metadata = PluginMetadataProviderRegistry()
        metadata.register(object : PluginMetadataProvider {
            override fun list(): List<PluginMeta> = listOf(
                PluginMeta("TestPlugin", "1.0", true, "PluginClassLoader(已注册=true)"),
            )
        })

        val result = provider(metadata = metadata).call("plugin_list", null)

        assertEquals(true, result["available"])
        assertEquals(false, result["platformUnsupported"])
        val plugins = result["plugins"] as List<*>
        assertEquals(1, plugins.size)
        val meta = plugins[0] as Map<*, *>
        assertEquals("TestPlugin", meta["name"])
        assertEquals("1.0", meta["version"])
        assertEquals(true, meta["enabled"])
        assertEquals("PluginClassLoader(已注册=true)", meta["loaderSummary"])
    }

    @Test
    fun `未注册元数据提供者时返回空并标注平台不支持`() {
        val result = provider().call("plugin_list", null)

        assertEquals(false, result["available"])
        assertEquals(true, result["platformUnsupported"])
        assertEquals(emptyList<Any?>(), result["plugins"])
    }

    @Test
    fun `缺少插件名参数时抛出校验错误`() {
        val registry = loaderFor("TestPlugin")
        val provider = provider(registry = registry)

        listOf("plugin_classes", "plugin_threads", "plugin_cpu").forEach { tool ->
            assertThrows(IllegalArgumentException::class.java) { provider.call(tool, null) }
        }
    }

    @Test
    fun `插件名不在已注册集合时抛出校验错误`() {
        val registry = loaderFor("TestPlugin")
        val provider = provider(registry = registry)

        listOf("plugin_classes", "plugin_threads", "plugin_cpu").forEach { tool ->
            assertThrows(IllegalArgumentException::class.java) {
                provider.call(tool, arguments("plugin" to "UnknownPlugin"))
            }
        }
    }

    @Test
    fun `线程归属过滤只保留命中插件的线程并保留全部帧`() {
        val registry = loaderFor("TestPlugin")
        val hitFrame = StackTraceElement(PluginScopedMcpToolProviderTest::class.java.name, "run", "Test.kt", 10)
        val foreignFrame = StackTraceElement("com.other.Unknown", "run", "Other.kt", 1)
        val threads = listOf(
            PluginThreadSample(1L, "插件线程", "RUNNABLE", listOf(hitFrame, foreignFrame), 100L),
            PluginThreadSample(2L, "无关线程", "RUNNABLE", listOf(foreignFrame), 50L),
        )

        val result = provider(registry = registry, threads = threads).call("plugin_threads", arguments("plugin" to "TestPlugin"))

        val rows = result["threads"] as List<*>
        assertEquals(1, rows.size)
        val row = rows[0] as Map<*, *>
        assertEquals(1L, row["id"])
        assertEquals(1, row["hitFrames"])
        // 命中线程保留全部帧(含未归属的公共帧)
        assertEquals(2, (row["stack"] as List<*>).size)
    }

    @Test
    fun `线程排序按命中帧数降序再按CPU时间降序`() {
        val registry = loaderFor("TestPlugin")
        val self = PluginScopedMcpToolProviderTest::class.java.name
        val threads = listOf(
            PluginThreadSample(1L, "低命中高CPU", "RUNNABLE", listOf(StackTraceElement(self, "a", "T.kt", 1)), 999L),
            PluginThreadSample(
                2L, "高命中低CPU", "RUNNABLE",
                listOf(StackTraceElement(self, "a", "T.kt", 1), StackTraceElement(self, "b", "T.kt", 2)), 100L,
            ),
            PluginThreadSample(
                3L, "同命中更CPU", "RUNNABLE",
                listOf(StackTraceElement(self, "a", "T.kt", 1), StackTraceElement(self, "b", "T.kt", 2)), 500L,
            ),
        )

        val result = provider(registry = registry, threads = threads).call("plugin_threads", arguments("plugin" to "TestPlugin"))

        val rows = result["threads"] as List<*>
        assertEquals(listOf(3L, 2L, 1L), rows.map { (it as Map<*, *>)["id"] as Long })
        assertEquals(listOf(2, 2, 1), rows.map { (it as Map<*, *>)["hitFrames"] as Int })
    }

    @Test
    fun `线程结果有界且单线程栈帧截断`() {
        val registry = loaderFor("TestPlugin")
        val self = PluginScopedMcpToolProviderTest::class.java.name
        // 300 个线程全部命中,每线程 100 帧,验证 ≤128 线程与 ≤64 帧
        val threads = (1L..300L).map { id ->
            PluginThreadSample(id, "线程$id", "RUNNABLE", (1..100).map { i -> StackTraceElement(self, "m$i", "T.kt", i) }, id)
        }

        val result = provider(registry = registry, threads = threads).call("plugin_threads", arguments("plugin" to "TestPlugin"))

        val rows = result["threads"] as List<*>
        assertTrue(rows.size <= NativeThreadDiagnostics.MAX_THREADS)
        rows.forEach { row ->
            assertTrue(((row as Map<*, *>)["stack"] as List<*>).size <= 64)
        }
    }

    @Test
    fun `插件类清单返回可解析类并标记截断`() {
        val registry = loaderFor("TestPlugin")
        val resolver = PluginClassResolverRegistry()
        resolver.register(object : PluginClassResolver {
            override fun enumerate(pluginName: String): PluginClassEnumeration =
                PluginClassEnumeration.success(listOf("a.A", "b.B"), truncated = true)
        })

        val result = provider(registry = registry, resolver = resolver).call("plugin_classes", arguments("plugin" to "TestPlugin"))

        assertEquals(true, result["available"])
        assertEquals(listOf("a.A", "b.B"), result["classes"])
        assertEquals(2, result["count"])
        assertEquals(true, result["truncated"])
    }

    @Test
    fun `无法枚举插件类时返回降级说明`() {
        val registry = loaderFor("TestPlugin")

        val result = provider(registry = registry).call("plugin_classes", arguments("plugin" to "TestPlugin"))

        assertEquals(false, result["available"])
        assertTrue((result["reason"] as String).contains("arthas"))
    }

    @Test
    fun `CPU归因未启用时返回降级说明`() {
        val registry = loaderFor("TestPlugin")

        val result = provider(registry = registry, cpuEnabled = false).call("plugin_cpu", arguments("plugin" to "TestPlugin"))

        assertEquals(false, result["available"])
        assertTrue((result["reason"] as String).contains("cpu.enabled"))
    }

    @Test
    fun `CPU归因零样本返回空列表并标注`() {
        val registry = loaderFor("TestPlugin")

        val result = provider(registry = registry, cpuSamples = emptyList()).call("plugin_cpu", arguments("plugin" to "TestPlugin"))

        assertEquals(true, result["available"])
        assertEquals(emptyList<Any?>(), result["samples"])
        assertEquals(true, result["zeroSamples"])
    }

    @Test
    fun `CPU归因按插件过滤窗口样本`() {
        val registry = loaderFor("TestPlugin")
        val samples = listOf(
            PluginCpuMetric.builder().plugin("TestPlugin").sampleCount(10).percent(50.0).build(),
            PluginCpuMetric.builder().plugin("OtherPlugin").sampleCount(10).percent(50.0).build(),
        )

        val result = provider(registry = registry, cpuSamples = samples).call("plugin_cpu", arguments("plugin" to "TestPlugin"))

        assertEquals(true, result["available"])
        val rows = result["samples"] as List<*>
        assertEquals(1, rows.size)
        val row = rows[0] as Map<*, *>
        assertEquals("TestPlugin", row["plugin"])
        assertEquals(10L, row["sampleCount"])
        assertEquals(50.0, row["percent"])
        assertEquals(false, result["zeroSamples"])
    }

    private fun arguments(vararg values: Pair<String, Any?>): JsonObject = object : JsonObject {
        private val map = values.toMap()
        override fun getString(key: String, default: String): String = map[key]?.toString() ?: default
        override fun getInt(key: String, default: Int): Int = map[key] as? Int ?: default
        override fun getLong(key: String, default: Long): Long = map[key] as? Long ?: default
        override fun getDouble(key: String, default: Double): Double = map[key] as? Double ?: default
        override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
        override fun getStringList(key: String): List<String> = emptyList()
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun getObject(key: String): JsonObject? = null
    }
}
