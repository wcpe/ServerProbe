package top.wcpe.mc.plugin.serverprobe.core.mcp

import java.beans.Introspector
import java.lang.reflect.Array
import java.util.IdentityHashMap

/** MCP 响应专用 JSON 写入器，避免配置序列化器处理嵌套响应时中断 HTTP 输出。 */
internal object McpJsonWriter {

    fun encode(value: Any?): String = StringBuilder().also { write(it, value, IdentityHashMap(), 0) }.toString()

    private fun write(output: StringBuilder, value: Any?, visited: IdentityHashMap<Any, Unit>, depth: Int) {
        when (value) {
            null -> output.append(NULL)
            is CharSequence, is Char, is Enum<*> -> string(output, value.toString())
            is Boolean -> output.append(value)
            is Number -> number(output, value)
            is Map<*, *> -> objectValue(output, value, visited, depth)
            is Iterable<*> -> arrayValue(output, value.asSequence(), visited, depth)
            else -> if (value.javaClass.isArray) {
                arrayValue(output, arraySequence(value), visited, depth)
            } else {
                beanValue(output, value, visited, depth)
            }
        }
    }

    private fun objectValue(output: StringBuilder, value: Map<*, *>, visited: IdentityHashMap<Any, Unit>, depth: Int) {
        cycleOrDepth(output, value, visited, depth) { nextDepth ->
            output.append('{')
            value.entries.take(MAX_ITEMS).forEachIndexed { index, entry ->
                if (index > 0) output.append(',')
                string(output, entry.key.toString())
                output.append(':')
                write(output, entry.value, visited, nextDepth)
            }
            output.append('}')
        }
    }

    private fun arrayValue(output: StringBuilder, values: Sequence<*>, visited: IdentityHashMap<Any, Unit>, depth: Int) {
        output.append('[')
        values.take(MAX_ITEMS).forEachIndexed { index, item ->
            if (index > 0) output.append(',')
            write(output, item, visited, depth + 1)
        }
        output.append(']')
    }

    private fun beanValue(output: StringBuilder, value: Any, visited: IdentityHashMap<Any, Unit>, depth: Int) {
        cycleOrDepth(output, value, visited, depth) { nextDepth ->
            output.append('{')
            Introspector.getBeanInfo(value.javaClass, Any::class.java).propertyDescriptors
                .asSequence().filter { it.readMethod != null }.take(MAX_ITEMS).forEachIndexed { index, property ->
                    if (index > 0) output.append(',')
                    string(output, property.name)
                    output.append(':')
                    write(output, runCatching { property.readMethod.invoke(value) }.getOrNull(), visited, nextDepth)
                }
            output.append('}')
        }
    }

    private fun cycleOrDepth(
        output: StringBuilder,
        value: Any,
        visited: IdentityHashMap<Any, Unit>,
        depth: Int,
        body: (Int) -> Unit,
    ) {
        if (depth >= MAX_DEPTH || visited.put(value, Unit) != null) {
            output.append(NULL)
            return
        }
        try {
            body(depth + 1)
        } finally {
            visited.remove(value)
        }
    }

    private fun arraySequence(value: Any): Sequence<Any?> = sequence {
        for (index in 0 until Array.getLength(value)) yield(Array.get(value, index))
    }

    private fun number(output: StringBuilder, value: Number) {
        val text = value.toString()
        if (text == "NaN" || text == "Infinity" || text == "-Infinity") output.append(NULL) else output.append(text)
    }

    private fun string(output: StringBuilder, value: String) {
        output.append('"')
        value.forEach { char ->
            when (char) {
                '"' -> output.append("\\\"")
                '\\' -> output.append("\\\\")
                '\b' -> output.append("\\b")
                '\u000C' -> output.append("\\f")
                '\n' -> output.append("\\n")
                '\r' -> output.append("\\r")
                '\t' -> output.append("\\t")
                else -> if (char < ' ') output.append("\\u%04x".format(char.code)) else output.append(char)
            }
        }
        output.append('"')
    }

    private const val NULL = "null"
    private const val MAX_DEPTH = 16
    private const val MAX_ITEMS = 256
}
