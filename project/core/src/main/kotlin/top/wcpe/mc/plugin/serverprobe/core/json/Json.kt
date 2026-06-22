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
}
