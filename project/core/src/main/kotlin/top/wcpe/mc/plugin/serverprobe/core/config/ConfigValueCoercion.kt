package top.wcpe.mc.plugin.serverprobe.core.config

/**
 * 托管配置值的类型转换(协议规格 §3.3 的取值链末端)。
 *
 * 托管面下发的是 JSON 解析结果,类型与 [top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig] 的
 * 取值方法并非一一对应(JSON 数字可能落成 `Integer`/`Long`/`Double`,字符串也可能是数字文本)。
 * 这里把"原值 → 请求类型"的判定集中收口:**转换失败一律返回 null**,由调用方视为该层未命中并继续回落,
 * 绝不抛异常(探针读取配置不是事故源)。
 *
 * 抽出独立对象而不是留在解析器里,是因为它是一组无状态的纯函数,与取值顺序无关。
 */
internal object ConfigValueCoercion {

    /** 整数转换:兼收整型与取值为整数的浮点/字符串。 */
    fun asLong(value: Any): Long? = when (value) {
        is Int, is Long, is Short, is Byte -> (value as Number).toLong()
        is Double -> value.toLong().takeIf { it.toDouble() == value }
        is Float -> value.toLong().takeIf { it.toFloat() == value }
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    /** 整型转换:在 [asLong] 基础上要求落在 `Int` 值域内,越界视为未命中(不做静默截断)。 */
    fun asInt(value: Any): Int? {
        val long = asLong(value) ?: return null
        return long.toInt().takeIf { it.toLong() == long }
    }

    /** 浮点转换。 */
    fun asDouble(value: Any): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

    /** 文本转换:标量取其文本形式,容器类型视为未命中。 */
    fun asText(value: Any): String? = when (value) {
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> null
    }

    /** 字符串列表转换:列表逐项取文本;标量视为未命中。 */
    fun asTextList(value: Any): List<String>? {
        val list = value as? List<*> ?: return null
        return list.mapNotNull { if (it is String || it is Number || it is Boolean) it.toString() else null }
    }
}
