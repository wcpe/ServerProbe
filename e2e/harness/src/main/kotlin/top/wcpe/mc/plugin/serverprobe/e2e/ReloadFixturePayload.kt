package top.wcpe.mc.plugin.serverprobe.e2e

/**
 * 配置重载通知 E2E两侧共用的**确定性**判据。
 *
 * ## 为什么两侧必须用同一份常量
 * 服务端（harness 注册的三个监听器）按这些常量决定"关注哪些路径、回什么结果、自报什么值"，
 * fixture 侧按同一份常量断言回执的每一个字段。若两侧各自手写字符串，任何一处笔误都会让断言变成
 * "比较两个不同错误值"——看似跑通、实则没校验。与 [ChunkFixturePayload]、[TopicFixturePayload]、
 * [HealthFixturePayload] 采用同一套做法。
 *
 * ## 判据来源
 * `docs/specs/config-reload.md`：
 * - §5「成功」：监听器返回 `ok()` → `reloaded` 含该 path、`failed` 空、`success=true`、
 *   `costMs >= 0`、**监听器调用计数 +1**（最后这条是本场景的核心，不能只看回执）；
 * - §5「无人认领」：无监听器 → `bestEffort` 含该 path、`reloaded` 与 `failed` 均空、`success=true`；
 * - §5「插件拒绝」/「部分成功」：`failed[0].code=PLUGIN_REJECTED`，且 `reloaded` 仍如实列出成功项；
 * - §5「越界」：`../../etc/passwd` → `PATH_ESCAPE`，且**监听器一次都不被调用**；
 * - §3.7 回执形态：`success`/`reloaded`/`bestEffort`/`failed`/`applied`/`costMs`/`error`，
 *   判定式 `success = failed.isEmpty() && error.isEmpty()`；
 * - §3.7「`applied`：自报字段，**缺省该键表示未上报**，不要写 0」——`count=0` 是合法事实，
 *   与"没上报"必须区分（§3.2 用 `OptionalInt` 的理由）。
 *
 * ## 为什么监听器声明是"三份"而不是"一份"
 * 匹配语义是「精确」与「目录前缀」两种形态（§3.4）。要同时证明两件事——
 * 前缀声明能覆盖同目录下的文件、且**精确与前缀会各被通知一次**——就必须让两者覆盖同一路径。
 * 而失败与无人认领又必须彼此隔离：
 * - 失败路径必须**只**被返回 `failed` 的监听器命中，否则"同一结果作用于全部命中路径"
 *   （§3.1 一次回调覆盖 `matchedPaths`）会把成功路径一并判失败；
 * - 无人认领路径必须**不被任何监听器**声明，而前缀监听器覆盖整个 `plugins/ReloadFixture/`，
 *   所以它只能放在该目录之外（[UNCLAIMED_PATH]）。
 */
internal object ReloadFixturePayload {

    /** 逐项断言在证据表里的键前缀（与 harness 的转登约定一致）。 */
    const val ASSERT_PREFIX = "assert."

    /** 治理域与动作名（`ConfigReloadRoute` 的 order=100 路由）。 */
    const val DOMAIN_CORE = "core"
    const val ACTION_CONFIG_RELOAD = "config_reload"

    /** 精确监听器声明的路径：同一条路径也会被目录前缀监听器命中（本场景的关键设计）。 */
    const val EXACT_PATH = "plugins/ReloadFixture/ok.yml"

    /** 目录前缀声明（末尾斜杠是语义，见 §3.4）；覆盖 `plugins/ReloadFixture/` 下任意层级。 */
    const val PREFIX = "plugins/ReloadFixture/"

    /** 只被"拒绝监听器"命中的路径（前缀监听器也会命中它，但精确监听器不会）。 */
    const val REJECT_PATH = "plugins/ReloadFixture/reject.yml"

    /**
     * 只上报 `count` 的路径：用来断言"未上报的字段整个缺席（不写 0）"与"上报 0 是合法事实"。
     *
     * 它在 [PREFIX] 之下，因此只会被前缀监听器命中；该监听器对含此路径的事件
     * 只调用 `withAppliedCount(0)`，不调用 `withAppliedMd5` / `withAppliedVersion`。
     */
    const val PARTIAL_PATH = "plugins/ReloadFixture/partial.yml"

    /**
     * 无人认领路径：**必须落在 [PREFIX] 之外**。
     *
     * 前缀监听器覆盖整个 `plugins/ReloadFixture/`，若把它放进那个目录，
     * 它就会被认领，"无人认领 → bestEffort"这条判据将无从验证。
     */
    const val UNCLAIMED_PATH = "plugins/ReloadUnclaimed/nobody.yml"

    /** 越界路径：`..` 在栈空时出现即逃逸（§3.3），回执里保留**原始输入**便于 JM 对账。 */
    const val ESCAPE_PATH = "../../etc/passwd"

    /** 前缀监听器自报的 md5（原样透传判据）。 */
    const val MD5 = "e2e-reload-md5-0001"

    /** 前缀监听器自报的版本标识（原样透传判据）。 */
    const val VERSION = "e2e-reload-v7"

    /** 前缀监听器自报的条目数（原样透传判据）。 */
    const val COUNT = 12

    /** [PARTIAL_PATH] 上的自报条目数：**0 是合法事实**，必须原样出现而不是被当成"未上报"删掉。 */
    const val PARTIAL_COUNT = 0

    /** 前缀监听器自带的详情 JSON（§3.2：原样交给 JM，探针不解析）。 */
    const val DETAIL_JSON = "{\"e2e\":\"reload-fixture\"}"

    /** 拒绝监听器返回的可读消息：断言它原样出现在 `failed[].message` 里。 */
    const val REJECT_MESSAGE = "e2e-reload-fixture-rejected"

    /** 期望的错误码字面量（与 `ConfigReloadErrorCode` 的枚举名一致）。 */
    const val CODE_PLUGIN_REJECTED = "PLUGIN_REJECTED"
    const val CODE_PATH_ESCAPE = "PATH_ESCAPE"

    /**
     * 场景配置里写死的 `bridge.reload.timeout-ms`。
     *
     * 必须与 `e2e/templates/configs/reload-fixture/plugins/ServerProbe/config.yml` 一致：
     * 它是"本次编排的预算"唯一来源。取值不必很小（本场景没有卡死监听器），
     * 只需在真机上留够首次派发的类加载与线程启动开销。
     */
    const val TIMEOUT_MS = 2_000L

    /** 单条命令的期望 requestId；顺序下发、逐条核对归属。 */
    const val REQUEST_OK = "e2e-reload-ok"
    const val REQUEST_UNCLAIMED = "e2e-reload-unclaimed"
    const val REQUEST_PARTIAL_APPLIED = "e2e-reload-partial-applied"
    const val REQUEST_PARTIAL_SUCCESS = "e2e-reload-partial-success"
    const val REQUEST_ESCAPE = "e2e-reload-escape"
}
