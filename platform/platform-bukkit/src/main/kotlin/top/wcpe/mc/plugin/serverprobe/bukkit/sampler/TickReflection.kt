package top.wcpe.mc.plugin.serverprobe.bukkit.sampler

import org.bukkit.Bukkit
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger

/**
 * tick 采样相关的反射访问门面。
 *
 * 设计取舍:本模块编译期依赖的 Bukkit API(`ink.ptms.core:v12004`)为纯 Spigot 口径,
 * **不包含** Paper 扩展方法(`getTPS`/`getAverageTickTime`/`getTickTimes`),也不应在编译期
 * 直接 import 某个具体版本的 `net.minecraft.server.MinecraftServer`(违反多版本兼容要求,
 * 见任务自检"不直接 import 高版本 NMS 具体类")。因此 Paper API 与 NMS `recentTps`
 * 字段统一通过**运行期反射**读取:目标方法/字段存在则正常取值,缺失则降级为 null,
 * 反射天然提供了优雅降级能力。
 *
 * 反射元数据(Method/Field)做进程级懒缓存,避免每次采样重复查找(规范第 17 条:循环内高开销操作)。
 * 缓存项区分"未解析(null 占位)"与"已解析但不可用([UNAVAILABLE] 哨兵)"两态,
 * 最终只缓存一次结果(首拍并发下各线程可能各自解析一次,但解析为幂等纯读取、结果一致,
 * 稳态下后续访问经 @Volatile 缓存短路,不再重复解析)。
 *
 * 全部方法为无副作用纯读取(规范第 10 条第 10 项:静态工具仅限无状态纯函数);
 * 缓存仅是结果记忆化,不构成可变业务状态。
 */
object TickReflection {

    /** 已解析但确认不可用的哨兵:与 null(未解析)区分,避免反复尝试。 */
    private val UNAVAILABLE = Any()

    /** `Server#getTPS(): double[]` 反射缓存。 */
    @Volatile
    private var tpsMethod: Any? = null

    /** `Server#getAverageTickTime(): double` 反射缓存。 */
    @Volatile
    private var avgTickMethod: Any? = null

    /** `Server#getTickTimes(): long[]` 反射缓存(Paper)。 */
    @Volatile
    private var tickTimesMethod: Any? = null

    /** `MinecraftServer#recentTps`(double[3])反射缓存。 */
    @Volatile
    private var recentTpsField: Any? = null

    /**
     * 反射调用 Paper 的 `Server#getTPS()`。
     *
     * @return TPS 数组([1m, 5m, 15m]);方法不存在或调用异常时返回 null。
     */
    fun paperTps(): DoubleArray? {
        val method = resolveServerMethod(::tpsMethod, M_GET_TPS) ?: return null
        return runCatching { method.invoke(Bukkit.getServer()) as? DoubleArray }
            .onFailure { ProbeLogger.debug("反射调用 getTPS 失败:${it.message}") }
            .getOrNull()
    }

    /**
     * 反射调用 Paper 的 `Server#getAverageTickTime()`。
     *
     * @return 平均 tick 耗时(毫秒);方法不存在或调用异常时返回 null。
     */
    fun paperAverageTickTimeMs(): Double? {
        val method = resolveServerMethod(::avgTickMethod, M_GET_AVG_TICK) ?: return null
        return runCatching { method.invoke(Bukkit.getServer()) as? Double }
            .onFailure { ProbeLogger.debug("反射调用 getAverageTickTime 失败:${it.message}") }
            .getOrNull()
    }

    /**
     * 反射调用 Paper 的 `Server#getTickTimes()`(最近若干 tick 的纳秒耗时数组)。
     *
     * @return 最近 tick 纳秒耗时数组;方法不存在(非 Paper)或调用异常时返回 null。
     */
    fun paperTickTimes(): LongArray? {
        val method = resolveServerMethod(::tickTimesMethod, M_GET_TICK_TIMES) ?: return null
        return runCatching { method.invoke(Bukkit.getServer()) as? LongArray }
            .onFailure { ProbeLogger.debug("反射调用 getTickTimes 失败:${it.message}") }
            .getOrNull()
    }

