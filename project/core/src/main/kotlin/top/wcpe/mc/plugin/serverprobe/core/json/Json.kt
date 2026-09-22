package top.wcpe.mc.plugin.serverprobe.core.json

import taboolib.module.configuration.Configuration
import taboolib.module.configuration.Type

/**
 * 全项目统一的 JSON 门面（ADR-14）：所有 JSON 编解码经此调用，不再手写、不直接耦合具体库。
 *
 * - [encode] / [parse]：JSON 文本的序列化与解析，走可替换的 [codec]（默认 [ConfigJsonCodec]，零依赖）。
 *   换库只需替换 [codec]，调用点不动。
 * - [decode]：JSON 文本 → 强类型对象的反射映射。默认走 TabooLib `Configuration` 反射，与落盘 [encode]
 *   成对绑定、共同定义持久化格式；因 TabooLib 反序列化为 reified 内联，单列在此门面而非 [JsonCodec] 接口。
 *
 * 线程安全：[codec] 为 `@Volatile`，仅在启动装配期替换；运行期只读。
 */
object Json {

    /** 当前生效的文本编解码后端；默认零依赖的 [ConfigJsonCodec]，启动装配期可替换以换库。 */
    @Volatile
    var codec: JsonCodec = ConfigJsonCodec

    /** 把对象 / Map / List / 基元序列化为紧凑单行 JSON 文本。 */
    fun encode(value: Any?): String = codec.encode(value)

    /** 解析 JSON 文本为只读树 [JsonObject]，按键取字段。 */
    fun parse(json: String): JsonObject = codec.parse(json)

    /**
     * JSON 文本 → 强类型对象（反射映射，忽略构造器）。
     *
     * 用于落盘读取（启动画像 / 指标快照等数据类）。后端为 TabooLib `Configuration` 反射，
     * 与 [encode] 落盘序列化成对——故换落盘后端须同步两侧并演进 `schemaVersion`，不在通用 [codec] 替换范围内。
     *
     * @param json JSON 文本。
     * @param ignoreConstructor 忽略目标类构造器（直接按字段填充），默认 true。
     */
    inline fun <reified T : Any> decode(json: String, ignoreConstructor: Boolean = true): T =
        Configuration.deserialize(
            Configuration.loadFromString(json, Type.JSON_MINIMAL),
            ignoreConstructor = ignoreConstructor
        )

    /**
     * 宽类型反序列化：先按 [decode] 严格绑定，失败时把**目标类里声明为装箱 `Long` 的字段**在 JSON 树中
     * 由小整数归一化为 `Long` 后重试一次。
     *
     * 为什么需要：TabooLib 的反射绑定对装箱类型要求类型精确匹配——JSON 里的小整数被解析成 `Integer`，
     * 绑到 `Long` 字段会抛 `Can not set final java.lang.Long field ... to java.lang.Integer`，**整份文档**
     * 反序列化就此失败。真机表现是启动早像回读恒失败、"与上次启动对比"永久退化为"首次记录"
     * （issue #46）；而 `MetricSnapshot` 这类标量全是基本类型的模型不受影响，FR-26 落盘回读真机正常。
     *
     * 只归一化**装箱 `Long`**：基本类型与其它装箱类型（`Boolean`/`Integer`/`Double`）保持原样——
     * 全局把整数转 `Long` 会把本来能绑的 `int` 字段弄坏，而按目标类反射取值可精确避开。
     * 失败仍抛首次的异常（不吞错），调用方按既有语义处理。
     *
     * @param json JSON 文本。
     * @param ignoreConstructor 忽略目标类构造器（直接按字段填充），默认 true。
     */
    inline fun <reified T : Any> decodeLenient(json: String, ignoreConstructor: Boolean = true): T =
        runCatching { decode<T>(json, ignoreConstructor) }.getOrElse { failure ->
            runCatching {
                val config = Configuration.loadFromString(json, Type.JSON_MINIMAL)
                boxedLongFieldNames(T::class.java).forEach { name ->
                    if (config.contains(name)) {
                        (config.get(name) as? Int)?.let { value -> config.set(name, value.toLong()) }
                    }
                }
                Configuration.deserialize(config, ignoreConstructor = ignoreConstructor) as T
            }.getOrElse { throw failure }
        }

    /**
     * 取目标类中声明为装箱 `Long` 的字段名（基本类型 `long` 不在内）；容器/同名遮蔽场景按声明逐个纳入。
     *
     * `@PublishedApi` 是因为被公开 inline 的 [decodeLenient] 调用；可见性仍是模块内，供单测直接断言。
     */
    @PublishedApi
    internal fun boxedLongFieldNames(type: Class<*>): List<String> =
        type.declaredFields.filter { it.type == java.lang.Long::class.java }.map { it.name }
}
