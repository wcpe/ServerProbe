package top.wcpe.mc.plugin.serverprobe.bukkit.startup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [LatestLogPluginTimingParser] 单元测试。
 *
 * 解析器为纯函数(仅依赖 api 的 [PluginTiming]),不触碰 Bukkit/磁盘,可直接喂样本行验证。
 * 覆盖:标准 Spigot 格式、`Server thread` 变体格式、以 `Done` 结尾计末插件耗时、无 Done 时末插件记 0、
 * 跨午夜校正、空输入与全噪声行容错、插件名/时间戳缺失行被跳过、中途伪 `Done (` 行不误截。
 */
class LatestLogPluginTimingParserTest {

    /**
     * 标准 Spigot 格式 + Done 收尾:
     * A@00s → B@02s → C@05s,Done@09s。
     * 耗时:A=2s,B=3s,C(末)以 Done 为上界 =4s。
     */
    @Test
    fun `标准格式逐插件耗时含 Done 收尾`() {
        val lines = listOf(
            "[12:00:00 INFO]: [Alpha] Enabling Alpha v1.0",
            "[12:00:02 INFO]: [Beta] Enabling Beta v2.3.1",
            "[12:00:05 INFO]: [Gamma] Enabling Gamma v0.9",
            "[12:00:09 INFO]: Done (21.345s)! For help, type \"help\""
        )
        val result = LatestLogPluginTimingParser.parse(lines)
        assertEquals(
            listOf(
                "Alpha" to 2000L,
                "Beta" to 3000L,
                "Gamma" to 4000L
            ),
            result.map { it.name to it.enableMs },
            "应按顺序解析 A=2s/B=3s/Gamma=4s(末插件以 Done 为上界)"
        )
    }

    /**
     * `[HH:mm:ss] [Server thread/INFO]:` 变体格式同样可解析(只取第一段时间)。
     */
    @Test
    fun `Server thread 变体格式可解析`() {
        val lines = listOf(
            "[09:30:10] [Server thread/INFO]: Enabling WorldEdit v7.2.0",
            "[09:30:13] [Server thread/INFO]: Enabling WorldGuard v7.0.0",
            "[09:30:18] [Server thread/INFO]: Done (12.000s)! For help, type \"help\" or \"?\""
        )
        val result = LatestLogPluginTimingParser.parse(lines)
        assertEquals(
            listOf("WorldEdit" to 3000L, "WorldGuard" to 5000L),
            result.map { it.name to it.enableMs },
            "变体格式应解析 WorldEdit=3s/WorldGuard=5s(末插件以 Done 为上界)"
        )
    }

    /**
     * 无 Done 行:最后一个插件无耗时上界,记为 0;其余仍按相邻差计算。
     */
    @Test
    fun `无 Done 行末插件记零`() {
        val lines = listOf(
            "[00:00:01 INFO]: Enabling First v1",
            "[00:00:04 INFO]: Enabling Second v1"
        )
        val result = LatestLogPluginTimingParser.parse(lines)
        assertEquals(
            listOf("First" to 3000L, "Second" to 0L),
            result.map { it.name to it.enableMs },
            "First=3s;末插件 Second 无上界应记 0"
        )
    }

    /**
     * 噪声行(非 Enabling)夹杂其中应被跳过,不影响相邻 Enabling 的配对计时。
     */
    @Test
    fun `夹杂噪声行被跳过`() {
        val lines = listOf(
            "[10:00:00 INFO]: Starting minecraft server version 1.21.4",
            "[10:00:00 INFO]: Loading properties",
            "[10:00:02 INFO]: Enabling PluginA v1",
            "[10:00:03 INFO]: [PluginA] some random message",
            "[10:00:07 INFO]: Enabling PluginB v1",
            "[10:00:10 INFO]: Done (10.0s)! For help, type \"help\""
        )
        val result = LatestLogPluginTimingParser.parse(lines)
        assertEquals(
            listOf("PluginA" to 5000L, "PluginB" to 3000L),
            result.map { it.name to it.enableMs },
            "噪声行应忽略;A=5s(到 B),B=3s(到 Done)"
        )
    }

    /**
     * 中途伪 `Done (` 行(如插件/数据包打印的 `Done (loading ...)`)不应被误判为启动完成而提前截断:
     * PluginA 与 PluginB 之间夹一条伪 Done 行,解析仍应越过它直到真正的 `Done (x.xs)!` 收尾。
     */
    @Test
    fun `中途伪 Done 行不截断解析`() {
        val lines = listOf(
            "[10:00:00 INFO]: Enabling PluginA v1",
            "[10:00:02 INFO]: [SomeDataPack] Done (something irrelevant)",
            "[10:00:04 INFO]: Enabling PluginB v1",
            "[10:00:09 INFO]: Done (9.0s)! For help, type \"help\""
        )
        val result = LatestLogPluginTimingParser.parse(lines)
        assertEquals(
            listOf("PluginA" to 4000L, "PluginB" to 5000L),
            result.map { it.name to it.enableMs },
            "伪 Done 行不应截断;A=4s(到 B),B=5s(到真正的 Done)"
        )
    }

    /**
     * 跨午夜:23:59:58 → 00:00:02 应校正为 4s 而非负值。
     */
    @Test
    fun `跨午夜耗时按加一天校正`() {
        val lines = listOf(
            "[23:59:58 INFO]: Enabling NightOwl v1",
            "[00:00:02 INFO]: Enabling EarlyBird v1",
            "[00:00:05 INFO]: Done (1.0s)! For help, type \"help\""
        )
        val result = LatestLogPluginTimingParser.parse(lines)
        assertEquals(
            listOf("NightOwl" to 4000L, "EarlyBird" to 3000L),
            result.map { it.name to it.enableMs },
            "跨午夜 NightOwl 应为 4s(非负)"
        )
    }

    /** 空输入:返回空列表。 */
    @Test
    fun `空输入返回空`() {
        assertTrue(LatestLogPluginTimingParser.parse(emptyList()).isEmpty(), "空输入应返回空列表")
    }

    /** 全为无关/异常格式行:无任何 Enabling,返回空列表(容错)。 */
    @Test
    fun `全噪声行返回空`() {
        val lines = listOf(
            "this is not a log line",
            "[bad timestamp] Enabling X v1",
            "[12:00:00 INFO]: just info, no enabling here",
            ""
        )
        val result = LatestLogPluginTimingParser.parse(lines)
        assertTrue(result.isEmpty(), "无可解析的 Enabling 行应返回空列表")
    }

    /**
     * 缺时间戳的 Enabling 行被跳过,但同批中合法行仍正常解析。
     */
    @Test
    fun `缺时间戳的 Enabling 行被跳过`() {
        val lines = listOf(
            "Enabling NoTimePlugin v1",
            "[08:00:00 INFO]: Enabling GoodPlugin v1",
            "[08:00:06 INFO]: Done (6.0s)! For help"
        )
        val result = LatestLogPluginTimingParser.parse(lines)
        assertEquals(
            listOf("GoodPlugin" to 6000L),
            result.map { it.name to it.enableMs },
            "缺时间戳行应跳过,仅 GoodPlugin 入榜且以 Done 为上界 =6s"
        )
    }
}