    /**
     * 反射读取 NMS `MinecraftServer#recentTps`(无 Paper API 的旧版/纯 CraftBukkit 兜底)。
     *
     * 取数链路:`Bukkit.getServer()`(CraftServer)→ `getServer()` 取 `MinecraftServer` 实例 →
     * 在其类层级中查找名为 `recentTps` 的 `double[]` 字段。全程不 import 任何具体版本 NMS 类。
     *
     * M2 完善:字段名在部分映射/混淆环境下可能不是 `recentTps`,届时可补充按类型(double[3])
     * 启发式扫描或 nmsProxy 适配;当前以标准字段名为准,失败即降级 null。
     *
     * @return recentTps 数组([1m, 5m, 15m]);不可用时返回 null。
     */
    fun nmsRecentTps(): DoubleArray? {
        val field = resolveRecentTpsField() ?: return null
        return runCatching {
            val nmsServer = field.first.invoke(Bukkit.getServer())
            field.second.get(nmsServer) as? DoubleArray
        }.onFailure { ProbeLogger.debug("反射读取 recentTps 失败:${it.message}") }.getOrNull()
    }

    /**
     * 探测 NMS `recentTps` 反射链路是否可用(供采样器工厂选路,不取实际值)。
     *
     * @return 链路可解析返回 true,否则 false。
     */
    fun isRecentTpsAvailable(): Boolean = resolveRecentTpsField() != null

    /**
     * 解析并缓存 `org.bukkit.Server` 上的无参方法。
     *
     * @param cache 对应方法的缓存属性引用。
     * @param methodName 目标方法名。
     * @return 可调用的 [java.lang.reflect.Method];不可用时返回 null。
     */
    private fun resolveServerMethod(
        cache: kotlin.reflect.KMutableProperty0<Any?>,
        methodName: String
    ): java.lang.reflect.Method? {
        when (val cached = cache.get()) {
            UNAVAILABLE -> return null
            is java.lang.reflect.Method -> return cached
        }
        val resolved = runCatching {
            Bukkit.getServer().javaClass.getMethod(methodName)
        }.getOrNull()
        cache.set(resolved ?: UNAVAILABLE)
        if (resolved == null) {
            ProbeLogger.debug("当前服务端不支持方法 $methodName,对应采样项降级为 N/A")
        }
        return resolved
    }

    /**
     * 解析并缓存 NMS `recentTps` 的反射访问对(CraftServer#getServer 方法 + recentTps 字段)。
     *
     * @return (getServer 方法, recentTps 字段) 二元组;链路不可用时返回 null。
     */
    @Suppress("UNCHECKED_CAST")
    private fun resolveRecentTpsField(): Pair<java.lang.reflect.Method, java.lang.reflect.Field>? {
        when (val cached = recentTpsField) {
            UNAVAILABLE -> return null
            is Pair<*, *> -> return cached as Pair<java.lang.reflect.Method, java.lang.reflect.Field>
        }
        val resolved = runCatching {
            val getServer = Bukkit.getServer().javaClass.getMethod(M_GET_SERVER)
            val nmsServer = getServer.invoke(Bukkit.getServer())
            val field = findFieldInHierarchy(nmsServer.javaClass, F_RECENT_TPS)
            field.isAccessible = true
            getServer to field
        }.getOrNull()
        recentTpsField = resolved ?: UNAVAILABLE
        if (resolved == null) {
            ProbeLogger.debug("当前服务端无法反射读取 recentTps")
        }
        return resolved
    }

    /**
     * 在类继承链中逐级查找声明字段。
     *
     * MinecraftServer 的 `recentTps` 可能声明于父类,需沿 superclass 逐级回溯。
     *
     * @param type 起始类型。
     * @param name 字段名。
     * @return 找到的字段。
     * @throws NoSuchFieldException 整条继承链均无该字段时抛出(由上层 runCatching 兜底)。
     */
    private fun findFieldInHierarchy(type: Class<*>, name: String): java.lang.reflect.Field {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name)
            } catch (ignored: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw NoSuchFieldException("继承链中未找到字段 $name")
    }

    /** `org.bukkit.Server#getTPS` 方法名。 */
    private const val M_GET_TPS = "getTPS"

    /** `org.bukkit.Server#getAverageTickTime` 方法名。 */
    private const val M_GET_AVG_TICK = "getAverageTickTime"

    /** `org.bukkit.Server#getTickTimes` 方法名。 */
    private const val M_GET_TICK_TIMES = "getTickTimes"

    /** CraftServer 取 NMS 服务端实例的方法名。 */
    private const val M_GET_SERVER = "getServer"

    /** NMS `MinecraftServer` 最近 TPS 字段名。 */
    private const val F_RECENT_TPS = "recentTps"
}
