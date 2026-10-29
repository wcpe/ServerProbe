package top.wcpe.mc.plugin.serverprobe.core.bridge.chunk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.bridge.OutboundFrame
import top.wcpe.mc.plugin.serverprobe.core.config.ChunkSettings
import java.nio.charset.StandardCharsets

/**
 * [FrameChunker] 单元测试（功能规格 `docs/specs/frame-chunking.md` §3.2 / §5）。
 *
 * 全部用例**注入小阈值**（`max-frame-bytes` 用 512~4096 而非默认 256 KiB）：
 * 用默认值断言会让"读默认值"的实现在阈值逻辑写错时照样变绿。
 */
class FrameChunkerTest {

    /** 构造受测分片器；阈值与单组上限都由用例显式给出。 */
    private fun chunker(
        maxFrameBytes: Int,
        maxChunksPerGroup: Int = 64,
        enabled: Boolean = true,
    ): FrameChunker = FrameChunker(
        ChunkSettings(enabled = enabled, maxFrameBytes = maxFrameBytes, maxChunksPerGroup = maxChunksPerGroup),
    )

    /** 把组取出来（断言一定分片成功）。 */
    private fun whole(result: ChunkResult): List<OutboundFrame> =
        (result as? ChunkResult.Whole)?.frames ?: error("期望分片成功,实际 ${(result as ChunkResult.TooLarge).reason}")

    /**
     * 阈值三档边界：整帧字节 `T-1`、`T` 原样单帧发出，`T+1` 才分片。
     *
     * 边界值取整帧序列化字节（`payload.toByteArray(UTF_8).size`），`T` 本身**不分片**
     * （规格写的是"超过阈值"，不是"达到阈值"）。
     */
    @Test
    fun `阈值三档边界`() {
        val threshold = 4096
        val chunker = chunker(threshold)

        val below = demoFrame("a".repeat(threshold - 1))
        val at = demoFrame("a".repeat(threshold))
        val above = demoFrame("a".repeat(threshold + 1))

        listOf(below, at).forEach { frame ->
            val frames = whole(chunker.split(frame))
            assertEquals(1, frames.size, "未超阈值应原样单帧发出(长度=${frame.payload.length})")
            assertTrue(frames[0] === frame, "未超阈值必须原样返回同一帧(不重写)")
            assertEquals(frame.payload, frames[0].payload, "代理写会让 payload 逐字节改变")
        }

        val chunked = whole(chunker.split(above))
        assertTrue(chunked.size >= 2, "超过阈值必须分片,实际片数=${chunked.size}")
        assertEquals(chunked.size, chunked.first().total, "total 应等于实际片数")
        assertWithinThreshold(chunked, threshold)
    }

    /**
     * 量的是**整帧**字节，不是载荷字节：信封写长（超长 `requestId`）使业务数据很小、
     * 整帧却超阈值 → 仍然分片。若实现按"业务载荷"量，这一帧会被当成"很小"而直接发出。
     */
    @Test
    fun `阈值量整帧字节而非业务载荷`() {
        val threshold = 1024
        val chunker = chunker(threshold)
        // 业务数据只有 8 字节，而 requestId 把整帧撑到远超阈值。
        val businessData = "\"data\":\"tiny\""
        val payload = "{\"type\":\"event\",\"event\":\"command_result\",\"requestId\":\"${"r".repeat(threshold)}" +
            "\",$businessData}"
        assertTrue(businessData.length < threshold, "前提:业务载荷远小于阈值")
        assertTrue(payload.toByteArray(StandardCharsets.UTF_8).size > threshold, "前提:整帧已超阈值")

        val frames = whole(chunker.split(demoFrame(payload, event = OutboundFrame.TYPE_COMMAND_RESULT)))

        assertTrue(frames.size >= 2, "整帧超阈值就必须分片(量的是整帧而非业务载荷),实际片数=${frames.size}")
        assertEquals(payload, reassemble(frames), "重组后应逐字节还原整帧")
        assertWithinThreshold(frames, threshold)
    }

    /**
     * 重组先解码：按 `seq` 拼 `data` → base64 解码 → UTF-8，字节与原 `payload` **完全相同**。
     *
     * 同时断言"跳过解码直接拼字符串"与原文不相等——否则说明断言根本没走 base64
     * （早期"直接切原始文本"的方案会让这两种拼法结果相同，等于没测到 base64）。
     */
    @Test
    fun `重组先解码后字节相同`() {
        val chunker = chunker(512)
        val payload = "{\"type\":\"event\",\"event\":\"demo_change\",\"data\":{\"rows\":[" +
            (0 until 60).joinToString(",") { "{\"id\":$it}" } + "]}}"
        val frames = whole(chunker.split(demoFrame(payload)))

        assertTrue(frames.size >= 2, "前提:该载荷必须被分片")
        val decoded = reassemble(frames)
        assertArrayEqualsBytes(
            payload.toByteArray(StandardCharsets.UTF_8),
            decoded.toByteArray(StandardCharsets.UTF_8),
        )
        assertEquals(payload, decoded, "解码后文本应与原 payload 相同")
        assertNotEquals(payload, concatenatedData(frames), "跳过 base64 解码直接拼接**不该**等于原文")
    }

