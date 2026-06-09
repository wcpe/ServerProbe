package top.wcpe.mc.plugin.serverprobe.core.alert

import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostEnable
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * 告警引擎(FR5)。
 *
 * 由 [top.wcpe.mc.plugin.serverprobe.core.orchestrator.MetricOrchestrator] 在每次采集末尾调用 [evaluate],
 * 对照规则集判定各指标是否越线,并经 [AlertChannelRegistry] 广播触发/恢复事件给所有已注册通道。
 *
 * ## 防抖与恢复(每条规则独立状态机)
 * 每条规则维护一个 [RuleState](连续越线计数 + 是否已触发):
 * - **触发**:连续越线达到 [AlertRule.sustainCycles] 个采集周期且当前未触发 → 置触发态并广播一次触发事件;
 * - **恢复**:已触发状态下首次未越线 → 置正常态并广播一次恢复事件,清零计数;
 * - **防重**:已触发后持续越线**不重复**广播,直至恢复后再次满足条件;
 * - **数据缺失([AlertType.extract] 返回 null)**:重置连续越线计数(避免 N/A 期间累计误触发);
 *   且**即便此前处于触发态也只清状态、不广播恢复事件**——数据缺失(如代理端无 TPS、采集瞬时缺值)
 *   不能等同于"指标已恢复正常",以免误报恢复。
 *
 * ## 线程模型:串行无锁
 * [evaluate] 仅由编排层的**单一异步采集线程**串行调用(同一时刻不并发),故 [RuleState] 为可变普通字段、
 * 无需加锁。规则集 [rules] 在 [PostEnable] 一次性构建后只读,运行期不再变更。
 *
 * 生命周期:作为 IOC [Service] 由容器管理;[buildRules] 于 [PostEnable] 读取配置构建规则集
 * (此时 `config.yml` 已注入)。
 */
@Service
class AlertEngine {

    /** 告警通道注册中心,广播事件经其 [AlertChannelRegistry.channels] 分发。 */
    @Inject
    lateinit var channelRegistry: AlertChannelRegistry

    /**
     * 规则与其运行状态的绑定列表;在 [buildRules] 构建后只读。
     *
     * 总开关关闭或无启用规则时为空列表,[evaluate] 直接空转。
     */
    private var rules: List<RuleHolder> = emptyList()

    /**
     * 读取配置构建规则集(仅在告警总开关开启时)。
     *
     * 采用 [PostEnable] 而非构造期:确保 [ProbeConfig] 已由 TabooLib 注入完毕再读取。
     * 仅纳入 [AlertRule.enabled] 为 true 的规则;为每条规则配一份初始 [RuleState]。
     */
    @PostEnable
    fun buildRules() {
        if (!ProbeConfig.alertEnabled()) {
            ProbeLogger.info("告警引擎未开启(alert.enabled=false),已跳过")
            return
        }
        installRules(ProbeConfig.alertRules())
        ProbeLogger.info("告警引擎已启用,生效规则数=${rules.size}")
    }

    /**
     * 用给定规则集装配引擎:过滤掉未启用的规则,为每条配一份初始 [RuleState]。
     *
     * 抽出独立方法以解耦"规则来源"与"装配逻辑"——生产路径由 [buildRules] 从配置取,
     * 测试可经 [configureForTest] 直接传入,避免依赖 IOC/配置。
     *
     * @param ruleList 待装配的规则集(含未启用项,内部过滤)。
     */
    private fun installRules(ruleList: List<AlertRule>) {
        rules = ruleList.filter { it.enabled }.map { RuleHolder(it, RuleState()) }
    }

    /**
     * 测试专用装配:直接注入通道注册中心与规则集,绕过 IOC 与 [ProbeConfig]。
     *
     * 仅供单元测试在无容器环境下驱动 [evaluate] 验证防抖/恢复等行为;生产代码勿调用。
     *
     * @param channelRegistry 通道注册中心(测试可注册 fake 通道捕获事件)。
     * @param ruleList 规则集(含未启用项,内部按 [AlertRule.enabled] 过滤)。
     */
    internal fun configureForTest(channelRegistry: AlertChannelRegistry, ruleList: List<AlertRule>) {
        this.channelRegistry = channelRegistry
        installRules(ruleList)
    }

    /**
     * 对一份快照执行一次告警判定,并按需广播触发/恢复事件。
     *
     * 流程见类 KDoc 的"防抖与恢复"。本方法不抛出异常:单条规则、单个通道的异常均被
     * runCatching 兜底并记日志,绝不中断其余规则的判定与其余通道的呈现(探针不成事故源)。
     *
     * @param snapshot 当前指标快照。
     */
    fun evaluate(snapshot: MetricSnapshot) {
        if (rules.isEmpty()) return
        for (holder in rules) {
            runCatching { evaluateRule(holder, snapshot) }
                .onFailure { ProbeLogger.error("告警规则判定异常:${holder.rule.type}", it) }
        }
    }

    /**
     * 判定单条规则并驱动其状态机。
     *
     * @param holder 规则及其运行状态。
     * @param snapshot 当前指标快照。
     */
    private fun evaluateRule(holder: RuleHolder, snapshot: MetricSnapshot) {
        val rule = holder.rule
        val state = holder.state
        val value = rule.type.extract(snapshot)
        if (value == null) {
            // 数据缺失:清零计数;若此前已触发,仅清状态不发恢复(N/A ≠ 恢复正常,避免误报)
            state.consecutiveViolations = 0
            state.firing = false
            return
        }
        if (rule.type.violated(value, rule.threshold)) {
            state.consecutiveViolations++
            // 达到持续周期且尚未处于触发态 → 置触发并广播一次(防重:已触发则不再发)
            if (!state.firing && state.consecutiveViolations >= rule.sustainCycles) {
                state.firing = true
                broadcast(AlertEvent(rule, firing = true, value = value, serverId = snapshot.serverId, timestampMs = snapshot.timestampMs))
            }
        } else {
            // 未越线:若此前处于触发态 → 置恢复并广播一次恢复事件
            if (state.firing) {
                state.firing = false
                broadcast(AlertEvent(rule, firing = false, value = value, serverId = snapshot.serverId, timestampMs = snapshot.timestampMs))
            }
            state.consecutiveViolations = 0
        }
    }

    /**
     * 把一次事件广播给所有已注册通道;单通道异常被隔离,不影响其余通道。
     *
     * @param event 待广播的告警事件。
     */
    private fun broadcast(event: AlertEvent) {
        for (channel in channelRegistry.channels) {
            runCatching { channel.publish(event) }
                .onFailure { ProbeLogger.warn("告警通道呈现失败:${channel.javaClass.name},${it.message}") }
        }
    }

    /**
     * 规则与其运行状态的绑定。
     *
     * @property rule 规则定义(只读)。
     * @property state 规则运行状态(可变,仅编排单线程访问)。
     */
    private data class RuleHolder(val rule: AlertRule, val state: RuleState)

    /**
     * 单条规则的运行状态(防抖/恢复状态机)。
     *
     * 仅由 [evaluate] 所在的单一编排线程串行读写,故无需同步。
     *
     * @property consecutiveViolations 当前连续越线的采集周期数。
     * @property firing 当前是否处于已触发(告警中)状态。
     */
    private class RuleState(
        var consecutiveViolations: Int = 0,
        var firing: Boolean = false
    )
}
