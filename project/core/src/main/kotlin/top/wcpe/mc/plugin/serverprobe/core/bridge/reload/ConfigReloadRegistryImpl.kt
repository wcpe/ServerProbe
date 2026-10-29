package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import top.wcpe.mc.plugin.serverprobe.api.Registration
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadListener
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadRegistry
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.PreDestroy
import top.wcpe.taboolib.ioc.annotation.Service
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 监听器来源：只读快照，供协调器在每次派发时取参与方。
 *
 * 与 [ConfigReloadRegistry] 分开是一个**副作用**：注册表是对外契约（`api` 模块），
 * 而"取全部监听器"是探针内部编排需要，不该固化进对外制品。二者由 [ConfigReloadRegistryImpl]
 * 一并实现，IoC 因而把同一个实例同时暴露为两个类型（注入按接口，不得注入实现类）。
 */
interface ReloadListenerSource {

    /**
     * 当前已注册监听器（按注册顺序）。
     *
     * @return 不可变快照，永不为 null。
     */
    fun snapshot(): List<ConfigReloadListener>

    /**
     * 取某个监听器**当前**的合法声明（每次派发时调用，规格 §3.11）。
     *
     * 实现方须丢弃非法条目（越界、含 `NUL`、空串）并 WARN——注册时合法、运行期改坏
     * 同样是可绕过的洞，必须在每次取值时再拦一次。
     *
     * @param listener 监听器。
     * @return 已规范化、已去重的路径列表（可能为空）。
     */
    fun watchedPathsOf(listener: ConfigReloadListener): List<String>
}

/**
 * 配置重载监听器注册表（配置重载规格 §3.2、§3.11）。
 *
 * ## 注册时立即校验声明
 * 非法条目（越界、含 `NUL`、空串）被丢弃并 WARN（带监听器类名与非法值）；
 * 全部非法 → 失效句柄 + `INVALID` / `"路径越界"`；声明为空 → 失效句柄 + `"未声明关注路径"`。
 * **不静默成功**——否则插件会以为自己在倾听，而永远收不到通知。
 *
 * ## 声明快照是"每次派发"取的
 * [ConfigReloadListener.watchedPaths] 在**每次派发时**被调用，因此插件可在运行期改变关注项。
 * 除了注册时校验，派发取快照时**再校验一次**：运行期改出来的越界声明同样必须被丢弃，
 * 否则"注册时合法、运行期改坏"就是一个可绕过的洞。
 *
 * ## 句柄语义
 * - `close()` 幂等，按**实例比对**删除（插件热重载后旧实例的卸载回调不会误删新实例）；
 * - 同实例重复注册以最新一次为准，旧句柄 `REPLACED`，且保持原注册顺序；
 * - 探针停用时全部置 `PROBE_UNAVAILABLE`。
 */
@Service
class ConfigReloadRegistryImpl : ConfigReloadRegistry, ReloadListenerSource {

    /** 已注册监听器（写少读多：注册在 onEnable、读取在每次派发）。 */
    private val entries = CopyOnWriteArrayList<ListenerEntry>()

    /**
     * WARN 出口；单测可替换以断言"非法声明确实告警"。
     *
     * 沿用 [top.wcpe.mc.plugin.serverprobe.core.bridge.ProviderHealthRegistry] 的注入范式
     * （`@Volatile internal var`），而不是往构造签名里加参数——注册表由 IoC 实例化，没有构造器接缝。
     */
    @Volatile
    internal var warn: (String) -> Unit = ProbeLogger::warn

    /**
     * 注册监听器。
     *
     * @param listener 监听器；null 返回失效句柄
     * @return 注册句柄，永不 null、永不抛
     */
    override fun register(listener: ConfigReloadListener?): Registration {
        if (listener == null) {
            return InactiveRegistration(Registration.InactiveReason.INVALID, "listener 为 null")
        }
        val declaration = declaredPathsOf(listener)
        if (declaration.valid.isEmpty()) {
            val detail = if (declaration.illegal > 0) DETAIL_ESCAPE else DETAIL_NO_PATH
            return InactiveRegistration(Registration.InactiveReason.INVALID, detail)
        }
        val entry = ListenerEntry(listener)
        replaceExisting(listener, entry)
        ProbeLogger.info("配置重载监听器已注册:${listener.javaClass.name},关注 ${declaration.valid.size} 条路径")
        return entry
    }

    /** 当前已注册监听器（按注册顺序）。 */
    override fun snapshot(): List<ConfigReloadListener> = entries.map { it.listener }

    /** 当前注册数（诊断用）。 */
    fun size(): Int = entries.size

