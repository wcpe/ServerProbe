package top.wcpe.mc.plugin.serverprobe.core.bridge

/**
 * 一个业务域的对接 Provider（JBIS 业务对接,见 ADR-0015 / JianManager ADR-026）。
 *
 * 每个业务插件（经济 / 背包…）对应一个 Provider,住在 platform 层——**唯一认识具体插件 API 的地方**;
 * 经 [BusinessHost] 按域注册,[BridgeClient] 收到带 [domain] 的业务 `command` 帧时路由到对应 Provider 执行。
 * core 经本接口解耦,编译期不依赖任何业务插件(分层依赖单向不破)。
 *
 * ## 实现约定
 * - **线程安全**且**绝不抛**:失败返回 [BridgeCommandResult.fail],不要抛异常(由 [BusinessHost] 兜底亦然)。
 * - 由 [BusinessHost] 在**独立业务线程池**调用(事故域隔离,不占桥读线程 / 监控线程 / 主线程);
 *   需主线程的平台操作由实现自行切回主线程并同步等待(同 BukkitBridgeCommandHandler 范式)。
 */
interface BusinessProvider {

    /** 业务域命名空间(如 economy / inventory),全局唯一,与 Worker 下发的 domain 对应。 */
    val domain: String

    /**
     * 本域能力清单:声明支持的动作与字段 schema,供 JianManager 经 [BusinessHost] 汇总后动态发现 / 校验 / 渲染。
     *
     * @return 可被 JSON 序列化的结构化清单(如 `mapOf("actions" to listOf("balance"))`)。
     */
    fun manifest(): Map<String, Any?>

    /**
     * 执行一条本域业务动作。
     *
     * @param action 动作名(如 balance / add / transfer)。
     * @param payload 结构化业务参数 JSON 字符串(由实现自行解析)。
     * @return 执行结果;业务输出(如余额 JSON)放入 [BridgeCommandResult.output]。
     */
    fun dispatch(action: String, payload: String): BridgeCommandResult
}
