package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 业务对接装配与派发中心(JBIS,见 ADR-0015 / JianManager ADR-025~027)。
 *
 * 在 [BridgeClient](core,平台无关)与平台层 [BusinessProvider] 实现之间充当解耦装配点 + 事故域隔离边界:
 * core 不依赖任何业务插件,各平台于就绪后按域调 [register] 自注册 Provider;[BridgeClient] 收到带 domain
 * 的业务 `command` 帧时经 [dispatch] 路由执行。沿用 [BridgeCommandRegistry] 的 core 装配范式。
 *
 * ## 事故域隔离(铁律,ADR-0015)
 * 业务 Provider 在**独立 daemon 业务线程池**执行(非桥读线程、非监控线程、非主线程),有界超时 + 异常边界:
 * Provider 卡死 / 抛异常只降级该次回执,**监控采集与桥读线程不受影响**(读线程至多等 [DISPATCH_TIMEOUT_MS])。
 * 与 [BridgeClient] 自起 daemon 读线程同源(桥基础设施用裸线程,不占 TabooLib 调度线程 / 主线程)。
 *
 * 并发模型:domain→Provider 注册于启动期、读取于桥读线程,用 [ConcurrentHashMap];线程池守护,JVM 退出不阻塞。
 */
@Service
class BusinessHost {

    /** 已注册业务 Provider:domain → Provider。 */
    private val providers = ConcurrentHashMap<String, BusinessProvider>()

    /** 业务执行线程池(daemon,事故域隔离;与监控 / 桥读线程互不影响)。 */
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "serverprobe-business").apply { isDaemon = true }
    }

    /** 业务动作派发超时(毫秒);默认 [DISPATCH_TIMEOUT_MS],仅测试为验证超时降级可调小。 */
    @Volatile
    internal var dispatchTimeoutMs: Long = DISPATCH_TIMEOUT_MS

    /**
     * 注册一个业务域 Provider(平台层于就绪后调用)。同域重复注册以最新为准并 WARN。
     *
     * @param provider 业务 Provider。
     */
    fun register(provider: BusinessProvider) {
        val prev = providers.put(provider.domain, provider)
        if (prev != null && prev !== provider) {
            ProbeLogger.warn("业务 Provider 被重复注册,以最新为准:domain=${provider.domain}")
        } else {
            ProbeLogger.info("业务 Provider 已注册:domain=${provider.domain}")
        }
    }

    /** 当前已注册的业务域集合(只读快照)。 */
    fun domains(): Set<String> = providers.keys.toSet()

    /**
     * 汇总各 Provider 的能力清单为一个 JSON 文本,供 JianManager 动态发现业务能力。
     *
     * @return 形如 `{"domains":{"economy":{...}}}` 的 JSON 文本。
     */
    fun manifest(): String =
        Json.encode(mapOf("domains" to providers.values.associate { it.domain to it.manifest() }))

    /**
     * 路由并以事故域隔离方式派发一条业务动作。
     *
     * 域未注册 → 降级失败(域不可用);Provider 在业务线程池执行,超时 / 异常 → 降级失败。
     * **绝不抛、绝不阻塞桥读线程超过 [DISPATCH_TIMEOUT_MS]**。
     *
     * @param domain 业务域。
     * @param action 动作名。
     * @param payload 结构化参数 JSON。
     * @return 执行结果(成功 / 输出 / 失败原因)。
     */
    fun dispatch(domain: String, action: String, payload: String): BridgeCommandResult {
        val provider = providers[domain] ?: return BridgeCommandResult.fail("业务域不可用:$domain")
        val future = runCatching { executor.submit<BridgeCommandResult> { provider.dispatch(action, payload) } }
            .getOrElse { return BridgeCommandResult.fail("业务派发失败:${it.message}") }
        return runCatching { future.get(dispatchTimeoutMs, TimeUnit.MILLISECONDS) }
            .getOrElse {
                future.cancel(true)
                BridgeCommandResult.fail("业务执行异常或超时:${it.message}")
            }
    }

    /** 停止业务线程池(IOC 销毁时);未起亦安全。 */
    @PreDestroy
    fun shutdown() {
        runCatching { executor.shutdownNow() }
    }

    private companion object {
        /** 业务动作派发超时(毫秒):与治理同步往返同量级;超时即降级失败,绝不永占桥读线程。 */
        const val DISPATCH_TIMEOUT_MS = 5000L
    }
}
