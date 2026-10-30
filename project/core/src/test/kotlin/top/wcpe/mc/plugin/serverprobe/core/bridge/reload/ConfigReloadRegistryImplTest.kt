package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.Registration
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadEvent
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadListener
import top.wcpe.mc.plugin.serverprobe.api.reload.ConfigReloadResult
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [ConfigReloadRegistryImpl] 单元测试（配置重载规格 §3.11 + §5「非法声明」一行）。
 *
 * 重点在"不静默成功"：全部非法 → `INVALID` / `"路径越界"`；声明为空 → `"未声明关注路径"`；
 * 两种情况都必须有 WARN——否则插件作者无从发现自己的声明写错了。
 */
class ConfigReloadRegistryImplTest {

    /** 合法声明注册成功，句柄有效。 */
    @Test
    fun `合法声明注册成功`() {
        val registry = newRegistry()
        val listener = RecordingListener("plugins/Foo/config.yml")

        val handle = registry.register(listener)

        assertTrue(handle.getActive())
        assertEquals(Registration.InactiveReason.NONE, handle.getInactiveReason())
        assertEquals(listOf<ConfigReloadListener>(listener), registry.snapshot())
        assertEquals(1, registry.size())
    }

    /** 验收「非法声明」：声明 `../x` → 失效句柄、原因 `INVALID`、日志有 WARN。 */
    @Test
    fun `声明越界时注册被拒且有WARN`() {
        val registry = newRegistry()
        val warnings = CopyOnWriteArrayList<String>()
        registry.warn = { warnings.add(it) }

        val handle = registry.register(RecordingListener("../x"))

        assertFalse(handle.getActive(), "全非法的声明不得静默成功")
        assertEquals(Registration.InactiveReason.INVALID, handle.getInactiveReason())
        assertEquals("路径越界", handle.getInactiveDetail())
        assertTrue(warnings.any { it.contains("../x") }, "WARN 应带上非法值，实际=$warnings")
        assertTrue(warnings.any { it.contains("RecordingListener") }, "WARN 应带上监听器类名，实际=$warnings")
        assertTrue(registry.snapshot().isEmpty(), "被拒的监听器不得进入注册表")
    }

    /** 声明空集合 → 失效句柄 + `"未声明关注路径"`（等价于不注册，但不静默）。 */
    @Test
    fun `声明为空时注册被拒`() {
        val registry = newRegistry()

        val handle = registry.register(RecordingListener())

        assertFalse(handle.getActive())
        assertEquals(Registration.InactiveReason.INVALID, handle.getInactiveReason())
        assertEquals("未声明关注路径", handle.getInactiveDetail())
    }

    /** `null` 监听器 → 失效句柄，不抛。 */
    @Test
    fun `null监听器返回失效句柄`() {
        val handle = newRegistry().register(null)

        assertFalse(handle.getActive())
        assertEquals(Registration.InactiveReason.INVALID, handle.getInactiveReason())
    }

    /** 部分非法：合法的那部分生效，非法条目被丢弃并 WARN。 */
    @Test
    fun `部分非法时保留合法条目`() {
        val registry = newRegistry()
        val warnings = CopyOnWriteArrayList<String>()
        registry.warn = { warnings.add(it) }
        val listener = RecordingListener("../../etc/passwd", "plugins/Foo/config.yml")

        val handle = registry.register(listener)

        assertTrue(handle.getActive(), "有合法条目就应注册成功")
        assertEquals(listOf("plugins/Foo/config.yml"), registry.watchedPathsOf(listener), "非法条目应被丢弃")
        assertTrue(warnings.isNotEmpty(), "丢弃非法条目必须告警")
    }

    /** 声明一次列表含 `NUL` 与空串：同样丢弃 + WARN。 */
    @Test
    fun `含NUL与空串的声明被丢弃`() {
        val registry = newRegistry()
        val warnings = CopyOnWriteArrayList<String>()
        registry.warn = { warnings.add(it) }
        val listener = RecordingListener("plugins/A/${0.toChar()}x", "", "plugins/Ok/config.yml")

        registry.register(listener)

        assertEquals(listOf("plugins/Ok/config.yml"), registry.watchedPathsOf(listener))
        // 注册时校验一次、`watchedPathsOf` 再校验一次（每次派发都要拦），故按**去重后的消息**计数。
        assertEquals(2, warnings.toSet().size, "两条非法声明各自告警一次，实际=$warnings")
    }