    /**
     * 特殊字符不破：`"`、`\`、换行、emoji、中文都能原样往返。
     *
     * base64 让 `data` 不含任何需要 JSON 转义的字符，这正是选 base64 的原因
     * （若直接切原始文本，`"`/`\` 会被二次转义，拼接后字节就对不上）。
     */
    @Test
    fun `特殊字符不破`() {
        val chunker = chunker(512)
        val payload = "{\"quote\":\"\\\"\",\"backslash\":\"\\\\\",\"nl\":\"行1\\n行2\",\"emoji\":\"🚀🔥\"," +
            "\"中文\":\"探针\",\"pad\":\"" + "填".repeat(200) + "\"}"
        val frames = whole(chunker.split(demoFrame(payload)))

        assertTrue(frames.size >= 2, "前提:该载荷必须被分片")
        val decoded = reassemble(frames)
        assertArrayEqualsBytes(
            payload.toByteArray(StandardCharsets.UTF_8),
            decoded.toByteArray(StandardCharsets.UTF_8),
        )
        // data 只含 base64 字母表 → 没有任何 `"`、`\`、换行需要转义。
        frames.forEach { frame ->
            val data = dataOf(frame.payload)
            assertTrue(
                data.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' },
                "data 只应含 base64 字母表,实际含:${data.filterNot { c -> c.isLetterOrDigit() || c == '+' || c == '/' || c == '=' }}",
            )
        }
    }

    /**
     * `total` 后算：长度正好落在片边界时片数仍正确，且 `total` 写在**每一片**上。
     *
     * 用被测分片器先量出一片的满额数据长度，再构造"base64 正好填满 k 片"与"再多 4 个字符"两个载荷：
     * 任何"预估片数"的实现（例如按原始字节数估算）在这两个位置上都会差 1。
     */
    @Test
    fun `total 后算且每片一致`() {
        val threshold = 512
        val chunker = chunker(threshold)
        // 先探出一片的满额 base64 字符数（首片必为满额片）；探针帧自身必须已超阈值才会被分片。
        val probe = whole(chunker.split(demoFrame("a".repeat(threshold + 100))))
        val perChunk = dataOf(probe.first().payload).length
        assertTrue(perChunk > 0, "首片应有数据")

        // base64 每 4 字符对应 3 字节：按 4 的整数倍切分时"正好填满 k 片"的载荷长度是 3*k*perChunk/4。
        val exactTwo = 3 * perChunk * 2 / 4
        val exactlyFull = whole(chunker.split(demoFrame("a".repeat(exactTwo))))
        assertEquals(2, exactlyFull.size, "base64 正好填满 2 片时应正好 2 片")
        assertEquals(2, base64LengthOf(exactlyFull) / perChunk, "前提:正好整除")

        // 再多 3 个原始字节 → base64 多 4 字符 → 必须溢出到第 3 片。
        val overflow = whole(chunker.split(demoFrame("a".repeat(exactTwo + 3))))
        assertEquals(3, overflow.size, "越过片边界 1 个 base64 字符组应变成 3 片")

        listOf(exactlyFull, overflow).forEach { frames ->
            frames.forEach { frame ->
                assertEquals(frames.size, intFieldOf(frame.payload, "total"), "每一片(含首片)都要带正确 total")
            }
            assertEquals((0 until frames.size).toList(), frames.map { intFieldOf(it.payload, "seq") }, "seq 应从 0 连续")
        }
    }

    /**
     * 单组上限：片数超 `max-chunks-per-group` → `TooLarge`（硬错误，与可丢性无关）。
     *
     * 这里把上限压到 2，用 3 片的载荷触发；同时验证 `TooLarge` 带上人可读原因。
     */
    @Test
    fun `单组上限超限即拒绝`() {
        val chunker = chunker(512, maxChunksPerGroup = 2)
        val result = chunker.split(demoFrame("a".repeat(4096)))

        val tooLarge = result as? ChunkResult.TooLarge ?: error("超单组上限应返回 TooLarge,实际 $result")
        assertTrue(tooLarge.reason.isNotEmpty(), "TooLarge 应带人可读原因")
    }

    /** `enabled=false`：既不分片也不放行巨帧——超阈值一律 `TooLarge`，阈值内仍原样发出。 */
    @Test
    fun `enabled 关闭时超限即拒绝`() {
        val threshold = 512
        val chunker = chunker(threshold, enabled = false)

        val oversized = chunker.split(demoFrame("a".repeat(threshold + 1)))
        assertTrue(oversized is ChunkResult.TooLarge, "关闭分片后超阈值必须拒绝,实际 $oversized")

        val within = chunker.split(demoFrame("a".repeat(threshold)))
        assertEquals(1, whole(within).size, "阈值内的帧不受分片开关影响")
    }

