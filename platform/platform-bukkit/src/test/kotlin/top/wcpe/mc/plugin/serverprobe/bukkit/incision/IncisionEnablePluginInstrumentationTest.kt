package top.wcpe.mc.plugin.serverprobe.bukkit.incision

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import taboolib.module.incision.api.MethodCoordinate
import taboolib.module.incision.api.Resume
import taboolib.module.incision.api.Theatre
import top.wcpe.mc.plugin.serverprobe.core.incision.IncisionStartupDataStore

/**
 * [IncisionEnablePluginInstrumentation] 的行为测试。
 *
 * <h2>为什么不靠反射</h2>
 * <p>以前这里用 `getDeclaredMethod(...).getAnnotation(Lead::class.java)` + `assertNotNull(getAnnotation(Surgeon))`
 * 断言“注解写了什么”。那些只能证明<b>声明在</b>，而一条把 `startupDataStore.recordPluginEnable(...)`
 * 删掉的 advice 照样能通过。
 *
 * <h2>现在测什么</h2>
 * <p>直接按织入后的调用方式驱动 advice：
 * 先 `beforeEnablePlugin`（目标方法入口）、再 `afterEnablePlugin`（出口），
 * 然后看 [IncisionStartupDataStore] 里真的沉淀了什么：
 * <ul>
 *   <li>成对调用 → 记录一个带插件名与耗时的样本（这是启动画像里逐插件 onEnable 的唯一数据源）；</li>
 *   <li>异常出口 → 也要落地并清理线程局部作用域（`onThrow` 的真实后果）；</li>
 *   <li>嵌套启用 → 后进的先出，不得把名字串到别的插件上；</li>
 *   <li>未配对出口 → 安全返回，不抛异常、不产生样本；</li>
 *   <li>配置关闭 → 一条都不记（默认关闭是本能力的契约）。</li>
 * </ul>
 *
 * <p><b>“切点真的命中 `SimplePluginManager#enablePlugin`”</b>这一点无法用行为验：
 * 它需要 Incision 对真实 Bukkit 类做字节码织入。但那条契约的**声明**（切点字符串）
 * 是编译后必然落进 class 常量池的，因此另有一条 class 字节码断言直接读
 * 本模块编译产物验证——没有反射，也不读源码文本。真正织入了没有，由
 * `mcp-diagnostics-paper` / `mcp-diagnostics-folia` 真机场景与开启后的启动画像共同守住。</p>
 */
class IncisionEnablePluginInstrumentationTest {

    private lateinit var store: IncisionStartupDataStore

    @BeforeEach
    fun installEnabledConfig() {
        store = IncisionStartupDataStore()
        // object 单例上的 IOC 字段；测试直接放入实例，观察它收到的真实写入。
        IncisionEnablePluginInstrumentation.startupDataStore = store
        // 开关接缝：生产默认值读 `incision.enabled`，这里显式打开，
        // 才能验证“开启后真的落地”而不是“配置里的那个 key 拼对了”。
        IncisionEnablePluginInstrumentation.isIncisionEnabled = { true }
        // 线程局部栈是进程级状态（同一个线程跨用例复用），先清干净。
        drainScopes()
    }

    @AfterEach
    fun restoreDefaults() {
        // 单例状态不要泄漏给其他测试类：收尾时把开关与作用域都退回关闭形态。
        drainScopes()
        IncisionEnablePluginInstrumentation.isIncisionEnabled = { false }
    }

    // ────────────────── 成对 advice：真落地 ──────────────────

    @Test
    @DisplayName("前后置成对调用后，逐插件 onEnable 耗时被记录")
    fun `成对调用记录耗时`() {
        enablePlugin("某插件")

        val data = store.snapshot()
        assertTrue(data.enabled, "配置已请求启用时，enabled 必须为 true")
        assertTrue(data.active, "切点实际执行后，active 必须为 true")
        assertEquals(1, data.pluginEnableTimings.size, "一对 before/after 必须恰好记录一条，实际=${data.pluginEnableTimings}")
        assertEquals("某插件", data.pluginEnableTimings.single().name, "记录的必须是目标方法参数里的那个插件")
        assertTrue(data.pluginEnableTimings.single().enableMs >= 0, "耗时不得为负")
    }

    @Test
    @DisplayName("异常出口同样落地并清理作用域（onThrow 的真实后果）")
    fun `异常出口清理作用域`() {
        // 目标方法抛异常：Trail 仍会被调用，throwable 非空。
        IncisionEnablePluginInstrumentation.beforeEnablePlugin(theatre(FakePlugin("抛异常插件"), null))
        IncisionEnablePluginInstrumentation.afterEnablePlugin(
            theatre(FakePlugin("抛异常插件"), IllegalStateException("目标方法抛错")),
        )

        assertEquals(
            1,
            store.snapshot().pluginEnableTimings.size,
            "异常出口也必须记录，否则启动画像会漏掉因异常而慢的那次启用",
        )

        // 作用域已被清掉：再单独调一次 after 不得凭空多一条样本。
        IncisionEnablePluginInstrumentation.afterEnablePlugin(theatre(FakePlugin("无主出口"), null))
        assertEquals(
            1,
            store.snapshot().pluginEnableTimings.size,
            "异常出口必须把线程局部作用域一起清掉，否则后续出口会重复计数",
        )
    }