    /** `close()` 幂等；关闭后不再出现在快照里。 */
    @Test
    fun `关闭句柄幂等且移除监听器`() {
        val registry = newRegistry()
        val handle = registry.register(RecordingListener("plugins/Foo/config.yml"))

        handle.close()
        handle.close()

        assertFalse(handle.getActive())
        assertEquals(Registration.InactiveReason.CLOSED, handle.getInactiveReason())
        assertTrue(registry.snapshot().isEmpty())
    }

    /** 同实例重复注册：旧句柄 `REPLACED`，注册表里只留一份（避免重复派发）。 */
    @Test
    fun `同实例重复注册以最新为准`() {
        val registry = newRegistry()
        val listener = RecordingListener("plugins/Foo/config.yml")

        val first = registry.register(listener)
        val second = registry.register(listener)

        assertFalse(first.getActive())
        assertEquals(Registration.InactiveReason.REPLACED, first.getInactiveReason())
        assertTrue(second.getActive())
        assertEquals(1, registry.size(), "同实例不得占两份（否则每次派发都调两次）")
    }

    /** 旧句柄 close **不得**误删后来注册的同键实例（插件热重载的经典坑）。 */
    @Test
    fun `陈旧句柄close不影响新注册`() {
        val registry = newRegistry()
        val listener = RecordingListener("plugins/Foo/config.yml")
        val stale = registry.register(listener)
        val fresh = registry.register(listener)

        stale.close()

        assertTrue(fresh.getActive(), "陈旧句柄的 close 不应把新句柄打掉")
        assertEquals(1, registry.size())
    }

    /**
     * 声明在**每次读取时**重新校验：注册时合法、运行期改坏同样被丢弃。
     *
     * 这是规格 §3.11 的硬要求——否则"注册时合法、运行期改坏"就是可绕过的洞。
     */
    @Test
    fun `运行期改坏的声明在读取时被过滤`() {
        val registry = newRegistry()
        val listener = MutableListener("plugins/Foo/config.yml")
        registry.register(listener)

        listener.paths = listOf("plugins/../secret")

        assertEquals(emptyList<String>(), registry.watchedPathsOf(listener), "改坏后的声明必须被过滤")
    }

    /** `watchedPaths()` 抛异常 → 按未声明处理并 WARN，注册本身不崩。 */
    @Test
    fun `声明抛异常时按未声明处理`() {
        val registry = newRegistry()
        val warnings = CopyOnWriteArrayList<String>()
        registry.warn = { warnings.add(it) }
        val listener = object : ConfigReloadListener {
            // 刻意抛普通运行时异常：要验证的是"第三方声明取值抛异常"这条违约路径。
            @Suppress("UseCheckOrError")
            override fun watchedPaths(): Collection<String> = throw IllegalStateException("boom")
            override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult = ConfigReloadResult.ok()
        }

        val handle = registry.register(listener)

        assertFalse(handle.getActive())
        assertEquals("未声明关注路径", handle.getInactiveDetail())
        assertTrue(warnings.any { it.contains("抛出异常") }, "应告警说明监听器违约，实际=$warnings")
    }

    /** 探针停机：全部句柄置 `PROBE_UNAVAILABLE` 且注册表清空。 */
    @Test
    fun `停机后句柄失效`() {
        val registry = newRegistry()
        val handle = registry.register(RecordingListener("plugins/Foo/config.yml"))

        registry.shutdown()

        assertFalse(handle.getActive())
        assertEquals(Registration.InactiveReason.PROBE_UNAVAILABLE, handle.getInactiveReason())
        assertTrue(registry.snapshot().isEmpty())
    }

    /** 声明去重：同一路径写两遍只留一条。 */
    @Test
    fun `重复声明被去重`() {
        val registry = newRegistry()
        val listener = RecordingListener("plugins/Foo/config.yml", "plugins/Foo/config.yml")

        registry.register(listener)

        assertEquals(listOf("plugins/Foo/config.yml"), registry.watchedPathsOf(listener))
    }

    private fun newRegistry(): ConfigReloadRegistryImpl {
        val registry = ConfigReloadRegistryImpl()
        registry.warn = { }
        return registry
    }

    /** 固定声明的监听器。 */
    private class RecordingListener(vararg paths: String) : ConfigReloadListener {

        private val declared: List<String> = paths.toList()

        override fun watchedPaths(): Collection<String> = declared

        override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult = ConfigReloadResult.ok()
    }

    /** 声明可在运行期改变的监听器。 */
    private class MutableListener(vararg paths: String) : ConfigReloadListener {

        @Volatile
        var paths: List<String> = paths.toList()

        override fun watchedPaths(): Collection<String> = paths

        override fun onConfigReload(event: ConfigReloadEvent): ConfigReloadResult = ConfigReloadResult.ok()
    }
}
