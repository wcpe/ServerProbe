package top.wcpe.mc.plugin.serverprobe

import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI

/**
 * 跨插件**只读** API 静态门面——**兼容壳，已废弃**。
 *
 * ## 现状：本类仅为兼容壳
 * 统一入口已收敛到 api 模块的 [ServerProbeAPI]：只读数据走 `ServerProbeAPI.read()`，
 * 业务注册走 `ServerProbeAPI.businessHost()`。本类因此只剩一件事——
 * 把历史上的 `read(): ProbeReadApi?` 调用点原样转发到 `ServerProbeAPI.read()`，
 * 使老代码不改也能拿到**同一个**实例。
 *
 * | 入口 | 状态 |
 * |---|---|
 * | `ServerProbeAPI.read()`（api 模块） | **唯一推荐入口**：永不返回 null 的稳定代理，可缓存为字段 |
 * | `ServerProbeApi.read()`（本类） | **已废弃**：仅为二进制/源码兼容保留，下个大版本移除 |
 *
 * ## 与 `ServerProbeAPI` 的分工（已收敛，不再有分工）
 * 本类早期是 plugin 模块里唯一能取到 [ProbeReadApi] 的路径，靠运行期 IOC 容器
 * （`BeanContainer`）按类型取 bean，未就绪时返回 `null`。api 模块出现后，
 * 同一实例改由装配器经 `BridgeServices.read()` 注入稳定代理，不再需要调用方判空。
 * **两条路返回同一实例、行为一致**，本类不再自带任何取 bean 逻辑。
 *
 * ## 为什么还留着
 * 已有第三方插件按 `read()` 的 `null` 语义编写了 `?: return`，直接删类会让它们
 * `NoClassDefFoundError`。保留期间签名（可空返回类型）不变，故源码与二进制都还能编译链接。
 *
 * ## 不再新增引用
 * 新接入方请直接用 [ServerProbeAPI].read()；本类不提供任何 api 模块没有的能力。
 */
object ServerProbeApi {

    /**
     * 容错获取只读开放接口实例。
     *
     * **已废弃**：请改用 [ServerProbeAPI].read()（api 模块，非空、稳定代理）。
     * 本方法现等价于它——返回同一个稳定代理实例，**不再是 `null`**：
     * 探针未就绪时该代理的查询一律降级为无数据（列表空列表、对象 null、聚合空窗口、
     * 取证空页与"未启用"状态），绝不抛异常。
     *
     * 签名仍保留可空类型 `ProbeReadApi?`，以免打断既有的 `?: return` 写法；
     * 但实际返回恒非 null，`?:` 分支不会再被走到。
     *
     * **Java 消费方注意**：本方法已加 `@JvmStatic`，可直接 `ServerProbeApi.read()`；
     * 未加之前需要写 `ServerProbeApi.INSTANCE.read()`（Kotlin object 的默认形态）。
     *
     * @return 只读开放接口实例（稳定代理）；恒非 null。
     */
    @Deprecated(
        message = "统一改用 ServerProbeAPI.read()（api 模块，非空、稳定代理）；本类将在下个大版本移除",
        replaceWith = ReplaceWith("ServerProbeAPI.read()"),
        level = DeprecationLevel.WARNING,
    )
    @JvmStatic
    fun read(): ProbeReadApi? = ServerProbeAPI.read()
}
