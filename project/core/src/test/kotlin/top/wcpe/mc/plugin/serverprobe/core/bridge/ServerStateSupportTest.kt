package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ServerStateSupport] 单元测试(FR-076 全量状态查询的平台无关支撑)。
 *
 * 仅覆盖不依赖任何 Bukkit/Bungee API 的逻辑:有界裁剪计数、类加载计数、加载器链摘要、JVM 快照。
 * 断言聚焦"在任意可运行 JVM 上恒成立"的不变量与有界/降级行为;Bukkit API 部分真机验。
 */
class ServerStateSupportTest {

    /** 不超上限时:items 原样返回、total 等于元素数、truncated 为 false。 */
    @Test
    fun `bounded 不超上限时不裁剪`() {
        val src = (1..5).toList()
        val result = ServerStateSupport.bounded(src, limit = 10)

        @Suppress("UNCHECKED_CAST")
        val items = result["items"] as List<Any?>
        assertEquals(5, items.size, "未超上限应全量返回")
        assertEquals(5, result["total"], "total 应为原始总数")
        assertEquals(false, result["truncated"], "未超上限不应标记截断")
    }

    /** 超上限时:items 截断到 limit、total 保留原始总数、truncated 为 true。 */
    @Test
    fun `bounded 超上限时裁剪并计数`() {
        val src = (1..50).toList()
        val result = ServerStateSupport.bounded(src, limit = 10)

        @Suppress("UNCHECKED_CAST")
        val items = result["items"] as List<Any?>
        assertEquals(10, items.size, "应截断到 limit")
        assertEquals(50, result["total"], "total 应保留原始总数,反映被裁剪的真实规模")
        assertEquals(true, result["truncated"], "超上限应标记截断")
    }

    /** limit <= 0 时回退到默认上限 MAX_LIST_ITEMS,不产生越界或负数行为。 */
    @Test
    fun `bounded 非法 limit 回退默认上限`() {
        val src = (1..(ServerStateSupport.MAX_LIST_ITEMS + 5)).toList()
        val result = ServerStateSupport.bounded(src, limit = 0)

        @Suppress("UNCHECKED_CAST")
        val items = result["items"] as List<Any?>
        assertEquals(ServerStateSupport.MAX_LIST_ITEMS, items.size, "limit<=0 应按默认上限裁剪")
        assertEquals(true, result["truncated"], "超默认上限应标记截断")
    }

    /** 空列表:items 空、total 0、不截断。 */
    @Test
    fun `bounded 空列表`() {
        val result = ServerStateSupport.bounded(emptyList())

        @Suppress("UNCHECKED_CAST")
        val items = result["items"] as List<Any?>
        assertTrue(items.isEmpty(), "空输入 items 应为空")
        assertEquals(0, result["total"], "空输入 total 应为 0")
        assertEquals(false, result["truncated"], "空输入不应标记截断")
    }

    /** classLoadingCounts 在任意运行中的 JVM 上恒有正的已加载类数与非负累计/卸载计数。 */
    @Test
    fun `classLoadingCounts 返回有效计数`() {
        val counts = ServerStateSupport.classLoadingCounts()

        val loaded = counts["loadedClassCount"] as Int
        val total = counts["totalLoadedClassCount"] as Long
        val unloaded = counts["unloadedClassCount"] as Long
        assertTrue(loaded > 0, "当前已加载类数应大于 0")
        assertTrue(total >= loaded.toLong(), "累计加载类数应不小于当前已加载数")
        assertTrue(unloaded >= 0, "已卸载类数应不小于 0")
    }

    /** classLoaderChain 自身在前、逐级 parent、以 bootstrap(null 加载器) 收尾。 */
    @Test
    fun `classLoaderChain 自顶向下到 bootstrap`() {
        val chain = ServerStateSupport.classLoaderChain(javaClass.classLoader)

        assertTrue(chain.isNotEmpty(), "链不应为空")
        assertEquals("bootstrap", chain.last(), "链应以 bootstrap 收尾(引导加载器 null)")
        assertTrue(chain.size <= ServerStateSupport.MAX_LOADER_CHAIN_DEPTH, "深度不应超过封顶")
    }

    /** 起始即 null(引导加载器) 时链仅含 bootstrap。 */
    @Test
    fun `classLoaderChain null 起始仅 bootstrap`() {
        val chain = ServerStateSupport.classLoaderChain(null)
        assertEquals(listOf("bootstrap"), chain, "null 起始应仅产出 bootstrap")
    }

    /** jvmSnapshot 在任意运行中的 JVM 上各核心字段满足基本不变量,且不含降级 error 键。 */
    @Test
    fun `jvmSnapshot 返回满足基本不变量的快照`() {
        val jvm = ServerStateSupport.jvmSnapshot()

        assertFalse(jvm.containsKey("error"), "正常 JVM 上不应降级")
        assertNotNull(jvm["jvmName"], "JVM 名不应为 null")
        assertTrue((jvm["heapUsedBytes"] as Long) > 0, "堆已用应大于 0")
        assertTrue((jvm["heapCommittedBytes"] as Long) > 0, "堆已提交应大于 0")
        assertTrue((jvm["threadCount"] as Int) > 0, "线程数应大于 0")
        assertTrue((jvm["availableProcessors"] as Int) > 0, "可用处理器数应大于 0")
        assertTrue((jvm["uptimeMs"] as Long) >= 0, "运行时长应不小于 0")
    }
}
