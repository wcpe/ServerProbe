package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionSpec
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainManifest

/**
 * 测试用 manifest 构造器（起 `BusinessProvider.manifest` 返回强类型对象）。
 *
 * 单独抽出来是因为各测试类只需要"能注册的最小清单"，逐处手写
 * `DomainManifest.of(...)` 会把无关噪声盖到断言上；而各测试类的关注点从不是 manifest 本身。
 */
internal object TestManifests {

    /** 空动作清单（绝大多数测试桩的需求）。 */
    fun empty(domain: String): DomainManifest =
        DomainManifest.of(domain, domain, "", emptyList(), emptyMap())

    /** 带若干声明了 READ 风险的占位动作。 */
    fun withActions(domain: String, vararg actions: String): DomainManifest = DomainManifest.of(
        domain,
        domain,
        "",
        actions.map { ActionSpec.read(it, "") },
        emptyMap(),
    )
}
