package top.wcpe.mc.plugin.serverprobe.core.mcp

import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeActionHandler
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCapabilities
import top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeCommandRegistry
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneModeService
import top.wcpe.mc.plugin.serverprobe.core.control.ProbeMode
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 托管模式下把 **MCP 工具面经插件桥暴露**（`core.mcp` 动作）。
 *
 * ## 解决的是什么
 * 托管模式承诺“本地零监听端口”，于是 `metrics` / `web` / `mcp` 三个本地监听面全被
 * [top.wcpe.mc.plugin.serverprobe.core.control.LocalListenerGate] 强制关断。
 * 前两个有替代：指标走 WS 推送、面板由控制面自己渲染。**事故诊断（MCP + Arthas）没有替代**——
 * 一台被托管的机器出事时，最需要的就是它。
 *
 * 本服务用**已有的桥连接**（反向 WS）承载 MCP：控制面下发一个 `core.mcp` 命令，`payload` 是
 * 完整的 JSON-RPC 2.0 请求，回执 `output` 是 JSON-RPC 响应。于是零监听端口与可诊断性同时成立。
 *
 * ## 只在托管模式启用
 * 独立模式的 MCP 仍由 `mcp.enabled` 控制的本地端点提供（行为不变）。托管模式才把工具面挂上桥，
 * 因为此时“桥的可信持凭者”已经等于“被授权管理这台探针的控制面”，不再引入新的信任边界；
 * 而若在独立模式也默认开启，则等于给了一个绕过 `mcp.enabled=false` 的后门——那是不对的。
 *
 * ## 线程模型
 * 装配跑在本类自起的 daemon 线程（等模式判定后再注册动作），主线程零阻塞。
 * 真正的工具执行发生在**桥的命令池线程**上（`GovernanceRoute` 的 `coreAction` 分支），
 * 不占 MC 主线程，也与“业务域命令同池”共享有界背压。
 */
@Service
class McpBridgeService {

    /** 托管模式判定服务。 */
    @Inject
    lateinit var modeService: ControlPlaneModeService

    /** 指令分发中心：把 `core.mcp` 注册为 core 内置动作。 */
    @Inject
    lateinit var commandRegistry: BridgeCommandRegistry

    /** MCP 控制面：借用它的工具面装配与生命周期（工件工作区 + 审计）。 */
    @Inject
    lateinit var controlPlane: McpControlPlane

    /** 启动幂等。 */
    private val started = AtomicBoolean(false)

    /** 是否已完成挂载（停止时据此释放工具面运行期）。 */
    @Volatile
    private var mounting = false

    /** 是否已进入停止流程：堆住“stop 先到、装配后到”时仍注册动作与能力位的窗口。 */
    @Volatile
    private var stopping = false

    /** 装配与停止的串行化锁。 */
    private val lifecycleLock = Any()

    /** 等模式判定；单测可替换，以便确定性地验证"stop 先到"的时序（生产永远用真实现）。 */
    internal var modeReadiness: (Long) -> Unit = { timeoutMs -> modeService.awaitReady(timeoutMs) }

    /** 启动装配（`@PostEnable`）：只起后台线程，判定与装配均在后台完成。 */
    @PostEnable
    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        Thread({ bootstrap() }, THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 停止（`@PreDestroy`）：摘除动作与能力位，再释放工具面运行期（工作区与审计）。
     *
     * 只置 [mounting] 不撑销会让残留动作继续指向已关闭的审计/工作区（之后每次 `core.mcp` 都是“执行异常”）；
     * 能力位不摘则会让控制面继续按“支持 MCP”去调一个已不存在的工具面。
     */
    @PreDestroy
    fun stop() {
        synchronized(lifecycleLock) {
            stopping = true
            if (!mounting) {
                return
            }
            mounting = false
        }
        commandRegistry.unregisterCoreAction(ACTION_MCP)
        BridgeCapabilities.removeCapability(BridgeCapabilities.CAP_MCP_BRIDGE)
        runCatching { controlPlane.disable() }
    }

    /** 等配置就绪 → 等模式判定 → 托管模式且 MCP 已启用才挂载。 */
    private fun bootstrap() {
        // 先等配置就绪：`mcp.enabled` 属禁止远程档，值只能来自本地 config.yml；
        // 而 IOC 装配线程可能跑在 TabooLib 注入配置之前，早读会拿到默认 false（实测会导致 MCP 不挂载，偶发）。
        if (!ProbeConfig.awaitLoaded()) {
            ProbeLogger.warn("MCP 装配等待配置就绪超时，将按当前可见值判断 mcp.enabled")
        }
        modeReadiness(BOOTSTRAP_WAIT_MS)
        if (modeService.mode() != ProbeMode.MANAGED) {
            ProbeLogger.info("独立模式：MCP 仅保留由 mcp.enabled 控制的本地端点，不经插件桥暴露")
            return
        }
        // 开关语义不变：`mcp.enabled` 决定“是否启用 MCP 控制面”，**传输方式**才按模式决定
        // （独立模式=本地 HTTP 端点；托管模式=插件桥）。两者都不能远程开：该键属禁止档。
        if (!runCatching { ProbeConfig.mcpEnabled() }.getOrDefault(false)) {
            ProbeLogger.info("托管模式下 MCP 未启用(mcp.enabled=false)：桥不暴露工具面（本地亦不监听）")
            return
        }
        val dispatcher = controlPlane.bridgeDispatcher()
        val endpoint = McpBridgeEndpoint(dispatcher)
        // 注册动作、声明能力位、置挂载标志必须在同一临界区：stop 先到时一样都不做，
        // 否则停服后还会留下指向已释放执行体的动作与一条谎报的能力位。
        if (!mount { command -> endpoint.handle(command) }) {
            return
        }
        ProbeLogger.info("托管模式下 MCP 工具面已挂到插件桥（动作 $ACTION_MCP），本地不监听任何端口")
    }

    /**
     * 挂载工具面（注册动作 + 声明能力位 + 置标志）。
     *
     * 三件事同在一个临界区里完成，与 [stop] 共用 [lifecycleLock]：这是“stop 先到、装配后到”
     * 能被真正堵住的前提——旧写法里 stop 只置标志，于是装配晚到时仍会留下动作与能力位。
     *
     * @param handler `core.mcp` 的执行体。
     * @return true 表示挂载成功；false 表示 stop 已先到，本次什么都没注册。
     */
    internal fun mount(handler: BridgeActionHandler): Boolean = synchronized(lifecycleLock) {
        if (stopping) {
            return false
        }
        commandRegistry.registerCoreAction(ACTION_MCP, handler)
        // 能力位只在真的挂上之后声明：提前声明会让控制面调工具时拿到“未实现”回执。
        BridgeCapabilities.addCapability(BridgeCapabilities.CAP_MCP_BRIDGE)
        mounting = true
        true
    }

    private companion object {

        /** 装配线程名（便于线上排查）。 */
        private const val THREAD_NAME = "ServerProbe-McpBridge"

        /** 等托管模式判定的上限（毫秒）：与其它托管组件保持同一口径。 */
        private const val BOOTSTRAP_WAIT_MS = 5_000L

        /** `core.mcp` 动作名（协议规格 §3.6 旁路：MCP 经桥）。 */
        private const val ACTION_MCP = "mcp"
    }
}
