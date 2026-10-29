package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.BridgeServices
import top.wcpe.mc.plugin.serverprobe.api.FacadeAssemblyTestHook
import top.wcpe.mc.plugin.serverprobe.api.Registration
import top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI
import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessHost
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionRequest
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainManifest

/**
 * `BusinessHostImpl` 的注册句柄语义。
 *
 * 覆盖外部插件最容易踩的四种情况：重复注册顶替、陈旧句柄保护、逐域隔离、重入不放大。
 */
class BusinessHostHandleTest {

    @AfterEach
    fun cleanup() {
        FacadeAssemblyTestHook.uninstall()
    }

    /** 从聚合信封里取 domains 段（起回执顶层多了 apiVersion）。 */
    @Suppress("UNCHECKED_CAST")
    private fun domainsOf(envelope: Map<String, Any?>): Map<String, Map<String, Any?>> =
        envelope["domains"] as Map<String, Map<String, Any?>>

    /** 简单的可配置 Provider。 */
    private class Stub(
        private val name: String,
        private val behavior: (String) -> ActionResult = { ActionResult.ok(it) },
    ) : BusinessProvider {
        override fun domain(): String = name
        override fun manifest(): DomainManifest = TestManifests.withActions(domain(), "ping")
        override fun dispatch(request: ActionRequest): ActionResult = behavior(request.action)
    }

    /** 同域重复注册：旧句柄失效为 REPLACED，且旧句柄 close 不误删新 Provider。 */
    @Test
    fun `同域重复注册顶替且旧句柄不误删新实例`() {
        val host = BusinessHostImpl()
        try {
            val first = host.register(Stub("demo"))
            assertTrue(first.getActive(), "首次注册应有效")

            val second = host.register(Stub("demo"))
            assertTrue(second.getActive(), "新注册应有效")
            assertFalse(first.getActive(), "旧句柄应失效")
            assertEquals(Registration.InactiveReason.REPLACED, first.getInactiveReason())

            // 关键：陈旧句柄 close 不得删除新 Provider
            first.close()
            assertEquals(setOf("demo"), host.domains(), "旧句柄 close 不应影响新 Provider")
            assertTrue(second.getActive(), "新句柄仍应有效")
        } finally {
            host.shutdown()
        }
    }

    /** 句柄 close 幂等，且注销后域消失。 */
    @Test
    fun `句柄关闭幂等且注销域`() {
        val host = BusinessHostImpl()
        try {
            val handle = host.register(Stub("demo"))
            handle.close()
            handle.close()
            assertFalse(handle.getActive())
            assertEquals(Registration.InactiveReason.CLOSED, handle.getInactiveReason())
            assertTrue(host.domains().isEmpty(), "注销后不应有域")
        } finally {
            host.shutdown()
        }
    }

    /** `unregister(provider)` 按实例比对：非当前注册者返回 false 且不移除。 */
    @Test
    fun `unregister 按实例比对`() {
        val host = BusinessHostImpl()
        try {
            val provider = Stub("demo")
            host.register(provider)
            val other = Stub("demo")

            assertFalse(host.unregister(other), "非当前注册者不应移除")
            assertEquals(setOf("demo"), host.domains(), "域应仍在")

            assertTrue(host.unregister(provider), "当前注册者应可移除")
            assertTrue(host.domains().isEmpty())
        } finally {
            host.shutdown()
        }
    }

    /** 非法注册参数返回失效句柄，不抛异常。 */
    @Test
    fun `非法注册参数返回失效句柄`() {
        val host = BusinessHostImpl()
        try {
            val nullHandle = host.register(null)
            assertFalse(nullHandle.getActive())
            assertEquals(Registration.InactiveReason.INVALID, nullHandle.getInactiveReason())

            val emptyDomain = host.register(object : BusinessProvider {
                override fun domain(): String = ""
                override fun manifest(): DomainManifest = TestManifests.empty(domain())
                override fun dispatch(request: ActionRequest): ActionResult =
                    ActionResult.ok()
            })
            assertFalse(emptyDomain.getActive(), "空 domain 应被拒绝")
        } finally {
            host.shutdown()
        }
    }

    /** 单域 manifest 抛异常不影响其它域（事故域隔离）。 */
    @Test
    fun `单域 manifest 异常被隔离`() {
        val host = BusinessHostImpl()
        try {
            host.register(object : BusinessProvider {
                override fun domain(): String = "broken"
                override fun manifest(): DomainManifest = error("boom")
                override fun dispatch(request: ActionRequest): ActionResult =
                    ActionResult.ok()
            })
            host.register(Stub("healthy"))

            val domains = domainsOf(host.manifest())
            assertEquals(2, domains.size, "两个域都应出现")
            assertTrue(domains["broken"]?.containsKey("error") == true, "坏域应呈现 error")
            assertTrue(domains["healthy"]?.containsKey("error") == false, "健康域不受影响")
        } finally {
            host.shutdown()
        }
    }

