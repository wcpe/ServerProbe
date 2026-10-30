package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.Registration
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionRequest
import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessHost
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainManifest
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainStatus
import top.wcpe.mc.plugin.serverprobe.api.bridge.LegacyBusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.LegacyBusinessProviderAdapter
import top.wcpe.mc.plugin.serverprobe.api.bridge.Principal
import top.wcpe.mc.plugin.serverprobe.api.bridge.ProviderHealth
import top.wcpe.mc.plugin.serverprobe.core.bridge.manifest.BusinessManifestRules
import top.wcpe.mc.plugin.serverprobe.core.bridge.manifest.ManifestDigest
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 业务域宿主实现（JBIS，见 ADR-0015）。
 *
 * 在桥内核与业务 Provider 之间充当装配点 + 事故域隔离边界：
 * core 不依赖任何业务插件，各平台于就绪后调 [register] 自注册 Provider；
 * 桥收到带 domain 的业务命令时经 [dispatch] 路由执行。
 *
 * ## 事故域隔离
 * Provider 在**独立 daemon 线程池**执行（非桥读线程、非监控线程、非主线程），有界超时 + 异常边界：
 * Provider 卡死 / 抛异常只降级该次回执，**监控采集与桥读线程不受影响**。
 *
 * ## 注册句柄
 * [register] 返回 [Registration]：同域替换时旧句柄自动失效（`REPLACED`）；
 * 旧句柄的 `close()` 采用**实例比对**删除，绝不误删后来注册的新实例（插件热重载的经典坑）。
 *
 * ## / 的两层校验
 * - **注册期**（[register]）：域名格式、动作名重复 → 拒绝并记 ERROR；apiVersion 不支持、risk 未声明
 *   → 不拒绝，降级并记 WARN。理由：拒绝会让一个"只差版本号声明"的实现完全不可用，
 *   而它的动作本身仍然能跑（spec §7 明确要求降级）。
 * - **聚合期**（[manifest]）：逐域再判一次 `specDeclared` 与版本，因为 Provider 的 manifest
 *   每次现算，注册期的结论会过期。
 *
 * ## 重入保护
 * Provider 内再调 [dispatch] 会**内联执行**而非再占一个池槽：有界池下嵌套派发若各占一槽，
 * 高并发时会互相等对方释放 → 池级死锁。内联执行不增加并发位，语义上等价于同步调用。
 */
@Service
class BusinessHostImpl : BusinessHost {

    /** 已注册业务 Provider：domain → 条目。 */
    private val providers = ConcurrentHashMap<String, Entry>()

