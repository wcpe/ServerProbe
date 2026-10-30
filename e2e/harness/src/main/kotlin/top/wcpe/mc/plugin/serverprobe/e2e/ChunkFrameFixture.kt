package top.wcpe.mc.plugin.serverprobe.e2e

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仅用于（帧分片）的回环 fixture。
 *
 * ## 判据来源
 * `docs/specs/frame-chunking.md` §5 的"真机"一行：fixture 构造 1 MiB 回执，断言收到 N 片、
 * base64 解码后字节一致；并且 **status 由逐项断言汇总**，不是"跑完即 PASS"。
 * 因此本类把每条断言的结果都写进证据表，任一断言失败即抛异常让场景失败。
 *
 * ## 独立状态机
 * 与的 `ProtocolWorkerFixture` 共用 [WebSocketFixtureServer] 的连接与帧编解码，
 * 但**不复用**它的 [Stage] 序列：分片场景真正的判定对象是"按 groupId 重组出的字节"，
 * 前置步骤只需要 hello/pong 与一条 `big` 命令。硬塞进既有状态机会让两个场景互相牵连。
 *
 * ## 为什么断言"分片帧长度 ≤ 阈值"
 * 规格 §3.2 要求**每片整帧序列化字节**都不超过 `max-frame-bytes`。fixture 读到的帧文本
 * 就是服务端序列化后的完整帧，因此可以逐片直接核对阈值是否真的生效
 * （而不只是"分片数量看起来对"）。
 */
