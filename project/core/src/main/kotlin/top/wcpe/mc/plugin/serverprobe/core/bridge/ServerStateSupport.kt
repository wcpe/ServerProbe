package top.wcpe.mc.plugin.serverprobe.core.bridge

import java.lang.management.ManagementFactory

/**
 * 全量服务器状态查询(FR-076,JianManager ADR-016)的**平台无关**支撑工具。
 *
 * 把不依赖任何 Bukkit/Bungee API 的部分收口到 core(便于单测):
 * - **JVM 快照**:经 `java.lang.management.*` MXBean 取堆/非堆/线程/运行时长/处理器数等。
 * - **类加载快照**:经 [java.lang.management.ClassLoadingMXBean] 取已加载/累计加载/已卸载类计数(FR-076 classloader 专区核心)。
 * - **类加载器层级摘要**:对给定 ClassLoader 走 parent 链生成简短可读链(用于"插件类加载器层级"展示),
 *   类枚举**不全量 dump**,仅描述加载器身份与 parent 链。
 * - **有界裁剪**:对大集合(插件清单/世界列表/类名采样)统一裁剪 + 计数,避免单次产生大对象/大帧(非侵入)。
 *
 * 这些工具产出的都是可直接交 [top.wcpe.mc.plugin.serverprobe.core.json.Json.encode] 序列化的
 * `Map`/`List`/基元,平台层(BukkitServerStateCollector)拼装时复用。全部**只读、绝不抛**:
 * 反射越界/MXBean 异常一律降级为 N/A,沿用"探针只读优先、绝不成为事故源"。
 */
object ServerStateSupport {

    /** 单次状态帧中,任一大集合(插件/世界/类名采样/加载器链)的元素上限,超出截断并计数。 */
    const val MAX_LIST_ITEMS = 200

    /** 类加载器 parent 链最大深度(防御异常自引用/超深链),超出截断。 */
    const val MAX_LOADER_CHAIN_DEPTH = 16

    /**
     * 把列表裁剪到 [limit] 个元素并附「截断信息」,产出 `{ items: [...], total: N, truncated: bool }`。
     *
     * 用于把可能很大的集合(插件清单/世界/类名采样)安全放进状态帧:前端按需展开,后端不发超大帧(非侵入)。
     *
     * @param items 原始元素(已是可序列化的 Map/基元)。
     * @param limit 元素上限,默认 [MAX_LIST_ITEMS];<=0 时按默认处理。
     * @return 含 items(裁剪后)/total(原始总数)/truncated(是否被裁剪)的有界结构。
     */
    fun bounded(items: List<Any?>, limit: Int = MAX_LIST_ITEMS): Map<String, Any?> {
        val cap = if (limit <= 0) MAX_LIST_ITEMS else limit
        val total = items.size
        val capped = if (total > cap) items.subList(0, cap).toList() else items
        return linkedMapOf(
            "items" to capped,
            "total" to total,
            "truncated" to (total > cap),
        )
    }

    /**
     * 当前 JVM 的类加载快照(FR-076 classloader 专区核心计数)。
     *
     * 全部经 [java.lang.management.ClassLoadingMXBean]——**不枚举类、不 dump**,只取轻量计数:
     * 当前已加载类数 / 累计已加载类数 / 已卸载类数。异常降级为 -1。
     *
     * @return `{ loadedClassCount, totalLoadedClassCount, unloadedClassCount }`。
     */
    fun classLoadingCounts(): Map<String, Any?> = runCatching {
        val bean = ManagementFactory.getClassLoadingMXBean()
        linkedMapOf<String, Any?>(
            "loadedClassCount" to bean.loadedClassCount,
            "totalLoadedClassCount" to bean.totalLoadedClassCount,
            "unloadedClassCount" to bean.unloadedClassCount,
        )
    }.getOrElse {
        linkedMapOf<String, Any?>(
            "loadedClassCount" to -1,
            "totalLoadedClassCount" to -1L,
            "unloadedClassCount" to -1L,
        )
    }

    /**
     * 描述一个 ClassLoader 的 parent 链为简短可读字符串列表(自身在前,逐级 parent,到引导加载器止)。
     *
     * 仅取每个加载器的类名(`javaClass.name`)作身份标识——**不触碰其加载的类集合**(避免大数据 + 反射越界),
     * 满足 FR-076「插件类加载器层级」的层级摘要诉求。引导加载器(null)表示为 "bootstrap"。
     * 深度封顶 [MAX_LOADER_CHAIN_DEPTH] 防御异常自引用/超深链;任何异常降级为已收集到的部分。
     *
     * @param loader 起始类加载器(可为 null,表示引导加载器)。
     * @return 自顶向下的加载器身份链(如 `["PluginClassLoader", "...AppClassLoader", "PlatformClassLoader", "bootstrap"]`)。
     */
    fun classLoaderChain(loader: ClassLoader?): List<String> {
        val chain = ArrayList<String>(MAX_LOADER_CHAIN_DEPTH)
        var current: ClassLoader? = loader
        var depth = 0
        runCatching {
            while (depth < MAX_LOADER_CHAIN_DEPTH) {
                if (current == null) {
                    chain.add("bootstrap")
                    break
                }
                chain.add(current.javaClass.name)
                current = current.parent
                depth++
            }
        }
        return chain
    }

    /**
     * 当前 JVM 的运行时/内存/线程快照(平台无关部分)。经各 MXBean 取数,异常子项降级。
     *
     * 与 FR2.1 的 [top.wcpe.mc.plugin.serverprobe.core.collector.JvmMetricsCollector] 数据源一致,
     * 但本方法面向「按需全状态查询」一次性产出可序列化 Map(含 JVM 名/vendor/版本、可用处理器数等
     * 全状态视角更关心的字段),与持续指标采集解耦、互不影响。
     *
     * @return JVM 快照 Map(堆/非堆/线程/运行时长/处理器数/JVM 名版本 vendor)。
     */
    fun jvmSnapshot(): Map<String, Any?> = runCatching {
        val mem = ManagementFactory.getMemoryMXBean()
        val heap = mem.heapMemoryUsage
        val nonHeap = mem.nonHeapMemoryUsage
        val threads = ManagementFactory.getThreadMXBean()
        val runtime = ManagementFactory.getRuntimeMXBean()
        linkedMapOf<String, Any?>(
            "jvmName" to runtime.vmName,
            "jvmVendor" to runtime.vmVendor,
            "jvmVersion" to runtime.vmVersion,
            "availableProcessors" to Runtime.getRuntime().availableProcessors(),
            "uptimeMs" to runtime.uptime,
            "startTimeMs" to runtime.startTime,
            "heapUsedBytes" to heap.used,
            "heapCommittedBytes" to heap.committed,
            "heapMaxBytes" to heap.max,
            "nonHeapUsedBytes" to nonHeap.used,
            "threadCount" to threads.threadCount,
            "daemonThreadCount" to threads.daemonThreadCount,
            "peakThreadCount" to threads.peakThreadCount,
        )
    }.getOrElse { linkedMapOf("error" to "JVM 快照采集失败") }
}
