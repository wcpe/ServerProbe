package top.wcpe.mc.plugin.serverprobe.core.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.api.model.FoldedStack
import top.wcpe.mc.plugin.serverprobe.api.model.StackHotspot
import top.wcpe.mc.plugin.serverprobe.api.model.StartupItemTiming
import top.wcpe.mc.plugin.serverprobe.api.model.ThreadStackProfile
import top.wcpe.mc.plugin.serverprobe.api.model.TimelineEvent
import top.wcpe.mc.plugin.serverprobe.api.model.WorldTiming

/**
 * [AgentDataReader] 单元测试。
 *
 * **挂载降级**:agent 类(`ProbeAgentBridge` / `ProbeAgent`)位于 `plugin` 模块,不在 core 测试类路径,故
 * [AgentDataReader.read] 的 `Class.forName` 必然 [ClassNotFoundException]——覆盖"未挂载静默降级"契约。
 *
 * **解析器**:M5 新增的折叠栈/时间线/命名项耗时解析为 `internal` 纯函数,本测试在同模块直接调用,覆盖
 * 正常多段、字段不全跳过、非数字跳过、空串、分组+降序、主线程热点派生等分支(回归防护)。
 */
class AgentDataReaderTest {

    /** agent 未挂载(类不在测试类路径)时,read 应降级为 notAttached 且不抛异常。 */
    @Test
    fun `read 未挂载时降级为 notAttached`() {
        val data = AgentDataReader.read(5)

        assertFalse(data.attached, "未挂载时 attached 应为 false")
        assertEquals(0L, data.premainNanos, "未挂载时 premainNanos 应为 0")
        assertEquals(0L, data.jvmStartTimeMs, "未挂载时 jvmStartTimeMs 应为 0")
        assertTrue(data.loadTimings.isEmpty(), "未挂载时 loadTimings 应为空")
        assertTrue(data.enableTimings.isEmpty(), "未挂载时 enableTimings 应为空")
        assertTrue(data.libraryTimings.isEmpty(), "未挂载时 libraryTimings 应为空")
        assertTrue(data.hotspots.isEmpty(), "未挂载时 hotspots 应为空")
        assertTrue(data.threadStacks.isEmpty(), "未挂载时 threadStacks 应为空")
    }

    /** topN 取非正值时同样应安全降级。 */
    @Test
    fun `read 非正 topN 不抛异常`() {
        val data = AgentDataReader.read(0)
        assertFalse(data.attached, "未挂载时无论 topN 取值都应降级为未挂载")
    }

    /** agent 类不存在时,stopStackSampler 应静默无副作用(不抛异常)。 */
    @Test
    fun `stopStackSampler 未挂载时静默`() {
        AgentDataReader.stopStackSampler()
    }

    /** 未挂载时 readHttpCallsSince 应返回空列表且游标不前进,不抛异常。 */
    @Test
    fun `readHttpCallsSince 未挂载返回空`() {
        val (calls, maxSeq) = AgentDataReader.readHttpCallsSince(5L)
        assertTrue(calls.isEmpty(), "未挂载时外呼记录应为空")
        assertEquals(5L, maxSeq, "未挂载时游标应原样返回")
    }

    /** 未挂载时 setHttpMonitorEnabled 应静默无副作用(不抛异常)。 */
    @Test
    fun `setHttpMonitorEnabled 未挂载时静默`() {
        AgentDataReader.setHttpMonitorEnabled(true)
        AgentDataReader.setHttpMonitorEnabled(false)
    }

    /** parseFoldedStacks 应按线程分组、组内按命中降序,帧序列按 ';' 切分。 */
    @Test
    fun `parseFoldedStacks 分组与降序`() {
        val raw = "Server thread|a.A#x;b.B#y|10\n" +
            "Server thread|a.A#x|3\n" +
            "Netty Server IO #1|c.C#z|7"
        val profiles = AgentDataReader.parseFoldedStacks(raw)

        assertEquals(2, profiles.size, "应分出 2 个线程")
        val server = profiles.first { it.threadName == "Server thread" }
        assertEquals(2, server.stacks.size, "Server thread 应有 2 条折叠栈")
        assertEquals(10L, server.stacks[0].sampleCount, "组内应按命中降序")
        assertEquals(listOf("a.A#x", "b.B#y"), server.stacks[0].frames, "帧序列应按 ';' 切分")
        val netty = profiles.first { it.threadName == "Netty Server IO #1" }
        assertEquals(7L, netty.stacks[0].sampleCount, "Netty 线程命中应正确")
    }

