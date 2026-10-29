package top.wcpe.mc.plugin.serverprobe.e2e

import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * E2E fixture 共用的 RFC 6455 服务端子集。
 *
 * ## 为什么抽出基类
 * 各场景（桥业务链路、帧分片）需要的是**同一个**"接受一条探针连接 + 握手 + 收发帧"的
 * 底层能力，而**状态机完全不同**：桥业务场景按 Stage 逐条校验回执，分片场景按 groupId 重组大帧。
 * 把状态机塞进同一个类里既会让 `Stage` 不断长分支，也会让新场景不得不复用不相干的前置步骤。
 * 因此这里只保留与业务语义无关的部分：连接、握手、帧编解码、JSON 取值、证据 future。
 *
 * ## 一次只接受一条连接
 * 探针桥是"反向 WS、单连接"；fixture 只需一条连接即可完成验收。重连语义由各场景自行决定
 * （本类不重试 accept）。
 *
 * ## 失败必须"大声"
 * 任何契约漂移都抛异常，由 [serve] 兜住并让证据 future 异常完成；绝不静默放过——
 * 否则 E2E 会变成"跑完即绿"。
 */
abstract class WebSocketFixtureServer(
    private val serverSocket: ServerSocket,
    /** 本次随机 token：握手时校验探针确实带着它连上来。 */
    protected val token: String,
    threadName: String,
) : AutoCloseable {

    /** 场景证据：键为断言项、值为可读结果；由子类在断言全部通过后 [completeEvidence]。 */
    protected val evidence = CompletableFuture<Map<String, String>>()

    private val socket = AtomicReference<Socket?>(null)

    private val output = AtomicReference<DataOutputStream?>(null)

    init {
        Thread({ serve() }, threadName).apply {
            isDaemon = true
            start()
        }
    }

    /** 等待状态机收齐全部证据；超时或异常即失败。 */
    fun awaitEvidence(timeoutSeconds: Long = DEFAULT_EVIDENCE_TIMEOUT_SECONDS): Map<String, String> =
        evidence.get(timeoutSeconds, TimeUnit.SECONDS)

    override fun close() {
        runCatching { socket.getAndSet(null)?.close() }
        runCatching { serverSocket.close() }
    }

    /** 子类处理一条上行文本帧。 */
    protected abstract fun handleText(text: String)

    /** 收到合法 pong 后的钩子（载荷校验已在 [handleFrame] 内完成）。 */
    protected open fun onPong() {}

    /** 全部断言通过时登记证据。 */
    protected fun completeEvidence(details: Map<String, String>) {
        evidence.complete(details)
    }

    /** 单连接服务循环：握手后读取客户端帧，直到测试服关闭或协议已失败。 */
    private fun serve() {
        runCatching {
            val accepted = serverSocket.accept()
            socket.set(accepted)
            accepted.use { serveConnection(it) }
        }.onFailure { failEvidence(it) }
    }

    /** 让证据 future 以异常结束（已完成则忽略）。 */
    private fun failEvidence(error: Throwable) {
        if (!evidence.isDone) {
            evidence.completeExceptionally(error)
        }
    }

    /** 完成 HTTP Upgrade，再逐帧处理探针上行消息。 */
    private fun serveConnection(connection: Socket) {
        val input = BufferedInputStream(connection.getInputStream())
        val stream = DataOutputStream(connection.getOutputStream())
        handshake(input, stream)
        output.set(stream)
        while (!connection.isClosed && !evidence.isCompletedExceptionally) {
            handleFrame(readFrame(input))
        }
    }

    /** 验证探针确实以本次随机 token 与实例标识建立 WebSocket 连接。 */
    private fun handshake(input: InputStream, stream: DataOutputStream) {
        val request = readHttpLine(input)
        check(request.startsWith("GET ${WsFixtureProtocol.BRIDGE_PATH}?")) { "WebSocket 路径不匹配:$request" }
        check(request.contains("token=$token") && request.contains("instance=${WsFixtureProtocol.INSTANCE_ID}")) {
            "WebSocket 凭据不匹配"
        }
        val headers = readHeaders(input)
        val key = checkNotNull(headers["sec-websocket-key"]) { "缺少 Sec-WebSocket-Key" }
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: ${webSocketAccept(key)}\r\n\r\n"
        stream.write(response.toByteArray(Charsets.US_ASCII))
        stream.flush()
    }

    /** 读取 HTTP 头并按小写键保存，握手结束空行不写入。 */
    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readHttpLine(input)
            if (line.isEmpty()) {
                return headers
            }
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
        }
    }

    /** 控制帧只验 pong；文本帧交给子类；客户端主动 close 视为连接结束。 */
    private fun handleFrame(frame: Frame) {
        when (frame.opcode) {
            WsFixtureProtocol.OPCODE_PING -> sendFrame(WsFixtureProtocol.OPCODE_PONG, frame.payload)
            WsFixtureProtocol.OPCODE_PONG -> {
                check(frame.payload.contentEquals(WsFixtureProtocol.PING_PAYLOAD)) { "pong 载荷不匹配" }
                onPong()
            }
            WsFixtureProtocol.OPCODE_TEXT -> handleText(String(frame.payload, Charsets.UTF_8))
            WsFixtureProtocol.OPCODE_CLOSE -> throw EOFException("探针主动关闭 WebSocket")
            else -> throw IllegalStateException("收到未预期的 WebSocket opcode:${frame.opcode}")
        }
    }

    /** 发送一条文本帧。 */
    protected fun sendText(text: String) = sendFrame(WsFixtureProtocol.OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))

    /** 发送 RFC 6455 ping，驱动探针回 pong。 */
    protected fun sendPing() = sendFrame(WsFixtureProtocol.OPCODE_PING, WsFixtureProtocol.PING_PAYLOAD)

    /** 发送未掩码的服务端帧；同一 fixture 仅有一个连接，但仍串行化避免并发交错。 */
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val stream = checkNotNull(output.get()) { "WebSocket 尚未完成握手" }
        synchronized(stream) {
            stream.writeByte(WsFixtureProtocol.FIN or opcode)
            writeLength(stream, payload.size)
            stream.write(payload)
            stream.flush()
        }
    }

    /** 服务端帧只支持本测试所需的小文本；超过 16 位长度仍按 RFC 6455 正确编码。 */
    private fun writeLength(stream: DataOutputStream, length: Int) {
        when {
            length < WsFixtureProtocol.LEN_16 -> stream.writeByte(length)
            length <= WsFixtureProtocol.MAX_16 -> {
                stream.writeByte(WsFixtureProtocol.LEN_16)
                stream.writeShort(length)
            }
            else -> {
                stream.writeByte(WsFixtureProtocol.LEN_64)
                stream.writeLong(length.toLong())
            }
        }
    }

    /** 读取并解掩客户端帧；RFC 6455 要求客户端帧必须携带掩码。 */
    private fun readFrame(input: InputStream): Frame {
        val first = readByte(input)
        val second = readByte(input)
        check((first and WsFixtureProtocol.FIN) != 0) { "fixture 不支持分片帧" }
        check((second and WsFixtureProtocol.MASK) != 0) { "客户端帧缺少掩码" }
        val length = readLength(input, second and WsFixtureProtocol.LENGTH_MASK)
        val mask = ByteArray(WsFixtureProtocol.MASK_BYTES) { readByte(input).toByte() }
        val payload = ByteArray(length)
        readFully(input, payload)
        payload.indices.forEach { index ->
            payload[index] = (payload[index].toInt() xor mask[index % WsFixtureProtocol.MASK_BYTES].toInt()).toByte()
        }
        return Frame(first and WsFixtureProtocol.OPCODE_MASK, payload)
    }

    /** 读取 7/16/64 位长度字段；fixture 只接受可放入 Int 的测试载荷。 */
    private fun readLength(input: InputStream, initial: Int): Int = when (initial) {
        WsFixtureProtocol.LEN_16 -> (readByte(input) shl WsFixtureProtocol.BYTE_BITS) or readByte(input)
        WsFixtureProtocol.LEN_64 -> readLongLength(input)
        else -> initial
    }

    /** 64 位长度高位必须为零，避免测试 fixture 接受不可分配的大载荷。 */
    private fun readLongLength(input: InputStream): Int {
        val value = (0 until WsFixtureProtocol.LONG_BYTES).fold(0L) { current, _ ->
            (current shl WsFixtureProtocol.BYTE_BITS) or readByte(input).toLong()
        }
        check(value <= Int.MAX_VALUE) { "帧载荷过大:$value" }
        return value.toInt()
    }

    /**
     * 从 JSON 文本取一个字符串字段的**原始**（未反转义）内容。
     *
     * 手写扫描而非正则：分片场景要在 MiB 级帧上取 `data` / `output`，
     * 含转义分支的正则在长文本上可能出现灾难性回溯。
     *
     * @return 未反转义的内容；字段不存在或后面不是字符串字面量时返回 null。
     */
    protected fun jsonRawString(text: String, key: String): String? {
        val marker = "\"$key\""
        var from = text.indexOf(marker)
        while (from >= 0) {
            var index = from + marker.length
            while (index < text.length && text[index].isWhitespace()) index++
            if (index < text.length && text[index] == ':') {
                index++
                while (index < text.length && text[index].isWhitespace()) index++
                if (index < text.length && text[index] == '"') return scanJsonStringBody(text, index + 1)
            }
            from = text.indexOf(marker, from + marker.length)
        }
        return null
    }

    /** 从 [start]（左引号之后）扫描到未被转义的右引号，返回中间原文。 */
    private fun scanJsonStringBody(text: String, start: Int): String {
        var index = start
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index += if (index + 1 < text.length && text[index + 1] == 'u') {
                    UNICODE_ESCAPE_LENGTH
                } else {
                    ESCAPE_PREFIX_LENGTH
                }
                '"' -> return text.substring(start, index)
                else -> index++
            }
        }
        error("JSON 字符串未闭合")
    }

    /** 从 JSON 文本取一个字符串字段（已反转义）；字段不存在返回 null。 */
    protected fun jsonString(text: String, key: String): String? =
        jsonRawString(text, key)?.let(::unescapeJson)

    /** 从 JSON 文本取整数字段。 */
    protected fun jsonInt(text: String, key: String): Int? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()

    /** 从 JSON 文本取布尔字段。 */
    protected fun jsonBoolean(text: String, key: String): Boolean? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)").find(text)?.groupValues?.get(1)?.toBoolean()

    /**
     * 从 JSON 文本取整数字段并按 Long 解析。
     *
     * 不能用 [jsonInt]：`refreshedAtMs` / `timestamp` 这类 epoch 毫秒（约 1.7e12）超出 Int 范围，
     * 会让它返回 null，断言就变成"字段缺失"的误导性失败。负号一并接受，
     * 这样"值非法"能被断言看见，而不是伪装成"字段不存在"。
     */
    protected fun jsonLongField(text: String, key: String): Long? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull()

    /** 字段是否存在（存在且后面跟冒号）；用于断言"未上报的字段整个缺席"。 */
    protected fun jsonHasField(text: String, key: String): Boolean =
        Regex("\"${Regex.escape(key)}\"\\s*:").containsMatchIn(text)

    /**
     * 从 JSON 文本取一个**对象**字段的原文（含首尾花括号）。
     *
     * 基类的扁平取值不够用：回执里的 `applied` 是"路径 → 子对象"的映射，必须按路径逐项取子对象。
     * 同名键可能先作为**数组元素**出现（如路径同时出现在 `reloaded[]` 与 `applied{}` 里），
     * 因此只接受"后面紧跟冒号且值是对象"的那一次命中。
     *
     * @return 对象原文；字段缺失或值不是对象时返回 null。
     */
    protected fun jsonObjectAt(text: String, key: String): String? {
        val start = jsonValueStart(text, key, '{') ?: return null
        return balancedRegion(text, start, '{', '}')
    }

    /**
     * 从 JSON 文本取一个**数组**字段的原文（含首尾方括号）。
     *
     * @return 数组原文；字段缺失或值不是数组时返回 null。
     */
    protected fun jsonArrayAt(text: String, key: String): String? {
        val start = jsonValueStart(text, key, '[') ?: return null
        return balancedRegion(text, start, '[', ']')
    }

    /**
     * 从 JSON 文本取一个字符串数组的**元素**（已反转义）。
     *
     * @return 元素列表；字段缺失或不是数组时返回空列表。
     */
    protected fun jsonStringArray(text: String, key: String): List<String> {
        val array = jsonArrayAt(text, key) ?: return emptyList()
        return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
            .findAll(array)
            .map { unescapeJson(it.groupValues[1]) }
            .toList()
    }

    /**
     * 取数组内的**顶层对象**原文（按出现顺序）。
     *
     * 扫描时跳过字符串字面量：`message` 里完全可能出现 `{`/`}`，不跳过就会截错对象。
     *
     * @param array 形如 `[{...},{...}]` 的数组原文。
     * @return 每个顶层对象的原文（含花括号）。
     */
    protected fun jsonObjectsIn(array: String): List<String> {
        val objects = ArrayList<String>()
        var index = 0
        var inString = false
        while (index < array.length) {
            val character = array[index]
            when {
                // 转义序列整体跳过：否则 `\"` 会被当成字符串结束，括号计数随即失真。
                inString && character == '\\' -> index++
                character == '"' -> inString = !inString
                inString -> Unit
                character == '{' -> {
                    val objectText = balancedRegion(array, index, '{', '}') ?: return objects
                    objects.add(objectText)
                    index += objectText.length
                    continue
                }
            }
            index++
        }
        return objects
    }

    /** 定位 `"key":` 之后第一个 [open] 字符的下标；该键后不是该形态时继续找下一次出现。 */
    private fun jsonValueStart(text: String, key: String, open: Char): Int? {
        val marker = "\"$key\""
        var from = text.indexOf(marker)
        while (from >= 0) {
            var index = from + marker.length
            while (index < text.length && text[index].isWhitespace()) index++
            if (index < text.length && text[index] == ':') {
                index++
                while (index < text.length && text[index].isWhitespace()) index++
                if (index < text.length && text[index] == open) return index
            }
            from = text.indexOf(marker, from + marker.length)
        }
        return null
    }

    /** 从 [start] 处的开括号开始做括号配对，返回含首尾括号的原文；未闭合返回 null。 */
    private fun balancedRegion(text: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        var index = start
        var inString = false
        while (index < text.length) {
            when {
                inString && text[index] == '\\' -> index++
                text[index] == '"' -> inString = !inString
                inString -> Unit
                text[index] == open -> depth++
                text[index] == close -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
            index++
        }
        return null
    }

    /** 解码协议内字符串的 JSON 转义（覆盖 `\" \\ \/ \b \f \n \r \t \uXXXX`）。 */
    protected fun unescapeJson(value: String): String {
        if (!value.contains('\\')) return value
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character != '\\' || index + 1 >= value.length) {
                result.append(character)
                index++
                continue
            }
            when (val escaped = value[index + 1]) {
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'u' -> {
                    check(index + UNICODE_ESCAPE_LENGTH <= value.length) { "JSON \\u 转义不完整" }
                    result.append(value.substring(index + 2, index + UNICODE_ESCAPE_LENGTH).toInt(HEX_RADIX).toChar())
                    index += UNICODE_ESCAPE_LENGTH
                    continue
                }
                else -> result.append(escaped)
            }
            index += ESCAPE_PREFIX_LENGTH
        }
        return result.toString()
    }

    /** 转义 JSON 字符串值，使结构化 payload 作为 JSON 字符串传输。 */
    protected fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    /** 读取 CRLF HTTP 行，用于 WebSocket Upgrade。 */
    private fun readHttpLine(input: InputStream): String {
        val text = StringBuilder()
        var previous = -1
        while (true) {
            val current = input.read()
            if (current < 0) {
                throw EOFException("读取 HTTP 握手时连接关闭")
            }
            if (previous == WsFixtureProtocol.CR && current == WsFixtureProtocol.LF) {
                text.setLength(text.length - 1)
                return text.toString()
            }
            text.append(current.toChar())
            previous = current
        }
    }

    /** 读取完整帧载荷，流提前结束即协议失败。 */
    private fun readFully(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) {
                throw EOFException("读取 WebSocket 帧载荷时连接关闭")
            }
            offset += read
        }
    }

    private fun readByte(input: InputStream): Int =
        input.read().takeIf { it >= 0 } ?: throw EOFException("读取 WebSocket 帧时连接关闭")

    private fun webSocketAccept(key: String): String {
        val bytes = MessageDigest.getInstance("SHA-1")
            .digest((key + WsFixtureProtocol.WEB_SOCKET_GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(bytes)
    }

    private data class Frame(val opcode: Int, val payload: ByteArray)

    private companion object {
        /** 证据等待默认上限：桥连接重试可能跨越数秒，留足余量。 */
        const val DEFAULT_EVIDENCE_TIMEOUT_SECONDS = 25L

        /** `\uXXXX` 占 6 个字符（`\`+`u`+4 位十六进制）。 */
        const val UNICODE_ESCAPE_LENGTH = 6

        /** 普通转义占 2 个字符（`\`+被转义字符）。 */
        const val ESCAPE_PREFIX_LENGTH = 2

        /** 十六进制基数。 */
        const val HEX_RADIX = 16
    }
}

/**
 * fixture 侧共用的 RFC 6455 常量与探针配置读取。
 *
 * 单列成对象而不是散在各 fixture 的 companion 里：握手、帧编解码与配置解析都是
 * "多场景共用"的协议细节，改名或补常量时只应有一处可改。
 */
internal object WsFixtureProtocol {

    /** 探针桥的 WS 路径。 */
    const val BRIDGE_PATH = "/ws/plugin-bridge"

    /** 探针插件在服务端插件目录下的名字。 */
    const val SERVER_PROBE_PLUGIN = "ServerProbe"

    /** 场景配置里写入的实例标识（与 config 模板一致）。 */
    const val INSTANCE_ID = "e2e-fixture"

    const val FIN = 0x80
    const val MASK = 0x80
    const val LENGTH_MASK = 0x7F
    const val OPCODE_MASK = 0x0F
    const val OPCODE_TEXT = 0x1
    const val OPCODE_CLOSE = 0x8
    const val OPCODE_PING = 0x9
    const val OPCODE_PONG = 0xA
    const val LEN_16 = 126
    const val LEN_64 = 127
    const val MAX_16 = 0xFFFF
    const val MASK_BYTES = 4
    const val LONG_BYTES = 8
    const val BYTE_BITS = 8
    const val CR = 13
    const val LF = 10
    const val MAX_PORT = 65535
    const val WEB_SOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** fixture 主动下发的 ping 载荷：pong 必须原样回送，用来验证控制帧往返。 */
    val PING_PAYLOAD = byteArrayOf(0x45, 0x32, 0x45)
}

/**
 * 从启动前生成的测试配置读取一次性凭据，并在指定回环端口启动 fixture 的服务端套接字。
 *
 * 配置缺失或不是回环地址一律立即失败：禁止悄然降级到"随便连"的形态，
 * 那会让场景在错误的地址上等待超时，掩盖真实问题。
 *
 * @param pluginsDirectory 服务端的 `plugins` 目录。
 * @param scenario 场景名（仅用于失败提示）。
 */
internal fun openFixtureEndpoint(pluginsDirectory: File, scenario: String): FixtureEndpoint {
    val config = File(pluginsDirectory, "${WsFixtureProtocol.SERVER_PROBE_PLUGIN}/config.yml")
    val values = config.readText()
    check(probeConfigValue(values, "enabled") == "true") { "$scenario 测试桥未启用" }
    check(probeConfigValue(values, "instance") == WsFixtureProtocol.INSTANCE_ID) { "$scenario 测试实例标识错误" }
    val token = probeConfigValue(values, "token")
    val endpoint = URI(probeConfigValue(values, "url"))
    check(endpoint.scheme == "ws" && endpoint.host == "127.0.0.1") { "$scenario 测试桥地址必须为本机 WS" }
    check(endpoint.port in 1..WsFixtureProtocol.MAX_PORT && endpoint.path == WsFixtureProtocol.BRIDGE_PATH) {
        "$scenario 测试桥地址无效"
    }
    val socket = ServerSocket(endpoint.port, 1, InetAddress.getLoopbackAddress())
    return FixtureEndpoint(socket, token)
}

/** fixture 的监听端点与一次性凭据。 */
internal class FixtureEndpoint(val serverSocket: ServerSocket, val token: String)

/** 读取本测试生成的最小 YAML 配置项；配置缺失立即失败，禁止悄然降级。 */
internal fun probeConfigValue(content: String, key: String): String = checkNotNull(
    Regex("(?m)^\\s*${Regex.escape(key)}:\\s*\"?([^\"\\r\\n]+)\"?\\s*$").find(content)?.groupValues?.get(1)?.trim(),
) { "测试配置缺少:$key" }