    /** 业务执行线程池（daemon，事故域隔离）。 */
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, THREAD_BUSINESS).apply { isDaemon = true }
    }

    /** 当前线程是否正在执行 Provider（重入判定）。 */
    private val inDispatch = ThreadLocal.withInitial { false }

    /**
     * 健康状态缓存（Provider 健康规格 §3.3）。
     *
     * 注入类型 [ProviderHealthRegistry] **未实现任何接口**，故 IoC 以自身类型暴露 Bean，
     * 按具体类型注入是正确写法；反例见本类自身的 `BusinessHost`（实现了接口的类只以接口暴露，
     * 注入实现类会报 `missing-bean`）。
     *
     * 单测可直接 `BusinessHostImpl()` 而不注入它，故所有访问点都先判 `isInitialized`。
     */
    @Inject
    lateinit var healthRegistry: ProviderHealthRegistry

    /** 业务动作派发超时（毫秒）；仅测试为验证超时降级可调小。 */
    @Volatile
    internal var dispatchTimeoutMs: Long = DISPATCH_TIMEOUT_MS

    /** 告警出口；单测可替换以断言告警文案（默认走探针统一日志门面）。 */
    @Volatile
    internal var warn: (String) -> Unit = ProbeLogger::warn

    /** 错误出口；单测可替换以断言拒绝原因。 */
    @Volatile
    internal var error: (String) -> Unit = { ProbeLogger.error(it, null) }

    /**
     * 注册一个业务域 Provider（含 spec §7 的全部注册期校验）。
     *
     * @param provider 业务 Provider；null、domain 为空/格式非法、动作名重复时返回失效句柄，不抛异常
     * @return 注册句柄；close 幂等
     */
    override fun register(provider: BusinessProvider?): Registration {
        if (provider == null) return InactiveRegistration(Registration.InactiveReason.INVALID, "provider 为 null")
        val domain = runCatching { provider.domain() }.getOrNull()
        // 域名格式校验（含"为空"）：格式非法会让下游的域路由/权限规则/日志聚合全部失效，
        // 必须拒绝（spec §7）。空串归入同一分支，是为了让"被拒原因"只有一处口径。
        if (domain == null || !BusinessManifestRules.isValidDomain(domain)) {
            val shown = if (domain == null) "<null>" else "\"$domain\""
            error("业务域格式非法，拒绝注册:domain=$shown（须匹配 ^[a-z0-9-]{1,32}$）")
            return InactiveRegistration(
                Registration.InactiveReason.INVALID,
                "domain 格式非法：须为 1-32 位小写字母/数字/连字符（当前 $shown）",
            )
        }
        // 清单取一次：注册期校验与降级判断都基于同一个实例。
        // Provider 的 manifest() 抛异常时按"未声明规格"处理，绝不因此让注册失败——
        // 一个 manifest 写坏的域，其动作往往仍然可用。
        val manifest = resolveManifest(provider, domain)
        val duplicates = BusinessManifestRules.duplicateActionNames(manifest)
        if (duplicates.isNotEmpty()) {
            error("业务域动作名重复，拒绝注册:domain=$domain actions=$duplicates")
            return InactiveRegistration(
                Registration.InactiveReason.INVALID,
                "动作名重复：${duplicates.joinToString(",")}",
            )
        }
        warnOnUndisclosed(provider, domain, manifest)

        val entry = Entry(domain, provider)
        val previous = providers.put(domain, entry)
        if (previous != null && previous.provider !== provider) {
            previous.deactivate(Registration.InactiveReason.REPLACED, "同域被新的 Provider 顶替")
            warn("业务 Provider 被重复注册,以最新为准:domain=$domain")
        } else {
            ProbeLogger.info("业务 Provider 已注册:domain=$domain")
        }
        // 健康条目初始化 + 缓存 manifest 指纹 + 请求一次刷新（规格 §3.6 / §3.1）。
        // 指纹由**这里**算完再交出去：注册期已经取过一次清单，再取一次等于让第三方
        // `manifest()` 被调两遍。旧契约不给指纹——它的清单由适配器合成，算出来没有可比性，
        // 面板拿它做「变化即拉全量」的判断会得到假阳性。版本不支持时按**降级后**的形态算，
        // 因为下游拿到的正是降级后的清单（与 encodeDomainOf 保持一致）。
        val digest = if (LegacyBusinessProviderAdapter.isLegacy(provider)) {
            null
        } else {
            ManifestDigest.of(
                if (manifest.isApiVersionSupported) manifest else BusinessManifestRules.degradeToLegacy(manifest),
            )
        }
        registryOf()?.attach(domain, provider, digest)
        return entry
    }

    /**
     * 注册旧契约 Provider（形态）。
     *
     * @param provider 旧契约 Provider；null 返回失效句柄
     * @return 注册句柄；close 幂等
     */
    override fun registerLegacy(provider: LegacyBusinessProvider?): Registration =
        register(LegacyBusinessProviderAdapter.adapt(provider))

    /**
     * 按实例注销：仅当当前域仍指向该实例时才移除（陈旧句柄保护）。
     *
     * @return true=已移除；false=该实例并非当前注册者
     */
    override fun unregister(provider: BusinessProvider?): Boolean {
        if (provider == null) return false
        val domain = runCatching { provider.domain() }.getOrNull() ?: return false
        val current = providers[domain] ?: return false
        if (current.provider !== provider) return false
        if (!providers.remove(domain, current)) return false
        current.deactivate(Registration.InactiveReason.CLOSED, "调用方注销")
        // 按实例标记已注销：陈旧回调不会误抹新注册（healthRegistry.detach 内部同样按实例比对）。
        registryOf()?.detach(domain, provider)
        ProbeLogger.info("业务 Provider 已撤销:domain=$domain")
        return true
    }

    /** 当前已注册的业务域（不可变快照、按字典序）。 */
    override fun domains(): Set<String> = providers.keys.sorted().toSet()

    /**
     * 汇总各域能力清单（spec §6 的信封形态：`{apiVersion, domains}`）。
     *
     * 逐域 `runCatching`：单域 manifest 抛异常时该域呈现为 `{"error": ...}`，
     * 不影响其它域——否则一个坏 Provider 会让面板看不到全部业务。
     *
     * 版本不支持时在本层**再降级一次**（spec §5.2）：Provider 的 manifest 每次现算，
     * 注册期的结论会过期，而"把读不懂的清单当原生规格下发"正是静默失败的形态。
     */
    override fun manifest(): Map<String, Any?> {
        val result = LinkedHashMap<String, Map<String, Any?>>()
        providers.entries.sortedBy { it.key }.forEach { (domain, entry) ->
            result[domain] = runCatching { encodeDomainOf(entry.provider) }
                .getOrElse { BusinessManifestRules.encodeDomainError(it.message ?: it.javaClass.simpleName) }
        }
        return Collections.unmodifiableMap(BusinessManifestRules.encodeEnvelope(result))
    }

    /**
     * 派发一条业务动作（强类型入口）。
     *
     * **可能阻塞至多 [dispatchTimeoutMs]，禁止在主线程调用**。
     *
     * 域未注册 → 降级失败；Provider 抛异常 / 超时 → 降级失败。绝不抛、绝不超过超时预算。
     *
     * 收尾处把结果与耗时写入健康缓存（纯内存累加）并请求一次健康刷新——即使 Provider 从不覆写
     * `health()`，面板也能看到"这个域最近为什么失败、连续失败了几次"。
     */
    override fun dispatch(request: ActionRequest?): ActionResult {
        if (request == null) return ActionResult.fail("请求为 null")
        val startNanos = System.nanoTime()
        // requestId 恒非空（spec §4.4）：下行帧缺失时在这里补齐，Provider 无需再判空。
        val normalized = if (request.requestId.isEmpty()) withGeneratedRequestId(request) else request
        val result = doDispatch(normalized)
        recordOutcome(normalized.domain, result, (System.nanoTime() - startNanos) / NANOS_PER_MS)
        return result
    }

    /**
     * 异步派发：Future 以**结果**完成（超时也返回失败结果，而非异常完成）。
     */
    override fun dispatchAsync(request: ActionRequest?): CompletableFuture<ActionResult> =
        CompletableFuture.supplyAsync({ dispatch(request) }, executor)

    /**
     * 查询一个业务域的健康状态：**只读缓存快照**，不调用 Provider、不做 IO、不派发、不新建线程。
     *
     * 未注入注册表（单测直接构造本类）时返回降级快照；内部任何意外都降级为不可用，
     * 绝不抛异常（接口契约要求"所有方法均不抛"）。
     */
    override fun healthOf(domain: String): DomainStatus =
        runCatching { registryOf()?.viewOf(domain) }.getOrNull() ?: unavailableStatus(domain)

    /**
     * 派发主体（池化 + 超时 + 异常兜底）。
     *
     * 拆出来是为了让 [dispatch] 只在**一个**收尾处记账：原先有 5 个 return 分支，
     * 若在每个分支各写一次记录，漏一处就会出现"失败却没被记录"的静默缺陷。
     */
    // ExecutionException/TimeoutException 都是"Provider 侧失败"的统一出口，
    // 契约要求一律降级为失败结果而非上抛，因此这里按 Exception 广捕。
    @Suppress("TooGenericExceptionCaught")
    private fun doDispatch(request: ActionRequest): ActionResult {
        val entry = providers[request.domain]
            ?: return ActionResult.fail("业务域不可用:${request.domain}")
        // 重入:已在池线程内则内联执行,不再占新并发位(避免有界池下的嵌套死锁)。
        if (inDispatch.get()) {
            return runSafely(entry.provider, request)
        }
        val future = runCatching {
            executor.submit<ActionResult> { runSafely(entry.provider, request) }
        }.getOrElse { return ActionResult.fail("业务派发失败:${it.javaClass.simpleName}") }
        return try {
            future.get(dispatchTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // 恢复中断标志:否则 shutdownNow 之后线程无法正常退出(会残留)。
            Thread.currentThread().interrupt()
            future.cancel(true)
            ActionResult.fail("业务执行被中断")
        } catch (e: Exception) {
            // 这里必须广捕：TimeoutException 只是其一，Provider 的 RuntimeException 也会经
            // ExecutionException 冒出来，而契约要求"绝不抛、只降级为失败结果"。
            future.cancel(true)
            ActionResult.fail("业务执行异常或超时:${e.javaClass.simpleName}")
        }
    }

    /**
     * 把一次派发结果记入健康缓存，并请求一次尽快刷新。
     *
     * 两步都是纯内存、O(1)、不阻塞、不抛——[ProviderHealthRegistry.recordDispatch] 内部已兜住异常；
     * 这里再包一层 `runCatching`，保证"健康统计坏掉"永远不会影响业务回执本身。
     *
     * 健康缓存只认「成功与否 + 失败原因」，因此这里把 [ActionResult] 折成
     * [BridgeCommandResult] 再交出去。失败原因优先人可读消息、退而用机器可读错误码：
     * 「只返回了错误码」的 Provider 也应在面板上留下痕迹，否则运维看到的是"失败了但不知道为什么"。
     */
    private fun recordOutcome(domain: String, result: ActionResult, costMs: Long) {
        val registry = registryOf() ?: return
        val message = if (result.errorMessage.isNotEmpty()) result.errorMessage else result.errorCode
        val folded = if (result.success) {
            BridgeCommandResult.ok(result.outputJson)
        } else {
            BridgeCommandResult.fail(message)
        }
        runCatching {
            registry.recordDispatch(domain, folded, costMs)
            registry.requestRefresh(domain)
        }
    }

    /** 取健康注册表；未注入（单测直接构造本类）时返回 null。 */
    private fun registryOf(): ProviderHealthRegistry? =
        if (::healthRegistry.isInitialized) healthRegistry else null

    /** 未注入注册表时的降级快照（不报错、不抛）。 */
    private fun unavailableStatus(domain: String): DomainStatus = DomainStatus(
        domain,
        false,
        ProviderHealth.unknown(),
        REASON_HEALTH_UNAVAILABLE,
        0L,
        null,
        0L,
        0L,
        0L,
        false,
        // 无注册表就没有清单可取，指纹必然缺席（null 而不是空串）。
        null,
        0L,
        REASON_HEALTH_UNAVAILABLE,
    )

    /** 在 Provider 内执行并兜住一切异常。 */
    // 必须捕 Throwable：外部 Provider 完全可能抛 Error（如 NoClassDefFoundError），
    // 而这里在**池线程**上执行——漏出去会打死池线程并让池永久少一个位。
    @Suppress("TooGenericExceptionCaught")
    private fun runSafely(provider: BusinessProvider, request: ActionRequest): ActionResult {
        val wasInDispatch = inDispatch.get()
        inDispatch.set(true)
        return try {
            val outcome = runCatching { provider.dispatch(request) }
            // 区分"抛了"与"返回 null"：两者都是违约，但运维看到的原因必须不同，
            // 否则一个 NoSuchMethodError 会被记成"未返回结果"，排查方向完全跑偏。
            val failure = outcome.exceptionOrNull()
            if (failure != null) {
                ActionResult.fail("业务执行异常:${failure.javaClass.simpleName}")
            } else {
                // 防 Provider 返回 null:契约要求非 null,但外部实现可能违约,这里兜住避免 NPE 外溢。
                outcome.getOrNull() ?: ActionResult.fail("Provider 未返回结果")
            }
        } catch (e: Throwable) {
            ActionResult.fail("业务执行异常:${e.javaClass.simpleName}")
        } finally {
            inDispatch.set(wasInDispatch)
        }
    }

    /** 补一个回执标识（UUID 保证跨重启不撞号）。 */
    private fun withGeneratedRequestId(request: ActionRequest): ActionRequest = ActionRequest(
        request.domain,
        request.action,
        request.payloadJson,
        UUID.randomUUID().toString(),
        request.principal,
    )

    /**
     * 现算一次清单；抛异常或返回 null 时降级为空清单（按"未声明规格"处理）。
     *
     * 空清单而不是 null：后续校验与编码都不必再判空，且"读不出清单"与"清单为空"
     * 对下游是同一种事实——这个域没有可用规格。
     */
    private fun resolveManifest(provider: BusinessProvider, domain: String): DomainManifest =
        runCatching { provider.manifest() }.getOrNull()
            ?: DomainManifest.of(domain, "", "", emptyList(), emptyMap())

    /**
     * 按 spec §7 记录「不拒绝但要告警」的两类情况。
     *
     * apiVersion 不支持 → WARN + 降级；risk 未声明 → WARN + 按 WRITE 生效。
     * 两者都不能拒绝：拒绝会让 Provider 完全不可用，而降级后的语义仍然安全。
     */
    private fun warnOnUndisclosed(provider: BusinessProvider, domain: String, manifest: DomainManifest) {
        if (LegacyBusinessProviderAdapter.isLegacy(provider)) {
            warn("业务域使用旧契约注册，按未声明规格处理:domain=$domain（动作风险一律按 WRITE）")
            return
        }
        if (!manifest.isApiVersionSupported) {
            warn(
                "业务域 apiVersion 不受支持，已降级为 legacy 规格:domain=$domain " +
                    "apiVersion=${manifest.apiVersion} supported=${DomainManifest.CURRENT_API_VERSION}",
            )
        }
        val undisclosed = manifest.actions.filter { !it.hasDeclaredRisk() }.map { it.name }
        if (undisclosed.isNotEmpty()) {
            warn("业务域存在未声明风险等级的动作，按 WRITE 处理:domain=$domain actions=$undisclosed")
        }
    }

    /** 编码单域子结构（必要时降级）。 */
    private fun encodeDomainOf(provider: BusinessProvider): Map<String, Any?> {
        val raw = runCatching { provider.manifest() }.getOrNull()
            ?: return BusinessManifestRules.encodeDomainError("manifest() 未返回结果")
        val specDeclared = BusinessManifestRules.isSpecDeclared(provider, raw)
        // 旧契约由适配器自己给出 legacy 形态（动作名可能不完整），此处不再二次降级；
        // 仅"版本不匹配的新契约"需要把字段含义不可信的清单降成保守形态。
        val manifest = if (!specDeclared && !LegacyBusinessProviderAdapter.isLegacy(provider)) {
            BusinessManifestRules.degradeToLegacy(raw)
        } else {
            raw
        }
        return BusinessManifestRules.encodeDomain(manifest, specDeclared)
    }

    /** 停止业务线程池（IOC 销毁时）；未起亦安全。 */
    @PreDestroy
    fun shutdown() {
        runCatching { executor.shutdownNow() }
        providers.values.forEach { it.deactivate(Registration.InactiveReason.PROBE_UNAVAILABLE, "探针已停止") }
        // 健康条目一并标记已注销：停用后不该再有域显示为"在册可用"。
        // 注册表自身的线程由 BridgeClient 的停机序列 shutdownNow。
        registryOf()?.let { registry ->
            providers.forEach { (domain, entry) -> runCatching { registry.detach(domain, entry.provider) } }
        }
        providers.clear()
    }

    /**
     * 一个已注册条目，同时是返回给调用方的注册句柄。
     *
     * 声明为 `inner class`：句柄需要反查宿主才能注销，而注册与 close **不保证同线程**
     * （插件 onEnable 建句柄、热重载线程 close 是常见形态），用 ThreadLocal 传递宿主会失效。
     * 持有宿主引用形成的环由 GC 正常处理，无需担心泄漏。
     *
     * `reason` 用 `@Volatile`：注册于启动期、读取于任意线程，需要跨线程可见性。
     */
    private inner class Entry(val domain: String, val provider: BusinessProvider) : Registration {

        @Volatile
        private var reason: Registration.InactiveReason = Registration.InactiveReason.NONE

        @Volatile
        private var detail: String = ""

        override fun getActive(): Boolean = reason == Registration.InactiveReason.NONE

        override fun getInactiveReason(): Registration.InactiveReason = reason

        override fun getInactiveDetail(): String = detail

        /** 标记失效（内部调用）。 */
        fun deactivate(reason: Registration.InactiveReason, detail: String) {
            this.reason = reason
            this.detail = detail
        }

        override fun close() {
            // 幂等:已失效则无事可做。
            if (!getActive()) return
            unregisterByEntry(this)
        }
    }

    /** 按条目注销（供句柄 close 调用，保证实例比对语义）。 */
    private fun unregisterByEntry(entry: Entry) {
        if (providers.remove(entry.domain, entry)) {
            entry.deactivate(Registration.InactiveReason.CLOSED, "调用方 close")
            registryOf()?.detach(entry.domain, entry.provider)
            ProbeLogger.info("业务 Provider 已注销:domain=${entry.domain}")
        }
    }

    /** 注册被拒时返回的失效句柄（不抛异常，让调用方能按统一方式判断）。 */
    private class InactiveRegistration(
        private val reason: Registration.InactiveReason,
        private val detail: String,
    ) : Registration {

        override fun getActive(): Boolean = false

        override fun getInactiveReason(): Registration.InactiveReason = reason

        override fun getInactiveDetail(): String = detail

        override fun close() {
            // 从未注册过任何东西,close 是无操作。
        }
    }

    private companion object {
        /** 业务线程名前缀。 */
        const val THREAD_BUSINESS = "serverprobe-business"

        /** 派发超时（毫秒）：与治理同步往返同量级。 */
        const val DISPATCH_TIMEOUT_MS = 5000L

        /** 纳秒→毫秒换算（派发耗时统计用）。 */
        const val NANOS_PER_MS = 1_000_000L

        /** 健康注册表未注入时的降级原因（`reasonOverride` 与 `degradedReason` 共用）。 */
        const val REASON_HEALTH_UNAVAILABLE = "健康状态不可用(探针未就绪)"
    }
}
