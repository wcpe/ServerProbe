package top.wcpe.mc.plugin.serverprobe.core.config

import top.wcpe.mc.plugin.serverprobe.core.control.ConfigTier
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneIdentity
import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneRuntime

/**
 * 配置源抽象(协议规格 §3.3):66 个配置键的**唯一取值入口**。
 *
 * ## 取值顺序(规格 §3.3)
 * ```
 * 托管模式:[ManagedConfigStore] 当前生效值 → 上一次的值 → config.yml → 代码默认值
 * 独立模式:config.yml → 代码默认值
 * ```
 *
 * ## 独立模式逐字节一致(硬门槛)
 * 独立模式下每个取值方法**只**转调 `config.yml` 的同名读取方法并透传同一个默认值,
 * 不新增任何转换、兜底或日志——[getLong] 就是 `conf.getLong(key, default)` 本身。
 * 这是"独立模式零回归"的实现基础,改动本类时必须守住。
 *
 * ## 托管模式下 FORBIDDEN 键一律回落
 * `bridge` / `metrics` / `mcp` / `web` 整组属于禁止远程档(见 [ConfigTier]):托管面永不下发这些键,
 * 读取时也**先判档再查缓存**,命中禁止路径直接回落 `config.yml`(其中 `bridge.*` 更进一步由身份文件派生),
 * 不开任何"缓存里恰好有就用"的口子。
 *
 * ## 类型不符即降级
 * 缓存里的值类型与请求的取值类型不符时(只可能来自异常载荷),该层视为未命中,继续往下一层回落,
 * 绝不抛异常——探针读取配置不应成为事故源。
 */
object ManagedConfigResolver {

    /**
     * 当前是否托管模式。
     *
     * @return true 表示托管模式(身份文件存在且合法)。
     */
    fun isManaged(): Boolean = ControlPlaneRuntime.isManaged()

    /**
     * 取长整型配置。
     *
     * @param key 配置键名。
     * @param default 代码内默认值。
     * @return 生效值。
     */
    fun getLong(key: String, default: Long): Long =
        resolve(key) { ConfigValueCoercion.asLong(it) } ?: ProbeConfig.conf.getLong(key, default)

    /**
     * 取整型配置。
     *
     * @param key 配置键名。
     * @param default 代码内默认值。
     * @return 生效值。
     */
    fun getInt(key: String, default: Int): Int =
        resolve(key) { ConfigValueCoercion.asInt(it) } ?: ProbeConfig.conf.getInt(key, default)

    /**
     * 取布尔型配置。
     *
     * @param key 配置键名。
     * @param default 代码内默认值。
     * @return 生效值。
     */
    fun getBoolean(key: String, default: Boolean): Boolean =
        resolve(key) { it as? Boolean } ?: ProbeConfig.conf.getBoolean(key, default)

    /**
     * 取浮点型配置。
     *
     * @param key 配置键名。
     * @param default 代码内默认值。
     * @return 生效值。
     */
    fun getDouble(key: String, default: Double): Double =
        resolve(key) { ConfigValueCoercion.asDouble(it) } ?: ProbeConfig.conf.getDouble(key, default)

    /**
     * 取字符串配置(`config.yml` 侧不设默认值,与改造前一致)。
     *
     * @param key 配置键名。
     * @return 生效值;未配置时为 null。
     */
    fun getString(key: String): String? = resolve(key) { ConfigValueCoercion.asText(it) } ?: ProbeConfig.conf.getString(key)

    /**
     * 取字符串列表配置(`config.yml` 侧缺失时为空列表,与改造前一致)。
     *
     * @param key 配置键名。
     * @return 生效值(列表)。
     */
    fun getStringList(key: String): List<String> =
        resolve(key) { ConfigValueCoercion.asTextList(it) } ?: ProbeConfig.conf.getStringList(key)

    /**
     * 插件桥总开关。
     *
     * 托管模式下恒为 true:身份文件存在即代表"该连控制面"(协议规格 §3.5),不看 `bridge.enabled`。
     *
     * @param key `bridge.enabled` 的键名。
     * @param default 代码内默认值。
     * @return 是否开启插件桥。
     */
    fun bridgeEnabled(key: String, default: Boolean): Boolean =
        if (managedIdentity() != null) true else getBoolean(key, default)

