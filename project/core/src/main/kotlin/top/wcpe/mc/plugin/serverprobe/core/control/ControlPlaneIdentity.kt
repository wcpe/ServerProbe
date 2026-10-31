package top.wcpe.mc.plugin.serverprobe.core.control

/**
 * 外部控制面身份(托管模式的唯一开关载体)。
 *
 * 语义与字段定义见协议规格 `probe-managed-mode/spec.md` §3.2,本类只做数据承载与自校验,
 * **不含任何厂商分支**——[provider] 是数据,不是代码分支;任何厂商名都合法。
 *
 * ## credential 完全不透明(强制约束)
 * [credential] 对探针是不透明字符串:本类**不解析、不校验格式、不读内容**(不解析 JWT claims、不做前缀判断)。
 * 它只被原样带进握手 query 参数。为防泄漏,[toString] 恒以占位符替代,避免日志/异常信息把它带出去。
 *
 * @property schemaVersion 身份文件格式版本;探针只接受已知版本([SUPPORTED_SCHEMA_VERSION])。
 * @property provider 控制面标识(数据,不参与任何分支判断)。
 * @property endpoint 控制面反向 WS 接入地址。
 * @property instanceId 控制面侧的实例标识。
 * @property credential 不透明凭证;永不写日志、永不解析。
 * @property capabilities 对端声明的能力位列表;为空表示只用基础帧。
 * @property createdAt 身份签发时间(ISO-8601 UTC 文本,由对端或迁移写入,探针不解释)。
 */
data class ControlPlaneIdentity(
    val schemaVersion: Int,
    val provider: String,
    val endpoint: String,
    val instanceId: String,
    val credential: String,
    val capabilities: List<String>,
    val createdAt: String,
) {

    /**
     * 覆写文本表示:凭证以占位符替代。
     *
     * 必须覆写——Kotlin 默认 `data class` 的 toString 会把所有字段拼出来,一旦有人把它打进日志
     * 就是凭证明文泄漏(协议规格 §3.2:日志中永不出现)。
     */
    override fun toString(): String =
        "ControlPlaneIdentity(schemaVersion=$schemaVersion, provider=$provider, endpoint=$endpoint, " +
            "instanceId=$instanceId, credential=<不透明凭证,已隐藏>, capabilities=$capabilities, createdAt=$createdAt)"

    companion object {

        /** 探针当前认识的身份文件格式版本;其他版本一律视为"版本未知"。 */
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}

/**
 * 身份文件读取结果。
 *
 * 三态判定(协议规格 §3.5)直接消费本类型:[Loaded] → 托管,[Absent]/[Unusable] → 独立模式。
 * [Unusable] 携带具体原因,便于按规格要求区分告警文案(尤其"版本未知"必须明确说出来,不能静默)。
 */
sealed class IdentityLoadResult {

    /** 文件不存在(从未签发过身份)。 */
    object Absent : IdentityLoadResult()

    /** 读到合法身份。 */
    data class Loaded(val identity: ControlPlaneIdentity) : IdentityLoadResult()

    /**
     * 文件存在但不可用:按"视为不存在"降级独立模式 + WARN,绝不因此让探针启动失败(半写防御)。
     *
     * @property reason 不可用原因(决定告警文案)。
     * @property detail 附加说明(如缺哪个字段、读到什么版本号、解析失败位置),**绝不含凭证内容**。
     */
    data class Unusable(val reason: IdentityUnusableReason, val detail: String = "") : IdentityLoadResult()
}

/** 身份文件不可用的原因分类(仅用于日志措辞与测试断言)。 */
enum class IdentityUnusableReason {

    /** JSON 结构非法(半写/截断/非对象/超长等)。 */
    MALFORMED,

    /** schemaVersion 不在探针已知范围内。 */
    UNKNOWN_SCHEMA_VERSION,

    /** 必填字段缺失或为空白。 */
    MISSING_FIELD,
}