    /**
     * 阈值可配：同一内容在 1024 与 4096 下片数按 §3.2 的公式变化。
     *
     * 断言"片数 == ceil(base64 总长 / 单片数据长度)"而不写死数字——写死数字会把
     * 公式写错的实现也一起放过（它只是碰巧算对了）。
     */
    @Test
    fun `阈值可配且片数符合公式`() {
        // 载荷必须同时超过两个阈值，否则大阈值那侧会原样单帧发出（那样测不到公式）。
        val payload = "b".repeat(6000)
        val small = whole(chunker(1024).split(demoFrame(payload)))
        val large = whole(chunker(4096).split(demoFrame(payload)))

        assertTrue(small.size > large.size, "阈值调小应切得更碎(1024→${small.size},4096→${large.size})")
        assertTrue(large.size >= 2, "前提:4096 下也应分片,实际片数=${large.size}")

        listOf(1024 to small, 4096 to large).forEach { (threshold, frames) ->
            val perChunk = dataOf(frames.first().payload).length.coerceAtLeast(1)
            val expected = (base64LengthOf(frames) + perChunk - 1) / perChunk
            assertEquals(expected, frames.size, "阈值 $threshold 下片数应等于 ceil(base64 长度/单片长度)")
            assertWithinThreshold(frames, threshold)
        }
    }

    /**
     * `groupId` 必须每次新生成**完整 UUID**（36 字符），组内一致、组间不同。
     *
     * 不接受"实例名 + 每连接递增计数"：重连后计数归零会与上一连接尚未清理的残留分组撞号，
     * 对端会把两组片拼到一起。
     */
    @Test
    fun `groupId 唯一且为36字符`() {
        val chunker = chunker(512)
        val first = whole(chunker.split(demoFrame("c".repeat(4096))))
        val second = whole(chunker.split(demoFrame("c".repeat(4096))))

        val firstId = stringFieldOf(first.first().payload, "groupId") ?: error("分片帧缺少 groupId")
        val secondId = stringFieldOf(second.first().payload, "groupId") ?: error("分片帧缺少 groupId")

        assertEquals(36, firstId.length, "groupId 应为完整 UUID 文本(36 字符),实际=$firstId")
        assertEquals(36, secondId.length, "groupId 应为完整 UUID 文本(36 字符),实际=$secondId")
        assertNotEquals(firstId, secondId, "两次切分必须用不同的 groupId")
        first.forEach { assertEquals(firstId, stringFieldOf(it.payload, "groupId"), "同一组内 groupId 必须一致") }
        second.forEach { assertEquals(secondId, stringFieldOf(it.payload, "groupId"), "同一组内 groupId 必须一致") }
    }

    /** 片继承原帧的 `ofType` / `requestId` / 优先级；原帧没有 `requestId` 时片里也不出现该字段。 */
    @Test
    fun `分片帧继承原帧语义字段`() {
        val chunker = chunker(512)
        val frames = whole(
            chunker.split(
                demoFrame(
                    "d".repeat(2048),
                    event = OutboundFrame.TYPE_COMMAND_RESULT,
                    priority = OutboundFrame.Priority.COMMAND_RESULT,
                    requestId = "req-7",
                ),
            ),
        )

        frames.forEach { frame ->
            assertEquals(OutboundFrame.TYPE_COMMAND_RESULT, frame.ofType, "ofType 应记住原始 event 子类型")
            assertEquals(OutboundFrame.TYPE_COMMAND_RESULT, stringFieldOf(frame.payload, "ofType"), "ofType 应写入帧文本")
            assertEquals("req-7", frame.requestId, "requestId 应透传(供对端在重组前关联)")
            assertEquals("req-7", stringFieldOf(frame.payload, "requestId"), "requestId 应写入帧文本")
            assertEquals(OutboundFrame.Priority.COMMAND_RESULT, frame.priority, "片应继承原帧优先级")
            assertEquals(FrameChunker.EVENT_CHUNK, frame.event, "event 子类型应为 chunk")
            assertEquals(OutboundFrame.TYPE_EVENT, frame.type, "分片帧是 event 帧")
        }

        val noRequestId = whole(chunker.split(demoFrame("e".repeat(2048))))
        noRequestId.forEach { frame ->
            assertNull(stringFieldOf(frame.payload, "requestId"), "原帧无 requestId 时片里不应出现该字段")
        }
    }

    /** 每片序列化字节都不得超阈值（这是"逐片校验"要守住的不变量）。 */
    private fun assertWithinThreshold(frames: List<OutboundFrame>, threshold: Int) {
        frames.forEach { frame ->
            val size = frame.payload.toByteArray(StandardCharsets.UTF_8).size
            assertTrue(size <= threshold, "每片都必须 ≤ 阈值 $threshold,实际 $size(seq=${frame.seq})")
        }
    }

    /** 逐字节比较，失败信息带长度差（比 assertEquals 的数组打印更可读）。 */
    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertTrue(
            expected.contentEquals(actual),
            "字节应完全相同(期望 ${expected.size} 字节,实际 ${actual.size} 字节)",
        )
    }
}
