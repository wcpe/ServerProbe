package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.bridge.BridgeCommandResult
import top.wcpe.mc.plugin.serverprobe.api.bridge.Principal

/**
 * 一条由 JianManager Worker 经反向 WS 下发给探针的**治理**指令。
 *
 * 平台无关的数据载体:core 解析 `command` 帧得到本对象,交平台层 [BridgeCommandHandler] 执行
 * (Bukkit/Bungee 各自实现踢/封/解封/白名单/在线列表)。
 *
 * ## 为什么不进 `api` 模块
 * 治理指令是探针与平台之间的内部契约,外部业务插件永远用不到;`target`/`reason` 这类
 * 治理专用字段进对外制品只会固化无用承诺。业务侧的指令载体是
 * [top.wcpe.mc.plugin.serverprobe.api.bridge.ActionRequest]（域/动作/参数/回执标识/操作者）。
 *
 * @property action 指令动作:kick | ban | unban | whitelist_add | whitelist_remove | list | whitelist_list;
 *   或业务动作(domain 非空时,语义由对应 Provider 解释)。
 * @property target 目标玩家名(list/whitelist_list 时为空)。
 * @property reason 踢/封原因(其余为空)。
 * @property requestId 关联回执标识:执行后回 command_result 须带回同一 requestId。
 * @property domain 业务域命名空间:空 / core = 内建治理;economy/inventory… = 业务命令。
 * @property payload 业务结构化参数 JSON(domain 非空时;治理命令为空)。
 * @property principal 操作者上下文:业务写动作由 JM 注入,治理命令为 [Principal.unknown]。
 */
data class BridgeCommand(
    val action: String,
    val target: String,
    val reason: String,
    val requestId: String,
    val domain: String = "",
    val payload: String = "",
    val principal: Principal = Principal.unknown(),
)

/**
 * 治理/查询指令的平台执行器。由各平台(Bukkit/Bungee)以 IOC `@Service` 实现并注入桥。
 *
 * core 平台无关,不能直接调 Bukkit/Bungee API;故抽此接口,把「执行平台 API」下沉到平台模块。
 * 实现须**线程安全**且**绝不抛**(失败返回 [BridgeCommandResult.fail])。
 * 需主线程执行的平台操作(如 Bukkit kick/ban)由实现自行切回主线程并同步等待结果。
 *
 * 注：结果类型 [BridgeCommandResult] 已下沉到 `api` 模块，本接口仅复用该类型。
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