    /** parseFoldedStacks 对字段不全/命中非数字/空折叠栈的行应跳过,空串返回空列表。 */
    @Test
    fun `parseFoldedStacks 容错跳过非法行`() {
        assertTrue(AgentDataReader.parseFoldedStacks("").isEmpty(), "空串应返回空列表")
        val raw = "noPipes\n" +          // 无 '|',跳过
            "Thread|onlyOnePipe\n" +     // 仅一个 '|',跳过
            "Thread|f1;f2|notNumber\n" + // 命中非数字,跳过
            "Thread|valid#m|5"           // 合法
        val profiles = AgentDataReader.parseFoldedStacks(raw)
        assertEquals(1, profiles.size, "仅 1 个合法线程")
        assertEquals(1, profiles[0].stacks.size, "仅 1 条合法折叠栈")
        assertEquals(5L, profiles[0].stacks[0].sampleCount, "合法行命中应为 5")
    }

    /** deriveMainThreadHotspots 应选主线程、按帧累加命中并按降序取 Top-N。 */
    @Test
    fun `deriveMainThreadHotspots 主线程优先且累加降序`() {
        val stacks = listOf(
            ThreadStackProfile(
                "Server thread",
                listOf(
                    FoldedStack(listOf("root#m", "a#x"), 5L),
                    FoldedStack(listOf("root#m", "b#y"), 3L)
                )
            ),
            // 即便 Netty 命中更多,也应优先选 Server thread
            ThreadStackProfile("Netty Server IO #1", listOf(FoldedStack(listOf("n#z"), 100L)))
        )
        val hot = AgentDataReader.deriveMainThreadHotspots(stacks, 10)

        assertEquals(StackHotspot("root#m", 8L), hot[0], "root#m 应累加 5+3=8 居首")
        assertEquals(3, hot.size, "Server thread 应派生 3 个帧热点")

        val topOne = AgentDataReader.deriveMainThreadHotspots(stacks, 1)
        assertEquals(1, topOne.size, "topN=1 应截断到 1 条")
        assertEquals("root#m", topOne[0].frame, "截断后应保留最热帧")
    }

    /** 无 "Server thread" 时,deriveMainThreadHotspots 退化为采样最多的线程。 */
    @Test
    fun `deriveMainThreadHotspots 无主线程时取最忙线程`() {
        val stacks = listOf(ThreadStackProfile("Region #0", listOf(FoldedStack(listOf("x#m"), 4L))))
        val hot = AgentDataReader.deriveMainThreadHotspots(stacks, 5)
        assertEquals("x#m", hot[0].frame, "无 Server thread 时取最忙线程的帧")
    }

    /** parseTimelineEvents 正常解析,字段不足/非数字应跳过。 */
    @Test
    fun `parseTimelineEvents 解析与容错`() {
        val ev = AgentDataReader.parseTimelineEvents("enable|Alpha|1000|2000;load|Beta|5|6")
        assertEquals(2, ev.size, "应解析出 2 条")
        assertEquals(TimelineEvent("enable", "Alpha", 1000L, 2000L), ev[0], "首条应正确")

        val ev2 = AgentDataReader.parseTimelineEvents("bad|only|three;enable|A|x|2;load|B|1|2")
        assertEquals(1, ev2.size, "字段不足/非数字段应跳过,仅保留 1 条")
        assertEquals(TimelineEvent("load", "B", 1L, 2L), ev2[0], "应保留合法段")
        assertTrue(AgentDataReader.parseTimelineEvents("").isEmpty(), "空串应返回空列表")
    }

    /** parseWorldTimings / parseItemTimings 解析 name=ms 串,非数字值跳过。 */
    @Test
    fun `parseWorldTimings 与 parseItemTimings 解析`() {
        val worlds = AgentDataReader.parseWorldTimings("world=10;world_nether=20")
        assertEquals(listOf(WorldTiming("world", 10L), WorldTiming("world_nether", 20L)), worlds, "世界耗时应解析")

        val items = AgentDataReader.parseItemTimings("config.yml=5;broken=bad")
        assertEquals(1, items.size, "非数字值的项应跳过")
        assertEquals(StartupItemTiming("config.yml", 5L), items[0], "合法项应保留")
    }
}
