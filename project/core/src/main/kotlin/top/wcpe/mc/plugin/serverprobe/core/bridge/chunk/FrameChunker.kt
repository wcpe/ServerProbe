package top.wcpe.mc.plugin.serverprobe.core.bridge.chunk

import top.wcpe.mc.plugin.serverprobe.core.bridge.OutboundFrame
import top.wcpe.mc.plugin.serverprobe.core.config.ChunkSettings
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * 大数据帧分片的切分器（功能规格 `docs/specs/frame-chunking.md` §3.2）。
 *
 * ## 阈值按**整帧序列化字节**计
 * [OutboundFrame.payload] 本身就是完整帧文本（含 `type`/`event`/`instance`/`timestamp` 等信封字段），
 * 写出时不再包一层，因此"整帧 JSON 的 UTF-8 字节数"就是 `payload.toByteArray(UTF_8).size`，
 * [ChunkSettings.maxFrameBytes] 对**这个值**生效，而不是对"业务载荷"生效。
 *
 * ## 为什么 `data` 必须是 base64 切片
 * payload 本身是 JSON 文本，若直接切原始文本再拼进 `data`，里面的 `"` 与 `\` 会被**二次转义**，
 * 于是"按 seq 拼接后字节完全相同"落空；改 base64 后 `data` 只含 `A–Z a–z 0–9 + / =`，
 * 没有任何需要 JSON 转义的字符，切片与拼接都是纯文本操作，信封开销可精确计算。
 * 代价是 4/3 膨胀，可接受——分片本就只为"超阈值才触发"。
 *
 * ## 每片长度取 4 的整数倍
 * base64 每 4 个字符对应 3 字节，故按 4 的整数倍切分时**每片可独立解码**，
 * 对端既能"整组拼接后一次解码"，也能"逐片解码后拼接字节"，两种方式结果必然一致。
 *
 * ## 本类不抛异常
 * 任何切不动的情况都降级为 [ChunkResult.TooLarge] 并带上人可读原因；
 * 异常绝不冒到发送线程或命令池（桥的韧性要求，见规格 §3.3 与硬约束）。
 *
 * ## 线程安全
 * 无可变状态；`split` 可在任意线程调用。`envelope` 由调用方保证线程安全。
 */
class FrameChunker(
    private val settings: ChunkSettings,
    /**
     * 分片信封的公共字段（`instance` / `timestamp` 等）。
     *
     * 这些字段**不在** [OutboundFrame] 的权威字段集里（规格明确禁止为其加字段），
     * 只有装配处知道实例标识，故以供应器注入；默认不附加任何字段。
     * 该供应的结果同时参与信封开销测量，故"开销"与"实际片"始终一致。
     */
    private val envelope: () -> Map<String, Any?> = { emptyMap() },
) {

    /**
     * 若整帧序列化字节超过阈值则分片；否则返回单元素结果（原帧，逐字节不改写）。
     *
     * 前置条件：帧已过滤镜链（被滤镜丢弃的帧根本不会到这里）。
     *
     * @param frame 待发送的完整帧。
     * @return 整组（1 或 N 片）或明确的失败原因。
     */
    fun split(frame: OutboundFrame): ChunkResult {
        // ① 快判：UTF-8 编码后每字符最多 3 字节（BMP；代理对是 2 char→4 byte，仍不超过 3 倍），
        //    故 `字符数 × 3 ≤ 上限` 时必然不用分片 —— 直接放行，**不做任何字节枚举**。
        //    这一步是必须的：本类挂在每一帧的上行路径上，而 99.99% 的帧远小于阈值，
        //    若一律先 `toByteArray` 再判，等于让所有事件都为"万一要分片"付一次完整编码。
        val payloadLength = frame.payload.length
        if (payloadLength * UTF8_MAX_BYTES_PER_CHAR <= settings.maxFrameBytes) {
            return ChunkResult.Whole(java.util.Collections.singletonList(frame))
        }
        // ② 精确量整帧字节：阈值对"整帧序列化长度"生效，不是对载荷生效。
        //    只有长度可能逼近阈值时才需要这一步（JSON 多为 ASCII，实际字节数≈字符数）。
        val bytes = frame.payload.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= settings.maxFrameBytes) return ChunkResult.Whole(java.util.Collections.singletonList(frame))
        // enabled=false 时既不分片也不放行巨帧：宁可明确失败，也不发一个会长时间占写锁、
        // 并可能撞对端单帧上限的巨帧（规格 §3.2 末条）。
        if (!settings.enabled) {
            return ChunkResult.TooLarge(
                "分片已关闭(bridge.chunk.enabled=false),整帧 ${bytes.size} 字节超过上限 ${settings.maxFrameBytes} 字节",
            )
        }
        // ② 整帧字节转 base64 文本，后续切片全部在这段文本上进行。
        val encoded = Base64.getEncoder().encodeToString(bytes)
        // 信封公共字段一次取定，供探针片与全部实片共用（保证开销测量与实际片一致）。
        val extras = runCatching { envelope() }.getOrDefault(emptyMap())
        val chunkDataLen = dataLengthPerChunk(frame, extras)
        if (chunkDataLen <= 0) {
            return ChunkResult.TooLarge(
                "信封开销已用尽单帧上限 ${settings.maxFrameBytes} 字节,无可用数据预算(requestId 或信封字段过长)",
            )
        }
        return tryBuild(frame, encoded, chunkDataLen, extras)
    }

    /**
     * ③④ 量信封开销并算单片数据预算。
     *
     * 用 `data=""` 的探针片序列化一次得到开销：`data` 只含 base64 字母，对转义零贡献，
     * 故该值对每一片都成立。再留 [SEQ_TOTAL_DIGIT_ALLOWANCE] 字节给 `seq`/`total` 的位数变化，
     * 最后取 4 的整数倍，使每片都能独立解码。
     *
     * @return 每片可用 base64 字符数；`<= 0` 表示信封已把预算吃光，调用方须转 [ChunkResult.TooLarge]。
     */
    private fun dataLengthPerChunk(frame: OutboundFrame, extras: Map<String, Any?>): Int {
        val probe = buildChunk(frame, ChunkIndex(PROBE_GROUP_ID, seq = 0, total = 1), data = "", extras = extras)
        val overhead = probe.payload.toByteArray(StandardCharsets.UTF_8).size
        val dataBudget = settings.maxFrameBytes - overhead - SEQ_TOTAL_DIGIT_ALLOWANCE
        if (dataBudget <= 0) return 0
        return dataBudget - (dataBudget % BASE64_UNIT)
    }

    /**
     * ⑤⑥ 定片数、逐片构造，并在某片仍超限时缩小预算重试。
     *
     * `total` 必须在**切片完成后**才算得出并写入每一片（含首片），不能预估。
     * 逐片校验是必要的：信封里可能有超长 `requestId` 等，使实际片比探针片更宽。
     *
     * @param initialDataLen 首次尝试的单片 base64 字符数。
     * @return [ChunkResult.Whole] 或 [ChunkResult.TooLarge]。
     */
    private fun tryBuild(
        frame: OutboundFrame,
        encoded: String,
        initialDataLen: Int,
        extras: Map<String, Any?>,
    ): ChunkResult {
        var chunkDataLen = initialDataLen
        // 首次 + 最多 MAX_SHRINK_RETRIES 次"减 4 重算"。
        repeat(MAX_SHRINK_RETRIES + 1) {
            if (chunkDataLen <= 0) return tooLargeNoBudget()
            val total = chunkCount(encoded.length, chunkDataLen)
            if (total > settings.maxChunksPerGroup) {
                return ChunkResult.TooLarge(
                    "片数 $total 超过单组上限 ${settings.maxChunksPerGroup}(整帧已 base64 膨胀至 ${encoded.length} 字符)",
                )
            }
            val frames = buildChunks(frame, encoded, chunkDataLen, total, extras)
            if (frames.none { it.payload.toByteArray(StandardCharsets.UTF_8).size > settings.maxFrameBytes }) {
                return ChunkResult.Whole(frames)
            }
            // 某片仍超限（信封里有超长 requestId 等）：缩小数据预算重算，片数会随之增加。
            chunkDataLen -= BASE64_UNIT
        }
        return ChunkResult.TooLarge(
            "缩小数据预算重试 $MAX_SHRINK_RETRIES 次后仍有片超过单帧上限 ${settings.maxFrameBytes} 字节",
        )
    }

    /** 预算被吃光时的统一失败原因。 */
    private fun tooLargeNoBudget(): ChunkResult.TooLarge = ChunkResult.TooLarge(
        "信封开销已用尽单帧上限 ${settings.maxFrameBytes} 字节,无可用数据预算(requestId 或信封字段过长)",
    )

    /** 片数：`ceil(encodedLen / chunkDataLen)`，至少 1。 */
    private fun chunkCount(encodedLen: Int, chunkDataLen: Int): Int {
        if (encodedLen <= 0 || chunkDataLen <= 0) return 1
        return (encodedLen + chunkDataLen - 1) / chunkDataLen
    }

    /**
     * 构造全部片：`seq` 从 0 连续递增，`total` 写入每一片，`groupId` **每组新生成一个完整 UUID**。
     *
     * 用完整 UUID（36 字符）而非"实例名 + 递增计数"：重连后计数归零会与上一连接尚未清理的
     * 残留分组撞号，对端会把两组片拼到一起（规格 §3.1）。
     */
    private fun buildChunks(
        frame: OutboundFrame,
        encoded: String,
        chunkDataLen: Int,
        total: Int,
        extras: Map<String, Any?>,
    ): List<OutboundFrame> {
        val groupId = UUID.randomUUID().toString()
        return (0 until total).map { seq ->
            val from = seq * chunkDataLen
            val to = minOf(from + chunkDataLen, encoded.length)
            buildChunk(frame, ChunkIndex(groupId, seq, total), encoded.substring(from, to), extras)
        }
    }

    /** 构造单片的 [OutboundFrame]（信封字段全部从原帧继承，`ofType` 记住原始子类型）。 */
    private fun buildChunk(
        frame: OutboundFrame,
        index: ChunkIndex,
        data: String,
        extras: Map<String, Any?>,
    ): OutboundFrame = OutboundFrame(
        type = OutboundFrame.TYPE_EVENT,
        event = EVENT_CHUNK,
        domain = frame.domain,
        payload = chunkJson(frame, index, data, extras),
        // 片继承原帧优先级：命令回执的片仍是 COMMAND_RESULT，可靠事件仍是 RELIABLE_EVENT。
        priority = frame.priority,
        topic = frame.topic,
        // requestId 透传：便于对端在重组前就能关联，也让超限失败回执能找回 requestId。
        requestId = frame.requestId,
        groupId = index.groupId,
        seq = index.seq,
        total = index.total,
        ofType = frame.event.ifEmpty { frame.type },
    )

    /**
     * 手写一片的 JSON 文本。
     *
     * 不依赖运行期 JSON 库：`core` 的单测类路径没有 nightconfig（见 `BridgeConfigSource` 的说明），
     * 分片是纯文本拼装，用不上反射式序列化；手写还能保证字段顺序稳定、开销可精确复现。
     */
    private fun chunkJson(
        frame: OutboundFrame,
        index: ChunkIndex,
        data: String,
        extras: Map<String, Any?>,
    ): String {
        return buildString(data.length + ENVELOPE_BASE_CHARS) {
            append('{')
            appendStringField("type", OutboundFrame.TYPE_EVENT)
            appendStringField("event", EVENT_CHUNK)
            extras.forEach { (key, value) -> appendRawField(key, value) }
            appendStringField("groupId", index.groupId)
            appendRawField("seq", index.seq)
            appendRawField("total", index.total)
            appendStringField("ofType", frame.event.ifEmpty { frame.type })
            if (frame.requestId.isNotEmpty()) appendStringField("requestId", frame.requestId)
            appendStringField("data", data)
            append('}')
        }
    }

    // 以下四个助手只做 JSON 文本拼装，故就近放在本类；共同保证"值一定被正确转义"。

    /** 追加一个字符串字段（自动转义值）。 */
    private fun StringBuilder.appendStringField(key: String, value: String) {
        appendFieldSeparator()
        appendJsonString(key)
        append(':')
        appendJsonString(value)
    }

    /** 追加一个原样字段（数字/布尔不加引号；字符串仍转义；null 整字段省略）。 */
    private fun StringBuilder.appendRawField(key: String, value: Any?) {
        if (value == null) return
        appendFieldSeparator()
        appendJsonString(key)
        append(':')
        if (value is Number || value is Boolean) append(value.toString()) else appendJsonString(value.toString())
    }

    /** 除首字段外都补逗号。 */
    private fun StringBuilder.appendFieldSeparator() {
        if (isNotEmpty() && last() != '{') append(',')
    }

    /**
     * 追加一个已转义的 JSON 字符串字面量。
     *
     * 需要转义的只有 Envelope 里的少数短字段（`requestId` / `instance` 等）与 `data`；
     * `data` 只含 base64 字母，实际不会被转义——这正是选 base64 的原因。
     */
    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (ch in value) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch == '\b' -> append("\\b")
                ch == '\u000C' -> append("\\f")
                ch < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }

    companion object {

        /** 分片帧的 `event` 子类型值。 */
        const val EVENT_CHUNK = "chunk"

        /** base64 的每组字符数：取其整数倍切分可让每片独立解码。 */
        private const val BASE64_UNIT = 4

        /**
         * UTF-8 每字符最大字节数（用于快判上界）。
         *
         * BMP 内每字符最多 3 字节；代理对是 2 char → 4 byte，折合每 char 2 字节，仍低于 3。
         * 因此 `字符数 × 3 ≤ 阈值` 时，UTF-8 字节数必然也 ≤ 阈值，可安全跳过精确编码。
         */
        private const val UTF8_MAX_BYTES_PER_CHAR = 3

        /** 留给 `seq` / `total` 位数变化的字节数（规格 §3.2 ④）。 */
        private const val SEQ_TOTAL_DIGIT_ALLOWANCE = 8

        /** 某片超限时"缩小预算重算"的最大重试次数（规格 §3.2 ⑥）。 */
        private const val MAX_SHRINK_RETRIES = 4

        /** 探针片用的占位 `groupId`：与真实 UUID 等长（36 字符），使开销测量精确。 */
        private const val PROBE_GROUP_ID = "00000000-0000-0000-0000-000000000000"

        /** 信封（除 data 外全部字段）的粗略字符预留，仅用于减少 StringBuilder 扩容。 */
        private const val ENVELOPE_BASE_CHARS = 256
    }
}

/**
 * 一片在组内的定位信息（`groupId` / `seq` / `total`）。
 *
 * 单独成类型只是为了把三个"总是一起传递"的字段收口：片构造与 JSON 拼装都需要它们，
 * 逐个当参数会让签名变长且容易传错顺序。
 *
 * @property groupId 组标识（完整 UUID 文本）。
 * @property seq 片序号，从 0 开始。
 * @property total 整组片数（切片完成后才算得出）。
 */
private data class ChunkIndex(val groupId: String, val seq: Int, val total: Int)

/**
 * 分片结果：要么整组（1 或 N 片），要么明确的失败原因。
 *
 * 用密封类而非"返回空列表"表达失败：发送器需要区分"被滤镜丢弃""容量不足""无法分片"，
 * 因为三者的对外语义与计数完全不同（尤其 [TooLarge] 要触发命令侧回写失败回执）。
 */
sealed class ChunkResult {

    /**
     * 切分成功。
     *
     * @property frames `total == 1` 时为原帧本身（逐字节不改写）；否则为按 `seq` 升序的全部片。
     */
    class Whole(val frames: List<OutboundFrame>) : ChunkResult()

    /**
     * 无法分片传输。
     *
     * @property reason 人可读原因（进日志，便于定位是"单组上限"还是"信封过大"）。
     */
    class TooLarge(val reason: String) : ChunkResult()
}