    /** Provider 返回 null 时降级为失败结果，不抛 NPE。 */
    @Test
    fun `Provider 返回 null 被兜住`() {
        val host = BusinessHostImpl()
        try {
            host.register(object : BusinessProvider {
                override fun domain(): String = "sloppy"
                override fun manifest(): DomainManifest = TestManifests.empty(domain())
                @Suppress("UNCHECKED_CAST")
                override fun dispatch(request: ActionRequest): ActionResult =
                    null as ActionResult
            })
            val result = host.dispatch("sloppy", "x", "")
            assertFalse(result.getSuccess(), "违约返回 null 应降级为失败")
        } finally {
            host.shutdown()
        }
    }

    /**
     * 门面稳定代理：**先缓存引用、后 install**，注册仍然生效。
     *
     * 这条是外部插件最自然的写法（onEnable 里存一次字段），
     * 若门面返回"未就绪快照"，缓存后永久失效且无任何提示。
     */
    @Test
    fun `门面代理可缓存且后安装生效`() {
        FacadeAssemblyTestHook.uninstall()
        // 先缓存代理（此时探针未就绪）
        val cached: BusinessHost = ServerProbeAPI.businessHost()
        assertNotNull(cached)
        assertFalse(ServerProbeAPI.isAvailable())

        val host = BusinessHostImpl()
        try {
            FacadeAssemblyTestHook.install(object : BridgeServices {
                override fun businessHost(): BusinessHost = host
            })
            assertTrue(ServerProbeAPI.isAvailable())

            val handle = cached.register(Stub("demo"))
            assertTrue(handle.getActive(), "缓存引用在 install 后必须能正常注册")
            assertEquals(setOf("demo"), host.domains())
            assertSame(cached, ServerProbeAPI.businessHost(), "代理应为同一稳定实例")
        } finally {
            host.shutdown()
        }
    }

    /** 未安装时门面全部方法安全返回（不抛、不 NPE）。 */
    @Test
    fun `未安装时门面安全降级`() {
        FacadeAssemblyTestHook.uninstall()
        val host = ServerProbeAPI.businessHost()

        val handle = host.register(Stub("demo"))
        assertFalse(handle.getActive())
        assertEquals(Registration.InactiveReason.PROBE_UNAVAILABLE, handle.getInactiveReason())
        assertTrue(host.domains().isEmpty())
        assertTrue(host.manifest().isEmpty())
        assertFalse(host.dispatch("demo", "x", "").getSuccess())
        assertFalse(host.dispatchAsync("demo", "x", "").get().getSuccess())
        assertFalse(host.unregister(null))
        handle.close()
    }

    /** uninstall 后已注册句柄失效。 */
    @Test
    fun `卸载后句柄失效`() {
        val host = BusinessHostImpl()
        val external = Stub("demo")
        try {
            FacadeAssemblyTestHook.install(object : BridgeServices {
                override fun businessHost(): BusinessHost = host
            })
            val handle = ServerProbeAPI.businessHost().register(external)
            assertTrue(handle.getActive())

            FacadeAssemblyTestHook.uninstall()
            // 卸载后门面走降级实现,原句柄仍指向宿主,但宿主 shutdown 时会置失效
            host.shutdown()
            assertFalse(handle.getActive(), "宿主停止后句柄应失效")
        } finally {
            host.shutdown()
        }
    }

    /** 重入派发内联执行，不占新并发位。 */
    @Test
    fun `重入派发内联执行`() {
        val host = BusinessHostImpl()
        try {
            host.register(object : BusinessProvider {
                override fun domain(): String = "inner"
                override fun manifest(): DomainManifest = TestManifests.empty(domain())
                override fun dispatch(request: ActionRequest): ActionResult =
                    ActionResult.ok("inner-${request.action}")
            })
            host.register(object : BusinessProvider {
                override fun domain(): String = "outer"
                override fun manifest(): DomainManifest = TestManifests.empty(domain())
                override fun dispatch(request: ActionRequest): ActionResult {
                    // 在 Provider 内部再次派发另一域
                    val nested = host.dispatch("inner", "x", "")
                    return ActionResult.ok("outer+${nested.getOutput()}")
                }
            })

            val result = host.dispatch("outer", "go", "")
            assertTrue(result.getSuccess(), "重入派发应成功")
            assertEquals("outer+inner-x", result.getOutput(), "内层应同步返回结果")
        } finally {
            host.shutdown()
        }
    }
}
