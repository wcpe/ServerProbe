package top.wcpe.mc.plugin.serverprobe.core.testsupport

import taboolib.module.configuration.Configuration
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * 只回答取值方法的 `config.yml` 替身。
 *
 * 单测 classpath 里没有配置后端，无法构造真实的 TabooLib [Configuration]；本替身按"键 → 值"回答
 * 探针实际会问到的那几个取值方法，缺键时回落调用方传入的默认值，与真实配置语义一致。
 *
 * 现有单测（`ProbeConfigStandaloneTest` / `ManagedConfigResolverTest`）各自内联了同样的替身；
 * FR-46 的监听面与推送测试也要用，故抽到此处共用，避免第三份拷贝。
 *
 * @param values 键 → 值。
 * @return 可直接赋给 `ProbeConfig.conf` 的替身。
 */
// 单测辅助：按方法名分派取值是"表驱动"的必然形态，分支数由探针实际使用的方法数决定，拆开反而更难读
@Suppress("CyclomaticComplexMethod")
internal fun testConfiguration(values: Map<String, Any?> = emptyMap()): Configuration =
    Proxy.newProxyInstance(
        Configuration::class.java.classLoader,
        arrayOf(Configuration::class.java),
        InvocationHandler { _, method, args ->
            val key = args?.firstOrNull() as? String
            val fallback = args?.getOrNull(1)
            val value = values[key]
            when (method.name) {
                "getLong" -> (value as? Number)?.toLong() ?: fallback
                "getInt" -> (value as? Number)?.toInt() ?: fallback
                "getBoolean" -> value as? Boolean ?: fallback
                "getDouble" -> (value as? Number)?.toDouble() ?: fallback
                "getString" -> value as? String
                "getStringList" -> (value as? List<*>)?.map { it.toString() } ?: emptyList<String>()
                "contains" -> values.containsKey(key)
                "get" -> value
                else -> fallback
            }
        },
    ) as Configuration