    @Test
    @DisplayName("嵌套启用按后进先出配对，名字不串台")
    fun `嵌套启用后进先出`() {
        // 插件 A 的 onEnable 里又启了插件 B：两个 before，再两个 after。
        IncisionEnablePluginInstrumentation.beforeEnablePlugin(theatre(FakePlugin("A"), null))
        IncisionEnablePluginInstrumentation.beforeEnablePlugin(theatre(FakePlugin("B"), null))
        IncisionEnablePluginInstrumentation.afterEnablePlugin(theatre(FakePlugin("B"), null))
        IncisionEnablePluginInstrumentation.afterEnablePlugin(theatre(FakePlugin("A"), null))

        assertEquals(
            listOf("B", "A"),
            store.snapshot().pluginEnableTimings.map { it.name },
            "内层先退出，必须先记 B；名字取自已配对的那个作用域，不得取出口参数",
        )
    }

    @Test
    @DisplayName("无配对前置的出口安全返回（不抛异常、不产生样本）")
    fun `孤立出口安全返回`() {
        val result = runCatching {
            IncisionEnablePluginInstrumentation.afterEnablePlugin(theatre(FakePlugin("孤立"), null))
        }

        assertTrue(result.isSuccess, "孤立出口不得影响被织入的方法，实际=${result.exceptionOrNull()}")
        assertEquals(emptyList<Any>(), store.snapshot().pluginEnableTimings, "没有 before 就不该产生样本")
    }

    @Test
    @DisplayName("开关关闭时一条都不记（默认关闭是契约）")
    fun `开关关闭时不采集`() {
        IncisionEnablePluginInstrumentation.isIncisionEnabled = { false }

        enablePlugin("不应被记录")

        val data = store.snapshot()
        assertEquals(emptyList<Any>(), data.pluginEnableTimings, "未开启时不得采集，否则拿不到数据的主机也会被开启开销")
        assertEquals(false, data.enabled, "未开启时 enabled 不得被标为 true")
    }

    @Test
    @DisplayName("早期 markRequested 在 IOC 未就绪时安全降级")
    fun `未注入存储时安全降级`() {
        // 织入回调可能早于 IOC 字段注入；那时不得抛异常，否则会拖死插件启用流程。
        val result = runCatching { IncisionEnablePluginInstrumentation.markRequested() }

        assertTrue(result.isSuccess, "markRequested 不得抛异常，实际=${result.exceptionOrNull()}")
    }

    @Test
    @DisplayName("编译产物里带着 enablePlugin 切点字符串（织入的目标不能被默默改掉）")
    fun `class字节码带着目标切点`() {
        val location = IncisionEnablePluginInstrumentation::class.java.protectionDomain.codeSource.location
        val classesRoot = java.io.File(location.toURI())
        val classFile = classesRoot.resolve(CLASS_RELATIVE_PATH)

        assertTrue(classFile.isFile, "必须能定位到本类的编译产物，实际=${classFile.absolutePath}")

        val bytes = classFile.readBytes()
        assertTrue(
            contains(bytes, ENABLE_PLUGIN_TARGET.toByteArray(Charsets.UTF_8)),
            "切点字符串必须编进本类常量池，否则注解的 scope 被改掉而无人发现：" +
                "期望包含 $ENABLE_PLUGIN_TARGET",
        )
    }

    // ────────────────── 夹具 ──────────────────

    /** 在字节数组里找一个 ASCII 子串。 */
    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }

    /** 跑一对完整的 before/after，模拟一次 `enablePlugin` 被织入后的完整调用。 */
    private fun enablePlugin(pluginName: String) {
        val plugin = FakePlugin(pluginName)
        IncisionEnablePluginInstrumentation.beforeEnablePlugin(theatre(plugin, null))
        IncisionEnablePluginInstrumentation.afterEnablePlugin(theatre(plugin, null))
    }

    /** 连续调用 after 把可能残留的作用域吐干净（用于用例间隔离）。 */
    private fun drainScopes() {
        repeat(MAX_DRAIN) {
            IncisionEnablePluginInstrumentation.afterEnablePlugin(theatre(FakePlugin("drain"), null))
        }
    }

    private fun theatre(plugin: Any?, failure: Throwable?): Theatre = FakeTheatre(plugin, failure)

    /** 目标方法参数里的插件替身：只需要一个公开的 `getName`（生产代码按名取插件名）。 */
    private class FakePlugin(private val name: String) {
        fun getName(): String = name
    }

    /** 最小 [Theatre] 替身：只实现 advice 真正会读的那几项。 */
    private class FakeTheatre(private val plugin: Any?, private val failure: Throwable?) : Theatre {

        override val target: MethodCoordinate get() = COORDINATE

        override val self: Any get() = this

        override val args: Array<Any?> get() = arrayOf(plugin)

        override val resume: Resume get() = NOOP_RESUME

        override val throwable: Throwable? get() = failure
    }

    private companion object {
        /** 足够排空任何残留作用域；本测试最多嵌套两层。 */
        const val MAX_DRAIN = 8

        /** Incision 织入的目标方法（切点字符串，会被编进 class 常量池）。 */
        const val ENABLE_PLUGIN_TARGET =
            "method:org.bukkit.plugin.SimplePluginManager#enablePlugin(org.bukkit.plugin.Plugin)V"

        /** 本类在模块编译输出目录里的相对路径。 */
        const val CLASS_RELATIVE_PATH =
            "top/wcpe/mc/plugin/serverprobe/bukkit/incision/IncisionEnablePluginInstrumentation.class"

        /** advice 不读它，仅为了构造一个完整坐标。 */
        val COORDINATE = MethodCoordinate(
            "org.bukkit.plugin.SimplePluginManager",
            "enablePlugin",
            "(Lorg/bukkit/plugin/Plugin;)V",
        )

        /** advice 只读 args/throwable，resume 用不到。 */
        val NOOP_RESUME = object : Resume {
            override fun proceed(): Any? = null
            override fun proceed(vararg args: Any?): Any? = null
            override fun proceedResult(result: Any?): Any? = result
            override fun skip(result: Any?): Any? = result
            override val proceeded: Boolean get() = false
        }
    }
}
