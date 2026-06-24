package top.wcpe.mc.plugin.serverprobe.core.bridge

/**
 * 一条由 JianManager Worker 经反向 WS 下发给探针的治理/查询指令（FR-067,见 JianManager ADR-016）。
 *
 * 平台无关的数据载体:core 解析 `command` 帧得到本对象,交平台层 [BridgeCommandHandler] 执行
 * (Bukkit/Bungee 各自实现踢/封/解封/白名单/在线列表)。字段命名与 Worker 下发约定一致。
 *
 * @property action 指令动作:kick | ban | unban | whitelist_add | whitelist_remove | list | whitelist_list;
 *   或业务动作(domain 非空时,如 balance / add,语义由对应 [BusinessProvider] 解释)。
 * @property target 目标玩家名(list/whitelist_list 时为空)。
 * @property reason 踢/封原因(其余为空)。
 * @property requestId 关联回执标识:执行后回 command_result 须带回同一 requestId,Worker 据此匹配同步等待者。
 * @property domain 业务域命名空间(JBIS,见 ADR-0015):空 / core = 内建治理(既有);economy/inventory… = 业务命令,
 *   由 [BridgeClient] 路由到 [BusinessHost] 对应 Provider。
 * @property payload 业务结构化参数 JSON(domain 非空时;治理命令为空)。
 */
data class BridgeCommand(
    val action: String,
    val target: String,
    val reason: String,
    val requestId: String,
    val domain: String = "",
    val payload: String = "",
)

/**
 * 一条治理/查询指令的执行结果(FR-067)。由平台层 [BridgeCommandHandler] 产出,core 回传给 Worker。
 *
 * @property success 是否执行成功。
 * @property output 输出文本(list/whitelist_list 的玩家列表等;成功且有内容时填充)。
 * @property error 失败原因(success=false 时填充)。
 */
data class BridgeCommandResult(
    val success: Boolean,
    val output: String = "",
    val error: String = "",
) {
    companion object {

        /** 成功结果(可带输出)。 */
        fun ok(output: String = ""): BridgeCommandResult = BridgeCommandResult(success = true, output = output)

        /** 失败结果(带原因)。 */
        fun fail(error: String): BridgeCommandResult = BridgeCommandResult(success = false, error = error)
    }
}

/**
 * 治理/查询指令的平台执行器(FR-067)。由各平台(Bukkit/Bungee)以 IOC `@Service` 实现并注入 [BridgeClient]。
 *
 * core 平台无关,不能直接调 Bukkit/Bungee API;故抽此接口,把「执行平台 API」下沉到平台模块。
 * 实现须**线程安全**(可能在桥读线程被调用)且**绝不抛**(失败返回 [BridgeCommandResult.fail])。
 * 需主线程执行的平台操作(如 Bukkit kick/ban)由实现自行切回主线程并同步等待结果。
 */
interface BridgeCommandHandler {

    /**
     * 执行一条指令并返回结果。
     *
     * @param command 待执行指令。
     * @return 执行结果(成功/输出/失败原因)。
     */
    fun handle(command: BridgeCommand): BridgeCommandResult
}
