package top.wcpe.mc.plugin.serverprobe.core.agent

import top.wcpe.mc.plugin.serverprobe.api.model.HttpCall

/**
 * 外呼归因器(M5):把一次外呼归因到触发它的插件。
 *
 * 两级策略,优先级从高到低:
 * 1. **ClassLoader 身份匹配**(最稳):外呼调用栈各类的 ClassLoader 身份哈希若命中某插件的 ClassLoader,即归因该插件。
 *    这能覆盖"插件自身代码不在栈上"的常见情形——如插件经连接池/驱动后台线程发起连接时,栈上只有被它打包的
 *    库类(mysql/h2/apache 等),而这些库类的 ClassLoader 正是该插件的 PluginClassLoader。
 * 2. **包名前缀匹配**(兜底):自栈顶向下找首个类名命中某插件主类包前缀的帧。
 *
 * 纯函数,无平台依赖,落位于 core(便于单测)。
 */
object HttpCallAttributor {

    /**
     * 归因一次外呼。
     *
     * @param call 外呼记录(用其 [HttpCall.loaderHashes] 与 [HttpCall.callerFrames])。
     * @param pluginByLoaderHash 插件 ClassLoader 身份哈希 → 插件名(由平台层据各插件 ClassLoader 构建)。
     * @param pluginPrefixes 插件名→主类包前缀列表(**应按前缀长度降序**),用于兜底匹配。
     * @return 命中的插件名;无法归因时为空串。
     */
    fun attribute(
        call: HttpCall,
        pluginByLoaderHash: Map<Int, String>,
        pluginPrefixes: List<Pair<String, String>>
    ): String {
        // 1) ClassLoader 身份(栈顶在前,取首个命中的插件 = 最直接的调用方/库拥有者)
        for (hash in call.loaderHashes) {
            pluginByLoaderHash[hash]?.let { return it }
        }
        // 2) 包前缀兜底
        return attributeByFrames(call.callerFrames, pluginPrefixes)
    }

    /**
     * 仅按包名前缀归因(兜底路径,亦供单测)。
     *
     * @param frames 调用栈帧(`类全名#方法名`,栈顶在前)。
     * @param pluginPrefixes 插件名→包名前缀列表(应按前缀长度降序)。
     * @return 命中的插件名;无命中为空串。
     */
    fun attributeByFrames(frames: List<String>, pluginPrefixes: List<Pair<String, String>>): String {
        if (pluginPrefixes.isEmpty()) {
            return ""
        }
        for (frame in frames) {
            val cls = frame.substringBefore('#')
            for ((name, prefix) in pluginPrefixes) {
                if (prefix.isNotEmpty() && cls.startsWith(prefix)) {
                    return name
                }
            }
        }
        return ""
    }
}
