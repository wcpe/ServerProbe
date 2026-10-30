package top.wcpe.mc.plugin.serverprobe.bukkit.mcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.mcp.McpToolProvider
import top.wcpe.mc.plugin.serverprobe.core.mcp.PluginClassResolver
import top.wcpe.mc.plugin.serverprobe.core.mcp.PluginClassResolverRegistry
import top.wcpe.mc.plugin.serverprobe.core.mcp.PluginMetadataProvider
import top.wcpe.mc.plugin.serverprobe.core.mcp.PluginMetadataProviderRegistry

/**
 * BukkitPluginMetadataProvider 的行为测试。
 *
 * <h2>为什么不靠反射</h2>
 * <p>以前这里的做法是反射查“注入的是不是 `PluginMetadataProviderRegistry` 类型”，
 * 再读源码文本断言 `contains("@PostConstruct")`。前者只证明字段声明，后者改注释就假失败。</p>
 *
 * <h2>真正要守的是什么</h2>
 * <p>一条历史真机事故：本类曾**兜底注册了同名工具**，按注册序覆盖了 core 的工具路由，
 * 导致 `plugin_*` 全部落到降级实现。所以关键在于：
 * <b>它只往两个数据契约注册，绝不碰工具面</b>。</p>
 *
 * <p>现在直接驱动真实的注册过程，然后去注册表里看结果。</p>
 */
class BukkitPluginMetadataProviderContractTest {

    /** 造一个“运行在 Bukkit 且两个注册表都就绪”的 provider。 */
    private fun providerWithRegistries(): Triple<
        BukkitPluginMetadataProvider,
        PluginMetadataProviderRegistry,
        PluginClassResolverRegistry,
        > {
        val metadata = PluginMetadataProviderRegistry()
        val resolver = PluginClassResolverRegistry()
        val provider = BukkitPluginMetadataProvider()
        provider.metadataRegistry = metadata
        provider.classResolverRegistry = resolver
        provider.isBukkitPlatform = { true }
        return Triple(provider, metadata, resolver)
    }

    // ────────────────── 契约声明 ──────────────────

    @Test
    @DisplayName("实现两个数据契约，且**不实现** MCP 工具提供者")
    fun `不实现工具提供者`() {
        val type = BukkitPluginMetadataProvider::class.java

        assertTrue(PluginMetadataProvider::class.java.isAssignableFrom(type))
        assertTrue(PluginClassResolver::class.java.isAssignableFrom(type))
        assertFalse(
            McpToolProvider::class.java.isAssignableFrom(type),
            "工具面由 core 的 PluginScopedMcpToolProvider 统一注册；本类实现它会让兜底注册覆盖 core 路由（真机事故）",
        )
    }

    // ────────────────── 注册行为（核心） ──────────────────

    @Test
    @DisplayName("register() 把自身登记进两个数据契约注册表")
    fun `注册后两个契约都能查到自身`() {
        val (provider, metadata, resolver) = providerWithRegistries()

        provider.register()

        assertSame(provider, metadata.current(), "元数据契约应可查到本实例")
        assertSame(provider, resolver.current(), "类解析契约应可查到本实例")
    }

    @Test
    @DisplayName("unregister() 从两个注册表撤回自身")
    fun `注销后两个契约都不再持有`() {
        val (provider, metadata, resolver) = providerWithRegistries()
        provider.register()

        provider.unregister()

        assertNull(metadata.current(), "注销后元数据契约不得仍持有本实例")
        assertNull(resolver.current(), "注销后类解析契约不得仍持有本实例")
    }

    @Test
    @DisplayName("register() 幂等：重复注册不抛且仍是同一实例")
    fun `重复注册幂等`() {
        val (provider, metadata, resolver) = providerWithRegistries()

        provider.register()
        provider.register()

        assertSame(provider, metadata.current())
        assertSame(provider, resolver.current())
    }

    // ────────────────── 平台门 ──────────────────

    @Test
    @DisplayName("非 Bukkit 平台时不注册任何东西")
    fun `非Bukkit平台不注册`() {
        val (provider, metadata, resolver) = providerWithRegistries()
        provider.isBukkitPlatform = { false }

        provider.register()

        assertNull(metadata.current(), "非 Bukkit 端不得注册元数据契约")
        assertNull(resolver.current(), "非 Bukkit 端不得注册类解析契约")
    }

    @Test
    @DisplayName("注册表保留首个实现，后注册的不顶掉已有")
    fun `注册表保留首个`() {
        // 历史 bug 的直接回归：兜底实现后注册会顶掉真实实现。
        val registry = PluginMetadataProviderRegistry()
        val first = BukkitPluginMetadataProvider()
        val second = BukkitPluginMetadataProvider()
        first.metadataRegistry = registry
        first.isBukkitPlatform = { true }
        second.isBukkitPlatform = { true }

        registry.register(first)
        registry.register(second)

        assertSame(first, registry.current(), "重复注册时保留首个，后注册的不得顶掉已有实现")
    }
}
