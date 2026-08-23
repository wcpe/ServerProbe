package top.wcpe.mc.plugin.serverprobe.core.cpu

import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * 插件 ClassLoader 注册表(FR2.6)。
 *
 * 平台层(platform-bukkit)在初始化时把各插件的 ClassLoader 注册进来,
 * 供 [CpuAttributionSampler] 把线程栈帧归并到具体插件:对某栈帧的类名,尝试用
 * 已注册的插件 ClassLoader `loadClass`,命中即归属该插件。
 *
 * 为控制开销(每帧遍历所有 CL 太重),维护 **类名 → 插件名 的解析缓存**:
 * 首次 miss 才遍历 CL,命中/确认失败后写缓存,后续帧直接查表。缓存按类名收敛,
 * 插件热加载(新类出现)时 miss 会自然重新解析,无需显式失效。
 *
 * 作为 IOC [Service],平台无关,落位于 core。
 */
@Service
class PluginClassLoaderRegistry {

    /** 插件名 → ClassLoader(读多写少,插件装载期注册)。 */
    private val loaders = ConcurrentHashMap<String, ClassLoader>()

    /** 类名 → 插件名 解析缓存;null 占位表示"已确认无插件归属"(避免反复遍历 CL)。 */
    private val classOwner = ConcurrentHashMap<String, String>()

    /**
     * 注册一个插件 ClassLoader。
     *
     * @param name 插件名。
     * @param loader 该插件的 ClassLoader。
     */
    fun register(name: String, loader: ClassLoader) {
        loaders[name] = loader
    }

    /**
     * 注销一个插件(插件禁用/卸载时调用),同时清空其相关的类归属缓存。
     *
     * @param name 插件名。
     */
    fun unregister(name: String) {
        loaders.remove(name)
        // 该插件曾解析过的类不再归属它;清空后下次采样会重新解析(简单优先,不做定向清理)
        classOwner.clear()
    }

    /**
     * 解析某类名归属的插件名。
     *
     * 先查缓存;未命中则遍历已注册 ClassLoader 尝试 `loadClass(className, false)`
     * (不初始化类,避免触发静态块),命中写缓存并返回,全部 miss 记"无归属"缓存返回 null。
     *
     * @param className 全限定类名。
     * @return 归属插件名;无任何插件能加载该类时为 null。
     */
    fun ownerOf(className: String): String? {
        classOwner[className]?.let { return it.takeIf(String::isNotEmpty) }
        for ((name, loader) in loaders) {
            if (tryLoad(loader, className)) {
                classOwner[className] = name
                return name
            }
        }
        // 记"无归属"占位(空串),避免对相同类名反复遍历 CL
        classOwner[className] = ""
        return null
    }

    /**
     * 尝试用给定 ClassLoader 加载类(不初始化)。
     *
     * @param loader ClassLoader。
     * @param className 全限定类名。
     * @return 是否可加载。
     */
    private fun tryLoad(loader: ClassLoader, className: String): Boolean = runCatching {
        loader.loadClass(className)
        true
    }.getOrDefault(false)

    /**
     * 已注册插件名列表(快照副本)。
     *
     * @return 当前注册的插件名集合。
     */
    fun pluginNames(): Set<String> = loaders.keys.toSet()
}
