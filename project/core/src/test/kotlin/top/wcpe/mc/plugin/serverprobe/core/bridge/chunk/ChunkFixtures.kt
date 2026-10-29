package top.wcpe.mc.plugin.serverprobe.core.bridge.chunk

import top.wcpe.mc.plugin.serverprobe.core.bridge.OutboundFrame
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 分片相关单测的共享辅助（三个测试文件共用，避免各写一份口径不一致的"重组器"）。
 *
 * 这些助手**只服务于测试**：分片帧的 JSON 由 [FrameChunker] 手写拼装（不依赖运行期 JSON 库），
 * 故这里也用最小的文本提取，而不引入 `Json.parse`（core 的单测类路径没有 nightconfig）。
 *
 * 提取器假定被读字段的**值不含需要转义的字符**：`data` 只含 base64 字母（本功能的立身之本），
 * 测试里的 `requestId` / `ofType` 也都是纯 ASCII。若将来要在测试里塞入含引号的 requestId，
 * 需要把这里换成真正的 JSON 解析。
 */

/** 构造一个业务事件帧（默认可靠事件、`demo` 域）。 */
internal fun demoFrame(
    payload: String,
    event: String = "demo_change",
    priority: OutboundFrame.Priority = OutboundFrame.Priority.RELIABLE_EVENT,
    requestId: String = "",
): OutboundFrame = OutboundFrame(
    type = OutboundFrame.TYPE_EVENT,
    event = event,
    domain = "demo",
    payload = payload,
    priority = priority,
    requestId = requestId,
)

/** 取某片的 `data` 字段值（base64 文本，不含需转义字符）。 */
internal fun dataOf(payload: String): String = stringFieldOf(payload, "data") ?: ""

/** 取某片的整数字段（`seq` / `total`）。 */
internal fun intFieldOf(payload: String, name: String): Int =
    stringFieldOf(payload, name)?.toIntOrNull() ?: error("帧文本缺少整数字段 $name:$payload")

/** 取某片的字符串字段；字段不存在返回 null。 */
internal fun stringFieldOf(payload: String, name: String): String? {
    val marker = "\"$name\":"
    val at = payload.indexOf(marker)
    if (at < 0) return null
    val valueAt = at + marker.length
    if (payload.getOrNull(valueAt) == '"') {
        val end = payload.indexOf('"', valueAt + 1)
        return if (end < 0) null else payload.substring(valueAt + 1, end)
    }
    val end = payload.indexOfAny(charArrayOf(',', '}'), valueAt)
    return if (end < 0) null else payload.substring(valueAt, end)
}

/** 按 `seq` 升序拼接 `data` 后整体 base64 解码，得到原帧文本。 */
internal fun reassemble(frames: List<OutboundFrame>): String {
    val joined = frames.sortedBy { intFieldOf(it.payload, "seq") }.joinToString("") { dataOf(it.payload) }
    return String(Base64.getDecoder().decode(joined), StandardCharsets.UTF_8)
}

/** 按 `seq` 升序拼接 `data` 得到的 base64 文本（**未解码**，用于证明"跳过解码拼出来就不对"）。 */
internal fun concatenatedData(frames: List<OutboundFrame>): String =
    frames.sortedBy { intFieldOf(it.payload, "seq") }.joinToString("") { dataOf(it.payload) }

/** 整组的总 base64 文本长度（各片 `data` 长度之和）。 */
internal fun base64LengthOf(frames: List<OutboundFrame>): Int = frames.sumOf { dataOf(it.payload).length }

/**
 * 用 [payloadBytes] 个 `a` 作载荷时会被切成的片数（未分片算 1 片）。
 *
 * 片数随载荷长度单调不减，故可用于二分搜索"正好 N 片"的载荷长度。
 * 超单组上限时返回 [OVER_LIMIT]：那是"片数已超过上限"，对二分搜索等价于"≥ 目标"。
 */
internal fun totalChunksOf(chunker: FrameChunker, payloadBytes: Int): Int =
    when (val result = chunker.split(demoFrame("a".repeat(payloadBytes)))) {
        is ChunkResult.Whole -> result.frames.first().total
        is ChunkResult.TooLarge -> OVER_LIMIT
    }

/** "片数已超单组上限"的哨兵值：只需保证它大于任何合理的搜索目标。 */
private const val OVER_LIMIT = Int.MAX_VALUE

/**
 * 构造片数**正好**为 [target] 的业务帧。
 *
 * 不在测试里重算 §3.2 的公式（公式写错时测试会跟着一起错），而是用被测的分片器
 * 二分搜索能产生该片数的载荷长度。
 */
internal fun frameWithChunkCount(chunker: FrameChunker, target: Int): OutboundFrame {
    var low = 1
    var high = 64
    // 指数扩张，找到片数已达 target 的上界。
    while (totalChunksOf(chunker, high) < target) {
        low = high
        high *= 2
        if (high > MAX_PROBE_BYTES) error("未找到片数正好为 $target 的载荷")
    }
    // 二分收敛到"片数首次达到 target"的载荷长度。
    while (low + 1 < high) {
        val mid = (low + high) / 2
        if (totalChunksOf(chunker, mid) < target) low = mid else high = mid
    }
    val actual = totalChunksOf(chunker, high)
    if (actual != target) error("已越过片数 $target（载荷 $high 字节时实际 $actual 片），该配置无法产生该片数")
    return demoFrame("a".repeat(high))
}

/** 构造片数正好为 [target] 的整组（[frameWithChunkCount] 的切分结果）。 */
internal fun framesWithChunkCount(chunker: FrameChunker, target: Int): List<OutboundFrame> {
    val frame = frameWithChunkCount(chunker, target)
    val result = chunker.split(frame)
    return (result as? ChunkResult.Whole)?.frames ?: error("构造的帧应能分片:${(result as ChunkResult.TooLarge).reason}")
}

/** 二分搜索的载荷上限，防止无限搜索。 */
private const val MAX_PROBE_BYTES = 1 shl 22