class ChunkFrameFixture private constructor(
    endpoint: FixtureEndpoint,
    /** 场景配置里写入的单帧上限，用于逐片核对（必须与 config 模板一致）。 */
    private val maxFrameBytes: Int,
) : WebSocketFixtureServer(endpoint.serverSocket, endpoint.token, "serverprobe-e2e-chunk-fixture") {

    private val commandsOpen = AtomicBoolean(false)
    private val helloSeen = AtomicBoolean(false)
    private val pongSeen = AtomicBoolean(false)
    private val bigCommandSent = AtomicBoolean(false)

    /** 已收到的片：seq → base64 文本；用升序有序映射，重组时无需再排序。 */
    private val chunks = sortedMapOf<Int, String>()

    /** 组标识：只在首片时确定，后续片必须完全一致。 */
    private var groupId: String? = null

    /** 组总片数：只在首片时确定，后续每片都必须与之一致（规格 §3.1：`total` 写入每一片）。 */
    private var total = 0

    /** Provider 注册完成后允许 fixture 下发大回执命令。 */
    fun openCommands() {
        commandsOpen.set(true)
        maybeSendBigCommand()
    }

    /** hello 唤醒 welcome 与 ping，其余 event 按分片/忽略分类。 */
    override fun handleText(text: String) {
        when (jsonString(text, "type")) {
            "hello" -> handleHello(text)
            "event" -> handleEvent(text)
            else -> throw IllegalStateException("收到未知桥帧:${text.take(PREVIEW_CHARS)}")
        }
    }

    /** 只接受指定实例的 hello，随后驱动 RFC 6455 ping/pong。 */
    private fun handleHello(text: String) {
        check(jsonString(text, "instance") == WsFixtureProtocol.INSTANCE_ID) { "hello 实例标识不匹配" }
        check(helloSeen.compareAndSet(false, true)) { "重复收到 hello" }
        sendText("{\"type\":\"welcome\"}")
        sendPing()
    }

    /** pong 到达即控制帧往返成立，随后可下发大回执命令。 */
    override fun onPong() {
        pongSeen.set(true)
        maybeSendBigCommand()
    }

    /** 分片帧进重组；未分片的 command_result 说明分片根本没触发，必须大声失败。 */
    private fun handleEvent(text: String) {
        when (jsonString(text, "event")) {
            EVENT_CHUNK -> handleChunk(text)
            "command_result" -> throw IllegalStateException(
                "回执未分片：收到未分片的 command_result（分片未触发或整组被降级）:${text.take(PREVIEW_CHARS)}",
            )
            "connected" -> Unit
            "player_join", "player_quit", "chat", "cross_server" -> Unit
            else -> throw IllegalStateException("收到未知业务事件:${text.take(PREVIEW_CHARS)}")
        }
    }

    /**
     * 收下一片并逐项校验，收齐 [total] 片后转入整组断言。
     *
     * 逐片校验的顺序刻意与规格 §3.1 的字段表一致：`event` → `groupId` → `seq` → `total`
     * → `data` 字母表，便于失败时一眼看出是哪条契约破了。
     */
    private fun handleChunk(text: String) {
        check(bigCommandSent.get()) { "未下发大回执命令就收到分片帧" }
        val frameBytes = text.toByteArray(StandardCharsets.UTF_8).size
        check(frameBytes <= maxFrameBytes) {
            "分片帧超过单帧上限：$frameBytes > $maxFrameBytes 字节"
        }
        check(jsonString(text, "ofType") == EVENT_COMMAND_RESULT) {
            "分片帧 ofType 不匹配:${jsonString(text, "ofType")}"
        }
        check(jsonString(text, "requestId") == REQUEST_BIG) { "分片帧 requestId 未透传原命令标识" }
        check(jsonString(text, "instance") == WsFixtureProtocol.INSTANCE_ID) { "分片帧信封 instance 不匹配" }
        val chunkGroupId = checkNotNull(jsonString(text, "groupId")) { "分片帧缺少 groupId" }
        checkGroupId(chunkGroupId)
        val chunkTotal = checkNotNull(jsonInt(text, "total")) { "分片帧缺少 total" }
        val seq = checkNotNull(jsonInt(text, "seq")) { "分片帧缺少 seq" }
        checkChunkIndex(chunkGroupId, chunkTotal, seq)
        val data = checkNotNull(jsonRawString(text, "data")) { "分片帧缺少 data" }
        checkBase64Slice(data)
        chunks[seq] = data
        if (chunks.size == total) {
            verifyGroup()
        }
    }

    /** `groupId` 必须是完整 UUID 文本（36 字符），且同组内完全一致。 */
    private fun checkGroupId(chunkGroupId: String) {
        check(chunkGroupId.length == GROUP_ID_CHARS) { "groupId 不是 36 字符 UUID:${chunkGroupId.length} 字符" }
        check(GROUP_ID_SHAPE.matches(chunkGroupId)) { "groupId 不符合 UUID 形态:$chunkGroupId" }
        val expected = groupId
        if (expected == null) {
            groupId = chunkGroupId
        } else {
            check(expected == chunkGroupId) { "同一组出现多个 groupId:$expected / $chunkGroupId" }
        }
    }

    /** `seq` 从 0 连续且不重复；`total` 在每片上都等于整组实际片数。 */
    private fun checkChunkIndex(chunkGroupId: String, chunkTotal: Int, seq: Int) {
        check(chunkTotal >= MIN_TOTAL) { "total 非正:$chunkTotal" }
        check(seq in 0 until chunkTotal) { "seq 越界:$seq / total=$chunkTotal" }
        check(!chunks.containsKey(seq)) { "重复收到 seq=$seq（groupId=$chunkGroupId）" }
        if (total == 0) {
            total = chunkTotal
        } else {
            check(total == chunkTotal) { "同组 total 不一致：$total / $chunkTotal" }
        }
    }

    /**
     * `data` 必须是**纯 base64 字母表**切片。
     *
     * 这正是选 base64 方案的核心收益（规格 §3.1）：`data` 里不应出现任何需要 JSON 转义的字符，
     * 因此这里连"出现了转义"都不允许——不是比较反转义后的值，而是直接看原始文本。
     * 长度取 4 的整数倍则保证每片可独立解码。
     */
    private fun checkBase64Slice(data: String) {
        check(data.isNotEmpty()) { "分片 data 为空" }
        // 先断言"没有任何需要 JSON 转义的字符"：这是选 base64 方案的核心收益，失败信息应当直指这一点。
        check(data.none { it == '"' || it == '\\' || it == '\n' || it == '\r' }) { "分片 data 含需转义字符" }
        check(BASE64_ALPHABET.matches(data)) { "分片 data 含 base64 字母表之外的字符" }
        check(data.length % BASE64_UNIT == 0) { "分片 data 长度不是 4 的整数倍:${data.length}" }
    }

    /**
     * 整组断言：收齐后按 seq 升序拼接 → base64 解码 → 与期望字节逐一比较。
     *
     * 先比字节再比字符串（规格 §5 的"重组先解码"）：字符串相等可能掩盖非法 UTF-8 被替换成
     * U+FFFD 的情况，字节比较不会。
     */
    private fun verifyGroup() {
        val currentGroupId = checkNotNull(groupId) { "分组状态丢失" }
        check(chunks.size == total) { "片数不匹配：${chunks.size} / $total" }
        check(total >= MIN_TOTAL) { "只收到 $total 片，未真正触发分片" }
        check(chunks.keys.toList() == (0 until total).toList()) { "seq 不是从 0 连续：${chunks.keys}" }
        val concatenated = chunks.values.joinToString(separator = "")
        val decoded = decodeWholeGroup(concatenated)
        val perChunk = decodePerChunk()
        check(decoded.contentEquals(perChunk)) { "整组解码与逐片解码再拼接的字节不一致" }
        val text = String(decoded, StandardCharsets.UTF_8)
        check(text.toByteArray(StandardCharsets.UTF_8).contentEquals(decoded)) { "重组文本不是无损 UTF-8" }
        check(decoded.size > maxFrameBytes) { "重组帧仅 ${decoded.size} 字节，未超过单帧上限，分片未被真正触发" }
        checkSingleFrame(text)
        val output = extractOutput(text)
        val expected = ChunkFixturePayload.bytes
        check(output.toByteArray(StandardCharsets.UTF_8).contentEquals(expected)) { "重组载荷字节与期望不一致" }
        check(output.startsWith(ChunkFixturePayload.HEAD_MARKER)) { "重组载荷首标记丢失" }
        check(output.endsWith(ChunkFixturePayload.TAIL_MARKER)) { "重组载荷尾标记丢失" }
        completeEvidence(
            mapOf(
                "chunkEvent" to EVENT_CHUNK,
                "groupId" to currentGroupId,
                "groupIdChars" to currentGroupId.length.toString(),
                "chunks" to total.toString(),
                "seqContinuous" to "true",
                "totalOnEveryChunk" to "true",
                "dataBase64Only" to "true",
                "chunkMaxBytes" to maxFrameBytes.toString(),
                "reassembledBytes" to decoded.size.toString(),
                "payloadBytes" to expected.size.toString(),
                "byteIdentical" to "true",
                "decodeModesEqual" to "true",
            ),
        )
    }

    /** 整组拼接后一次解码（规格 §3.1 的首选重组方式）。 */
    private fun decodeWholeGroup(concatenated: String): ByteArray =
        Base64.getDecoder().decode(concatenated)

    /** 逐片解码后拼接字节；与整组解码结果必须一致（每片长度取 4 的倍数才成立）。 */
    private fun decodePerChunk(): ByteArray {
        val slices = chunks.values.map { Base64.getDecoder().decode(it) }
        val joined = ByteArray(slices.sumOf { it.size })
        var offset = 0
        slices.forEach {
            it.copyInto(joined, offset)
            offset += it.size
        }
        return joined
    }

    /** 重组出的原文必须仍是一条完整的 `command_result` 帧（证明分片的是原帧，不是被改写的内容）。 */
    private fun checkSingleFrame(text: String) {
        check(text.startsWith("{")) { "重组文本不是 JSON 对象" }
        check(jsonString(text, "type") == FRAME_TYPE_EVENT) { "重组帧 type 不匹配" }
        check(jsonString(text, "event") == EVENT_COMMAND_RESULT) { "重组帧 event 不匹配" }
        check(jsonBoolean(text, "success") == true) { "重组帧 success 不为 true" }
    }

    /** 从重组帧里取出 `data.output` 并反转义，得到业务侧的原始载荷文本。 */
    private fun extractOutput(text: String): String {
        val raw = checkNotNull(jsonRawString(text, "output")) { "重组帧缺少 data.output" }
        return unescapeJson(raw)
    }

    /** 仅在 hello、pong 和 Provider 注册都完成后下发大回执命令。 */
    private fun maybeSendBigCommand() {
        if (commandsOpen.get() && helloSeen.get() && pongSeen.get() && bigCommandSent.compareAndSet(false, true)) {
            sendText(
                "{\"type\":\"command\",\"action\":\"$ACTION_BIG\",\"target\":\"\",\"reason\":\"\"," +
                    "\"requestId\":\"$REQUEST_BIG\",\"domain\":\"$E2E_DOMAIN\",\"payloadJson\":\"{}\"}",
            )
        }
    }

    companion object {

        /** 从启动前生成的测试配置读取一次性凭据，并在回环端口启动分片 fixture。 */
        fun startFromProbeConfiguration(pluginsDirectory: File, maxFrameBytes: Int): ChunkFrameFixture =
            ChunkFrameFixture(openFixtureEndpoint(pluginsDirectory, ""), maxFrameBytes)

        /** 分片帧的 `event` 子类型（规格 §3.1）。 */
        private const val EVENT_CHUNK = "chunk"

        /** 帧类型：事件（与 `OutboundFrame.TYPE_EVENT` 一致）。 */
        private const val FRAME_TYPE_EVENT = "event"

        /** 事件子类型：命令回执；也是分片帧的 `ofType` 与重组后的 `event`。 */
        private const val EVENT_COMMAND_RESULT = "command_result"

        private const val E2E_DOMAIN = "e2e"

        /** 与 ServerProbeE2eHarnessPlugin 的 `big` 动作、请求标识保持一致。 */
        private const val ACTION_BIG = "big"
        private const val REQUEST_BIG = "e2e-big"

        /** base64 每组字符数：每片长度必须是它的整数倍才能独立解码。 */
        private const val BASE64_UNIT = 4

        /** `groupId` 必须是完整 UUID 文本。 */
        private const val GROUP_ID_CHARS = 36

        /** 至少 2 片，否则说明根本没超阈值。 */
        private const val MIN_TOTAL = 2

        /** 失败信息里回显的帧长度上限，避免把 MiB 级文本全打进日志。 */
        private const val PREVIEW_CHARS = 512

        private val GROUP_ID_SHAPE =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** base64 字母表：`A–Z a–z 0–9 + /` 加尾部补位 `=`。 */
        private val BASE64_ALPHABET = Regex("^[A-Za-z0-9+/]+={0,2}$")
    }
}
