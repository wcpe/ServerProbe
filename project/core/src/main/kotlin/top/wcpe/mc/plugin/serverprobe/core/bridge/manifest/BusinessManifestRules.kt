package top.wcpe.mc.plugin.serverprobe.core.bridge.manifest

import top.wcpe.mc.plugin.serverprobe.api.bridge.ActionSpec
import top.wcpe.mc.plugin.serverprobe.api.bridge.BusinessProvider
import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainManifest
import top.wcpe.mc.plugin.serverprobe.api.bridge.LegacyBusinessProviderAdapter
import top.wcpe.mc.plugin.serverprobe.api.bridge.RiskLevel
import java.util.LinkedHashSet

/**
 * manifest 的注册期校验与聚合编码规则（spec §5.2 / §6 / §7）。
 *
 * 抽成独立对象而不是塞进 [top.wcpe.mc.plugin.serverprobe.core.bridge.BusinessHostImpl]：
 * 这里全是**无副作用的纯函数**（正则、去重、降级、编码），可以逐条单测，
 * 而宿主类里剩下的是并发与生命周期。混在一起会让"规则"淹没在锁与线程池里。
 *
 * ## 为什么降级在聚合期也要做一次
 * 注册期检查过的版本号，在聚合期仍可能变（Provider 的 manifest 是每次现算的）。
 * 若只在注册期判定，一个"注册后 manifest 才开始返回 apiVersion=2"的实现会绕过降级，
 * 把未适配的清单当作原生规格交给面板——正是 spec §5.2 要防的静默失败。
 */
object BusinessManifestRules {

    /**
     * 合法域名格式：小写字母 / 数字 / 连字符，1-32 字符（spec §4.1）。
     *
     * 不用 `String.matches` 每次编译正则：域名在每次注册时判定一次，但常量 Pattern 更清晰且可复用。
     */
    private val DOMAIN_PATTERN = Regex("^[a-z0-9-]{1,32}$")

    /**
     * 域名是否合法。
     *
     * @param domain 待判定域名；null 或空串一律非法
     * @return true=符合 `^[a-z0-9-]{1,32}$`
     */
    fun isValidDomain(domain: String?): Boolean = domain != null && DOMAIN_PATTERN.matches(domain)

    /**
     * 找出本域内重复声明的动作名（保持首次出现顺序）。
     *
     * 空动作名不参与判定：spec §7 只把"重复"列为拒绝条件，空名由 Provider 自行负责——
     * 探针替它决定"空名算非法"，会让一个只是忘了填名字的域整体不可用，代价远大于收益。
     *
     * @param manifest 待检查的清单；null 返回空集合
     * @return 重复出现的动作名；无重复返回空集合
     */
    fun duplicateActionNames(manifest: DomainManifest?): Set<String> {
        if (manifest == null) return emptySet()
        val seen = LinkedHashSet<String>()
        val duplicated = LinkedHashSet<String>()
        manifest.actions.forEach { spec ->
            val name = spec?.name.orEmpty()
            if (name.isEmpty()) return@forEach
            if (!seen.add(name)) duplicated.add(name)
        }
        return duplicated
    }

    /**
     * 判断该 Provider 的域在聚合 manifest 里是否应标 `specDeclared=true`。
     *
     * 两个否条件：来自旧契约适配（[LegacyBusinessProviderAdapter]），或 apiVersion 不受支持。
     *
     * @param provider 已注册的 Provider
     * @param manifest 本次现算的清单；为 null 表示 manifest() 取不到（抛异常），此时按"未声明"处理
     * @return true=规格可被下游信任
     */
    fun isSpecDeclared(provider: BusinessProvider, manifest: DomainManifest?): Boolean {
        if (LegacyBusinessProviderAdapter.isLegacy(provider)) return false
        return manifest != null && manifest.isApiVersionSupported
    }

    /**
     * 把清单降级为 legacy 形态：动作名保留，风险一律 [RiskLevel.WRITE]，幂等/摘要/schema/错误码清空。
     *
     * 与 [LegacyBusinessProviderAdapter] 的适配规则完全一致——「版本不匹配」在语义上就是
     * 「这份清单的字段含义我读不懂」，因此按同一套保守默认处理，而不是半信半疑地照搬。
     *
     * @param manifest 原始清单
     * @return 降级后的清单（apiVersion 归一为当前支持版本，避免下游再判一次）
     */
    fun degradeToLegacy(manifest: DomainManifest): DomainManifest {
        val actions = manifest.actions.map { spec ->
            ActionSpec.of(
                spec?.name.orEmpty(),
                "",
                RiskLevel.WRITE,
                false,
                null,
                null,
                null,
            )
        }
        return DomainManifest.of(
            manifest.domain,
            manifest.displayName,
            manifest.description,
            actions,
            emptyMap(),
        )
    }

    /**
     * 把一份清单编码为聚合信封里的单域子结构（spec §6）。
     *
     * @param manifest     已（必要时）降级后的清单
     * @param specDeclared 该域规格是否可信
     * @return 可直接 JSON 编码的不可变结构
     */
    fun encodeDomain(manifest: DomainManifest, specDeclared: Boolean): Map<String, Any?> = linkedMapOf(
        "displayName" to manifest.displayName,
        "description" to manifest.description,
        "specDeclared" to specDeclared,
        "actions" to manifest.actions.map(::encodeAction),
        "attributes" to LinkedHashMap<String, String>(manifest.attributes),
    )

    /**
     * 单个动作的聚合结构。
     *
     * `inputSchema` / `outputSchema` 未声明时写 `null` 而不是空串：spec §4.3 明确它们"可为 null"，
     * 空串会让下游误以为"声明了一个空 schema"。
     */
    private fun encodeAction(spec: ActionSpec): Map<String, Any?> = linkedMapOf(
        "name" to spec.name,
        "description" to spec.description,
        "risk" to spec.risk.name,
        "idempotent" to spec.idempotent,
        "inputSchema" to spec.inputSchema.ifEmpty { null },
        "outputSchema" to spec.outputSchema.ifEmpty { null },
        "errorCodes" to ArrayList(spec.errorCodes),
    )

    /**
     * 构造完整的聚合信封（spec §6）。
     *
     * @param domains domain → 已编码的单域结构；保持传入顺序
     * @return `{apiVersion, domains}` 结构的不可变 Map
     */
    fun encodeEnvelope(domains: Map<String, Map<String, Any?>>): Map<String, Any?> = linkedMapOf(
        "apiVersion" to DomainManifest.CURRENT_API_VERSION,
        "domains" to LinkedHashMap(domains),
    )

    /**
     * 单域失败时的占位结构（manifest() 抛异常等）。
     *
     * @param reason 失败原因（调用方已做脱敏/截断）
     * @return 仅含 `error` 的结构，字段集与既有行为一致
     */
    fun encodeDomainError(reason: String): Map<String, Any?> = linkedMapOf("error" to reason)
}