    /**
     * 取该监听器**当前**的合法声明（每次派发时调用，规格 §3.11）。
     *
     * 非法条目丢弃 + WARN。
     *
     * @return 已规范化、已去重的路径列表（可能为空）。
     */
    override fun watchedPathsOf(listener: ConfigReloadListener): List<String> = declaredPathsOf(listener).valid

    /**
     * 探针停用：全部句柄置失效。
     *
     * 与 [top.wcpe.mc.plugin.serverprobe.core.bridge.BusinessHostImpl] 的停机语义一致——
     * 停用后不该再有监听器显示为"在册"，否则插件的 `getActive()` 会撒谎。
     */
    @PreDestroy
    fun shutdown() {
        entries.forEach { it.deactivate(Registration.InactiveReason.PROBE_UNAVAILABLE, "探针已停止") }
        entries.clear()
    }

    /** 同实例重复注册：旧句柄失效、保留原位置（顺序即派发顺序，不该因重注册而抖）。 */
    private fun replaceExisting(listener: ConfigReloadListener, fresh: ListenerEntry) {
        val index = entries.indexOfFirst { it.listener === listener }
        if (index < 0) {
            entries.add(fresh)
            return
        }
        entries[index].deactivate(Registration.InactiveReason.REPLACED, "同实例被重新注册")
        entries.set(index, fresh)
        ProbeLogger.info("配置重载监听器被重复注册,以最新一次为准:${listener.javaClass.name}")
    }

    /**
     * 规范化声明并丢弃非法条目。
     *
     * `watchedPaths()` 抛异常或返回 null 一律按"无声明"处理（契约违约不应让注册崩溃）。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun declaredPathsOf(listener: ConfigReloadListener): Declaration {
        val raw = try {
            listener.watchedPaths()
        } catch (_: Throwable) {
            warn("配置重载监听器的 watchedPaths() 抛出异常,按未声明处理:${listener.javaClass.name}")
            return Declaration(emptyList(), 0)
        }
        if (raw == null) {
            warn("配置重载监听器的 watchedPaths() 返回 null,按未声明处理:${listener.javaClass.name}")
            return Declaration(emptyList(), 0)
        }
        val valid = LinkedHashSet<String>()
        var illegal = 0
        for (candidate in raw) {
            val normalized = candidate?.let { ReloadPathGuard.normalize(it) }
            if (normalized == null) {
                illegal++
                warn(
                    "配置重载声明被丢弃(路径越界或非法):${listener.javaClass.name} -> " +
                        "'${(candidate ?: "null").take(MAX_LOGGED_PATH_CHARS)}'",
                )
            } else {
                valid.add(normalized)
            }
        }
        return Declaration(valid.toList(), illegal)
    }

    /** 一次声明校验的结果：合法路径 + 被丢弃条目数。 */
    private data class Declaration(val valid: List<String>, val illegal: Int)

    /** 一条已注册监听器，同时是返回给调用方的注册句柄。 */
    private inner class ListenerEntry(val listener: ConfigReloadListener) : Registration {

        @Volatile
        private var reason: Registration.InactiveReason = Registration.InactiveReason.NONE

        @Volatile
        private var detail: String = ""

        override fun getActive(): Boolean = reason == Registration.InactiveReason.NONE

        override fun getInactiveReason(): Registration.InactiveReason = reason

        override fun getInactiveDetail(): String = detail

        /** 标记失效（内部调用）。 */
        fun deactivate(reason: Registration.InactiveReason, detail: String) {
            this.reason = reason
            this.detail = detail
        }

        override fun close() {
            // 幂等：已失效则无事可做。
            if (!getActive()) return
            if (entries.remove(this)) {
                deactivate(Registration.InactiveReason.CLOSED, "调用方 close")
                ProbeLogger.info("配置重载监听器已注销:${listener.javaClass.name}")
            }
        }
    }

    /** 注册被拒时返回的失效句柄（不抛异常，让调用方能按统一方式判断）。 */
    private class InactiveRegistration(
        private val reason: Registration.InactiveReason,
        private val detail: String,
    ) : Registration {

        override fun getActive(): Boolean = false

        override fun getInactiveReason(): Registration.InactiveReason = reason

        override fun getInactiveDetail(): String = detail

        override fun close() {
            // 从未注册过任何东西，close 是无操作。
        }
    }

    private companion object {

        /** 声明全部非法时的 detail（契约文本，JM/单测按字面匹配）。 */
        const val DETAIL_ESCAPE = "路径越界"

        /** 声明为空时的 detail（契约文本）。 */
        const val DETAIL_NO_PATH = "未声明关注路径"

        /** WARN 里单条非法值的最大展示长度（防超长输入刷屏）。 */
        const val MAX_LOGGED_PATH_CHARS = 200
    }
}
