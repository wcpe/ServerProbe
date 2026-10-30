package top.wcpe.mc.plugin.serverprobe.e2e

/**
 * Provider 健康状态 E2E两侧共用的**确定性**判据。
 *
 * ## 为什么两侧必须用同一份常量
 * 服务端（harness 注册的三个 Provider）按这些常量决定域名、健康值与阻塞时长，
 * fixture 侧按同一份常量断言"三个域必须被区分呈现"。若两侧各自手写字符串，
 * 任何一处笔误都会让断言变成"比较两个不同错误值"——看似跑通、实则没校验。
 * 与 [ChunkFixturePayload]、[TopicFixturePayload] 采用同一套做法。
 *
 * ## 判据来源
 * `docs/specs/provider-health.md`：
 * - §5「真机」一行：注册正常 / 抛异常 / 卡死三类 Provider，断言 `business_status` 三者区分呈现，
 *   且整段回执 `success=true`（单域降级不得影响整段——这是本功能最核心的语义）；
 * - §3.4 的回执形状：`domains{}` 每域含 `registered`/`health`/`reason`/`detailJson`/`sinceMs`/
 *   `refreshedAtMs`/`quarantined`/`dispatch`，外层另有 `refresh{}` 与 `timestamp`；
 * - §5「查询不派发」：连续查询 `business_status` 后该域 `dispatch` 计数**始终为 0**。
 *
 * ## 三个域名的字典序不是随手起的
 * 刷新线程按域名字典序逐个 probe，而 probe 池是**单线程且有界队列**（§3.3）：
 * 卡死域一旦先被 probe，唯一 probe 线程被占住，其余域本轮直接跳过——
 * 于是"正常域/抛异常域"根本拿不到首次健康值，断言就无从谈起。
 * 因此域名刻意用 `a`/`b`/`c` 前缀，把"卡死域永远排在最后"固化进命名：
 * 正常域 → 抛异常域 → 卡死域。
 */
internal object HealthFixturePayload {

    /** 正常域：`health()` 恒返回 `ProviderHealth.available()`，必须呈现为 AVAILABLE 且 reason 为空。 */
    const val OK_DOMAIN = "e2e-health-a-ok"

    /** 抛异常域：`health()` 直接抛 [IllegalStateException]，必须呈现为 DEGRADED。 */
    const val THROW_DOMAIN = "e2e-health-b-throw"

    /** 卡死域：`health()` 真阻塞远超 `bridge.health.timeout-ms`，必须呈现为超时降级。 */
    const val STUCK_DOMAIN = "e2e-health-c-stuck"

    /** 三个域（顺序即 probe 顺序，见类注释）。 */
    val ALL_DOMAINS: List<String> = listOf(OK_DOMAIN, THROW_DOMAIN, STUCK_DOMAIN)

    /** 正常域的期望等级。 */
    const val LEVEL_AVAILABLE = "AVAILABLE"

    /** 降级等级（超时与抛异常都是探针替 Provider 下的结论）。 */
    const val LEVEL_DEGRADED = "DEGRADED"

    /**
     * 抛异常归类的原因前缀，与 `HealthProbeRunner.REASON_THROW_PREFIX` 逐字一致。
     *
     * 这里必须自己持有常量而不是引 core 的内部类：harness 只链接 `api`（core 不在 classpath），
     * 而"前缀"本身就是对第三方可见的回执契约的一部分，理应在本场景里显式固定。
     */
    const val THROW_REASON_PREFIX = "health() 抛出异常"

    /** 超时归类的原因，与 `HealthProbeRunner.REASON_TIMEOUT` 逐字一致。 */
    const val TIMEOUT_REASON = "health() 超时"

    /** 抛异常域抛出的异常消息：额外断言它出现在 reason 里，证明是该 Provider 自己的异常被兜住。 */
    const val THROW_MESSAGE = "e2e-health-stub-broken"

    /**
     * 卡死域单次 `health()` 的阻塞时长。
     *
     * 必须**远大于**配置里的 `bridge.health.timeout-ms`（[HEALTH_TIMEOUT_MS]），
     * 这样超时判定只能来自"真阻塞"，不可能来自"恰好答得慢"。
     * 同时刻意不响应中断（见 harness 侧实现）：规格 §6 风险 2 的已知上限正是
     * "不可中断地卡住后 probe 线程被永久占住"，用可被 `Thread.sleep` 打断的实现会掩盖它。
     */
    const val STUCK_BLOCK_MS = 5_000L

    /** 卡死域忽略中断时的轮询粒度（毫秒）：睡眠而非自旋，避免空耗一个 CPU 核。 */
    const val STUCK_POLL_MS = 50L

    /**
     * 场景配置里写死的 `bridge.health.timeout-ms`。
     *
     * 必须与 `e2e/templates/configs/health-fixture/plugins/ServerProbe/config.yml` 一致：
     * 它是"卡死域多快被判超时"的唯一来源，两处失配会让"真阻塞到超时"这条断言失去意义。
     */
    const val HEALTH_TIMEOUT_MS = 300L

    /**
     * 逐项断言在证据表里的键前缀。
     *
     * fixture 把每条已成立的断言写成 `assert.<名字>=true`，harness 侧据此把它们**转登**进
     * [ScenarioResultWriter]，于是结果文件的 `assertions.total/passed` 是逐项断言汇总出来的，
     * 而不是"场景跑完即 PASS"。两侧共用同一常量，避免前缀笔误导致断言静默丢失。
     */
    const val ASSERT_PREFIX = "assert."

    /** 治理域与动作名（`BusinessStatusRoute` 的 order=110 路由）。 */
    const val DOMAIN_CORE = "core"
    const val ACTION_BUSINESS_STATUS = "business_status"

    /** Provider 的动作名：三个域都用它，方便 fixture 在需要时区分"这是业务调用而非查询"。 */
    const val ACTION_ECHO = "echo"
}
