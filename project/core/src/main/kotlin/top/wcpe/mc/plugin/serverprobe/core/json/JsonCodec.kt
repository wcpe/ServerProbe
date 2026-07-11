package top.wcpe.mc.plugin.serverprobe.core.json

/**
 * JSON 编解码适配器（ADR-14）。
 *
 * 把"JSON 文本的序列化与解析"收口到一个可替换的抽象接口，使全项目不再各自手写或直接耦合具体库。
 * 默认实现 [ConfigJsonCodec] 复用 TabooLib 运行时自带的 `Configuration`（nightconfig），零额外依赖；
 * 需要换库（如 gson/jackson）时只新增一个实现并替换 [Json.codec]，所有调用点不动。
 *
 * 抽象边界：本接口管"对象/Map ↔ JSON 文本"与"JSON 文本 → 只读树取字段"。
 * 反射对象映射（JSON ↔ 强类型数据类）见 [Json.decode]，因 TabooLib 反序列化为 reified 内联、
 * 且落盘对象映射与其序列化成对绑定具体库（定义持久化格式），单列在门面上。
 */
interface JsonCodec {

    /**
     * 把对象 / Map / List / 基元序列化为紧凑单行 JSON 文本。
     *
     * @param value 待序列化值；约定传 `Map<String, Any?>`（顺序保留用 `LinkedHashMap`）或可被后端反射序列化的对象。
     * @return 紧凑 JSON 文本（无缩进/换行）。
     */
    fun encode(value: Any?): String

    /**
     * 解析 JSON 文本为只读树 [JsonObject]，用于按键取字段（不做强类型对象映射）。
     *
     * @param json JSON 文本。
     * @return 只读树；非法 JSON 由实现决定（[ConfigJsonCodec] 抛运行期异常，调用方按需 `runCatching`）。
     */
    fun parse(json: String): JsonObject
}

/**
 * 只读 JSON 对象树：按键取字段，缺失返回给定默认值。
 *
 * 设计为库无关的最小取值面（仅覆盖现有调用点所需），便于不同 [JsonCodec] 实现各自包装其原生节点。
 */
interface JsonObject {

    /** 取字符串字段；缺失或类型不符返回 [default]。 */
    fun getString(key: String, default: String = ""): String

    /** 取整型字段；缺失返回 [default]。 */
    fun getInt(key: String, default: Int = 0): Int

    /** 取长整型字段；缺失返回 [default]。 */
    fun getLong(key: String, default: Long = 0L): Long

    /** 取双精度浮点字段；缺失或类型不符返回 [default]。 */
    fun getDouble(key: String, default: Double = 0.0): Double

    /** 取布尔字段；缺失返回 [default]。 */
    fun getBoolean(key: String, default: Boolean = false): Boolean

    /** 取字符串列表；缺失返回空列表。 */
    fun getStringList(key: String): List<String>

    /** 是否包含该键。 */
    fun contains(key: String): Boolean

    /** 取嵌套对象；缺失返回 null。 */
    fun getObject(key: String): JsonObject?
}
