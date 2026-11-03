package top.wcpe.mc.plugin.serverprobe.e2e.load

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessEventSink

/**
 * 桥链路压测桩（外置插件）。
 *
 * ## 为什么要做成外置插件而不是给 ServerProbe 加命令
 *
 * 三个理由，依次是必要性、可信度、可维护性：
 *
 * 1. **不动被测物**。给产品代码加压测入口，哪怕用开关包起来，也会改变它自身的东西
 *    （类装载、初始化顺序、配置解析）。压在“被改过的探针”上得出的结论不可靠。
 * 2. **证据链闭合**。本插件只依赖 `:api`（compileOnly），以第三方插件身份调公开 API。
 *    它能发出去，就证明**公开 API → 桥**这条链路是外部真实可用的。
 * 3. **发布包干净**。压测代码不会因“忘关开关”漏进发行 jar。
 *
 * ## 不变量
 *
 * - 只在**服务就绪后**才接受压测（服务未启完就压，测的是启动期而不是稳态）。
 * - 只在**本插件启用且 ServerProbe 可用**时才跑（否则测的是“未就绪”而不是“堵不堵”）。
 * - 压测域 `probe.loadtest` 与真实业务域隔离，JM 侧可按域过滤。
 */
class BridgeLoadHarnessPlugin : JavaPlugin() {

    @Volatile
    private var ready = false

    /** 当前是否有一轮压测在跑；同一时刻只允许一轮（并发跑两轮会互相干扰度量）。 */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onEnable() {
        // 延迟到首个 tick 再置就绪：onEnable 时 ServerProbe 可能尚未完成桥接线。
        object : BukkitRunnable() {
            override fun run() {
                ready = true
                logger.info("[BridgeLoad] 压测桩已就绪，可用 /bridgeload 命令；目标探针可用性待命")
            }
        }.runTask(this)
    }

    override fun onDisable() {
        ready = false
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!ready) {
            sender.sendMessage("[BridgeLoad] 插件尚未就绪，请稍后重试")
            return true
        }
        if (!sender.isOp) {
            sender.sendMessage("[BridgeLoad] 需要 OP")
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage("[BridgeLoad] 用法: /bridgeload <count> [threads] [maxInFlight]")
            return true
        }

        val count = args.getOrNull(0)?.toIntOrNull()
        if (count == null || count <= 0) {
            sender.sendMessage("[BridgeLoad] count 必须是正整数")
            return true
        }
        val threads = (args.getOrNull(1)?.toIntOrNull() ?: DEFAULT_THREADS).coerceIn(1, MAX_THREADS)
        val maxInFlight = args.getOrNull(2)?.toIntOrNull() ?: 0

        if (!ServerProbeAPI.isAvailable()) {
            sender.sendMessage("[BridgeLoad] ServerProbe API 不可用，无法压测")
            return true
        }
        if (!running.compareAndSet(false, true)) {
            sender.sendMessage("[BridgeLoad] 已有一轮压测在跑，请等待结束")
            return true
        }

        val sink: BusinessEventSink = ServerProbeAPI.eventSink()
        sender.sendMessage("[BridgeLoad] 开始压测: count=$count threads=$threads maxInFlight=$maxInFlight")

        // 异步跑：压测会持续数秒，必须在主线程之外，否则服务器卡死、测出的数字毫无意义。
        object : BukkitRunnable() {
            override fun run() {
                try {
                    val runner = BridgeLoadRunner { domain, dedupKey, topic ->
                        sink.emitReliable(domain, dedupKey, topic, FIELDS)
                    }
                    val report = runner.run("reliable", count, threads, maxInFlight)
                    logger.info("[BridgeLoad] ${report.summary()}")
                    report.details("load.").forEach { (key, value) ->
                        logger.info("[BridgeLoad] detail $key=$value")
                    }
                } catch (t: Throwable) {
                    logger.warning("[BridgeLoad] 压测异常: ${t.javaClass.simpleName}: ${t.message}")
                } finally {
                    running.set(false)
                }
            }
        }.runTaskAsynchronously(this)

        return true
    }

    private companion object {
        const val DEFAULT_THREADS = 4
        const val MAX_THREADS = 64

        /**
         * 压测事件字段。
         *
         * 键值均为字符串（协议要求）。`seq` 由调用方覆盖不实用（emitReliable 只收 Map），
         * 所以这里用固定小 Map、把序号编在 dedupKey 里——幂等去重靠 dedupKey，不靠字段。
         */
        val FIELDS: Map<String, String> = mapOf(
            "source" to "bridge-load-harness",
            "kind" to "synthetic",
        )
    }
}