    /**
     * 插件桥目标地址。
     *
     * 托管模式取身份文件的 `endpoint`(网关地址在托管面,不在 `config.yml`)。身份字段原样透传、不做 trim:
     * 与 [top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeClient] 使用同一份身份值,避免同一身份两种取值。
     *
     * @param key `bridge.url` 的键名。
     * @param default 代码内默认值。
     * @return WS 地址。
     */
    fun bridgeUrl(key: String, default: String): String =
        managedIdentity()?.endpoint ?: (getString(key)?.trim() ?: default)

    /**
     * 插件桥实例标识。
     *
     * 托管模式取身份文件的 `instanceId`(须与凭证内 instanceId 一致,由控制面签发),原样透传。
     *
     * @param key `bridge.instance` 的键名。
     * @param default 代码内默认值。
     * @return 实例标识。
     */
    fun bridgeInstance(key: String, default: String): String =
        managedIdentity()?.instanceId ?: (getString(key)?.trim() ?: default)

    /**
     * 插件桥连接凭证(安全:本方法返回值绝不写入日志)。
     *
     * 托管模式取身份文件的 `credential`:**不透明字符串,原样透传**——不解析、不校验格式、连 trim 都不做
     * (任何字节级改动都可能让对端拒签)。
     *
     * @param key `bridge.token` 的键名。
     * @param default 代码内默认值。
     * @return 凭证;未配置时为空串。
     */
    fun bridgeToken(key: String, default: String): String =
        managedIdentity()?.credential ?: (getString(key)?.trim() ?: default)

    /**
     * 校验一次下发(单键,带当前生效值上下文)。
     *
     * 校验规则全部在 [ConfigTier] 里(与 CP 写入侧共用一份),本方法只负责补上
     * 跨键约束所需的"当前生效值"回调。FR-44 应用 `config_push` 时逐个键调用。
     *
     * @param key 配置键名。
     * @param value 下发的值。
     * @return 通过返回 null;拒绝返回中文拒绝原因。
     */
    fun validate(key: String, value: Any?): String? = ConfigTier.validate(key, value) { effectiveRaw(it) }

    /**
     * 热替换托管生效值(内存原子替换,供 FR-44 调用)。
     *
     * @param values 新的一代生效值。
     * @return true 表示内容确有变化。
     */
    fun replace(values: Map<String, Any?>): Boolean = ManagedConfigStore.replace(values)

    /** 清空托管缓存(回到"从未下发"的初始状态)。 */
    fun clear() = ManagedConfigStore.clear()

    /**
     * 按"当前值 → 上一次的值"取托管面原值。
     *
     * 独立模式、禁止远程档路径一律返回 null(即直接回落 `config.yml`),不碰缓存。
     */
    private inline fun <T : Any> resolve(key: String, convert: (Any) -> T?): T? {
        if (!isManaged() || ConfigTier.isForbiddenPath(key)) {
            return null
        }
        // 只取一次快照:保证 current/previous 来自同一代组合,不会跨代拼接
        val generations = ManagedConfigStore.generations()
        ManagedConfigStore.valueOf(generations.current, key)?.let { raw ->
            val converted = convert(raw)
            if (converted != null) {
                return converted
            }
        }
        ManagedConfigStore.valueOf(generations.previous, key)?.let { raw ->
            return convert(raw)
        }
        return null
    }

    /** 当前身份;独立模式为 null。 */
    private fun managedIdentity(): ControlPlaneIdentity? =
        if (isManaged()) ControlPlaneRuntime.identity() else null

    /**
     * 跨键约束所需的"合并本次下发后的生效值":缓存里没有就退回 `config.yml`。
     *
     * 取不到时返回 null,由 [ConfigTier] 跳过该约束(宁可少拦一次,也不误拒)。
     */
    private fun effectiveRaw(key: String): Any? {
        if (isManaged() && !ConfigTier.isForbiddenPath(key)) {
            val generations = ManagedConfigStore.generations()
            ManagedConfigStore.valueOf(generations.current, key)?.let { return it }
            ManagedConfigStore.valueOf(generations.previous, key)?.let { return it }
        }
        // 跨键约束当前只用于数值比较(history-capacity),而整型读取无法区分"键缺失"与"值为 0",故先判存在
        return runCatching { if (ProbeConfig.conf.contains(key)) ProbeConfig.conf.get(key) else null }.getOrNull()
    }
}
