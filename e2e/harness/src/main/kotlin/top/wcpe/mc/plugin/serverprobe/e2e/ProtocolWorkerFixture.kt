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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 仅用于 FR9 的回环 Worker fixture。
 *
 * 它实现 ServerProbe 已有桥所需的 RFC 6455 子集，严格验证握手、ping/pong、命令回执和业务事件；
 * 不依赖或模拟 JianManager 的任何运行时代码。
 */
class ProtocolWorkerFixture private constructor(
    private val serverSocket: ServerSocket,
    private val token: String,
) : AutoCloseable {

    private val evidence = CompletableFuture<Map<String, String>>()
    private val socket = AtomicReference<Socket?>(null)
    private val output = AtomicReference<DataOutputStream?>(null)
    private val commandsOpen = AtomicBoolean(false)
    private val helloSeen = AtomicBoolean(false)
    private val pongSeen = AtomicBoolean(false)
    private val manifestSent = AtomicBoolean(false)
    private val eventSeen = AtomicBoolean(false)
    private val emitResultSeen = AtomicBoolean(false)
    private val stage = AtomicReference(Stage.WAITING)
    private val inventoryEvent = CompletableFuture<Map<String, String>>()

    init {
        Thread({ serve() }, "serverprobe-e2e-worker-fixture").apply {
            isDaemon = true
            start()
        }
    }

    /** Provider 注册完成后允许 fixture 下发第一条业务命令。 */
    fun openCommands() {
        commandsOpen.set(true)
        maybeSendManifest()
    }

    /** 等待状态机收齐全部协议证据，超时即失败。 */
    fun awaitEvidence(): Map<String, String> = evidence.get(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    /** 等待真实 AllinInventorySync 经探针桥上报一条重点物品流转事件。 */
    fun awaitInventoryEvent(): Map<String, String> = inventoryEvent.get(FIXTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

    override fun close() {
        runCatching { socket.getAndSet(null)?.close() }
        runCatching { serverSocket.close() }
    }

    /** 单连接服务循环：握手后读取客户端帧，直到测试服关闭或协议已失败。 */
    private fun serve() {
        runCatching {
            val accepted = serverSocket.accept()
            socket.set(accepted)
            accepted.use { serveConnection(it) }
        }.onFailure { error ->
            if (!evidence.isDone) {
                evidence.completeExceptionally(error)
            }
            if (!inventoryEvent.isDone) {
                inventoryEvent.completeExceptionally(error)
            }
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
        check(request.startsWith("GET $BRIDGE_PATH?")) { "WebSocket 路径不匹配:$request" }
        check(request.contains("token=$token") && request.contains("instance=$INSTANCE_ID")) { "WebSocket 凭据不匹配" }
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

    /** 控制帧只验 pong；文本帧进入桥协议状态机；客户端主动 close 视为连接结束。 */
    private fun handleFrame(frame: Frame) {
        when (frame.opcode) {
            OPCODE_PING -> sendFrame(OPCODE_PONG, frame.payload)
            OPCODE_PONG -> {
                check(frame.payload.contentEquals(PING_PAYLOAD)) { "pong 载荷不匹配" }
                pongSeen.set(true)
                maybeSendManifest()
            }
            OPCODE_TEXT -> handleText(String(frame.payload, Charsets.UTF_8))
            OPCODE_CLOSE -> throw EOFException("探针主动关闭 WebSocket")
            else -> throw IllegalStateException("收到未预期的 WebSocket opcode:${frame.opcode}")
        }
    }

    /** hello 触发 welcome 与 ping，其余 event 按命令回执或业务事件分类验证。 */
    private fun handleText(text: String) {
        when (jsonString(text, "type")) {
            "hello" -> handleHello(text)
            "event" -> handleEvent(text)
            else -> throw IllegalStateException("收到未知桥帧:$text")
        }
    }

    /** 只接受指定实例的 hello，随后驱动 RFC 6455 ping/pong。 */
    private fun handleHello(text: String) {
        check(jsonString(text, "instance") == INSTANCE_ID) { "hello 实例标识不匹配" }
        check(helloSeen.compareAndSet(false, true)) { "重复收到 hello" }
        sendText("{\"type\":\"welcome\"}")
        sendFrame(OPCODE_PING, PING_PAYLOAD)
    }

    /** 忽略连接事件；业务事件与 command_result 必须满足当前状态机。 */
    private fun handleEvent(text: String) {
        when (jsonString(text, "event")) {
            "command_result" -> handleCommandResult(text)
            "${E2E_DOMAIN}_change" -> handleBusinessEvent(text)
            "economy_change" -> Unit
            "inventory_change" -> handleInventoryEvent(text)
            "connected" -> Unit
            "player_join", "player_quit", "chat", "cross_server" -> Unit
            else -> throw IllegalStateException("收到未知业务事件:$text")
        }
    }

    /** 真实背包事件必须保留业务域、去重键与物品动作，供 FR10 验收线程判定。 */
    private fun handleInventoryEvent(text: String) {
        runCatching {
            check(jsonString(text, "domain") == INVENTORY_DOMAIN) { "背包事件 domain 不匹配" }
            val dedupKey = checkNotNull(jsonString(text, "dedupKey")) { "背包事件缺少 dedupKey" }
            check(dedupKey.isNotBlank()) { "背包事件 dedupKey 为空" }
            inventoryEvent.complete(mapOf("dedupKey" to dedupKey))
        }.onFailure(inventoryEvent::completeExceptionally).getOrThrow()
    }

    /** 校验 e2e 业务事件的域、去重键和结构化字段。 */
    private fun handleBusinessEvent(text: String) {
        check(jsonString(text, "domain") == E2E_DOMAIN) { "业务事件 domain 不匹配" }
        check(jsonString(text, "dedupKey") == EVENT_DEDUP_KEY) { "业务事件 dedupKey 不匹配" }
        check(jsonString(text, "kind") == "emitted") { "业务事件 data.kind 不匹配" }
        eventSeen.set(true)
        advanceAfterEmit()
    }

    /** 按当前下发命令逐项校验 requestId、成功状态和输出后推进下一步。 */
    private fun handleCommandResult(text: String) {
        when (stage.get()) {
            Stage.MANIFEST -> verifyManifest(text)
            Stage.ECHO -> verifyEcho(text, REQUEST_ECHO, Stage.EMIT)
            Stage.EMIT -> verifyEmit(text)
            Stage.FAIL -> verifyFailure(text, REQUEST_FAIL, false, Stage.SLOW)
            Stage.SLOW -> verifyFailure(text, REQUEST_SLOW, false, Stage.FOLLOW_UP)
            Stage.FOLLOW_UP -> verifyFollowUp(text)
            Stage.WAITING -> throw IllegalStateException("未下发命令时收到 command_result")
            null -> throw IllegalStateException("协议状态机丢失当前阶段")
        }
    }

    /** manifest 必须由真实 BusinessHost 汇总，且包含刚注册的 e2e 域。 */
    private fun verifyManifest(text: String) {
        checkCommand(text, REQUEST_MANIFEST, true)
        check(jsonString(text, "output")?.contains("\"$E2E_DOMAIN\"") == true) { "manifest 未包含 e2e 域" }
        sendCommand(Stage.ECHO, REQUEST_ECHO, E2E_DOMAIN, "echo", ECHO_PAYLOAD)
    }

    /** echo 输出必须保留原 payload，证明 domain/action/payload 的完整路由。 */
    private fun verifyEcho(text: String, requestId: String, next: Stage) {
        checkCommand(text, requestId, true)
        check(jsonString(text, "output") == ECHO_PAYLOAD) { "echo 回执未保留原 payload" }
        sendCommand(next, REQUEST_EMIT, E2E_DOMAIN, "emit", "{}")
    }

    /** emit 命令自身成功且对应业务 event 已到达后，才可进入失败分支。 */
    private fun verifyEmit(text: String) {
        checkCommand(text, REQUEST_EMIT, true)
        check(jsonString(text, "output") == "emitted") { "emit 回执不匹配" }
        emitResultSeen.set(true)
        advanceAfterEmit()
    }

    /** 失败与超时都必须作为失败回执保留在桥读循环内。 */
    private fun verifyFailure(text: String, requestId: String, expected: Boolean, next: Stage) {
        checkCommand(text, requestId, expected)
        sendCommand(next, requestIdFor(next), E2E_DOMAIN, actionFor(next), ECHO_PAYLOAD)
    }

    /** 超时后的 echo 仍成功，证明业务故障未中断桥读循环。 */
    private fun verifyFollowUp(text: String) {
        checkCommand(text, REQUEST_FOLLOW_UP, true)
        check(jsonString(text, "output") == ECHO_PAYLOAD) { "超时后的 echo 回执不匹配" }
        evidence.complete(
            mapOf(
                "hello" to helloSeen.get().toString(),
                "pong" to pongSeen.get().toString(),
                "event" to eventSeen.get().toString(),
                "timeoutRecovered" to "true",
            ),
        )
    }

    /** e2e event 与 emit 回执可能先后抵达，两个条件齐备才进入 fail。 */
    private fun advanceAfterEmit() {
        if (eventSeen.get() && emitResultSeen.get() && stage.compareAndSet(Stage.EMIT, Stage.FAIL)) {
            sendCommand(Stage.FAIL, REQUEST_FAIL, E2E_DOMAIN, "fail", "{}")
        }
    }

    /** 仅在 hello、pong 和 Provider 注册都完成后下发 manifest。 */
    private fun maybeSendManifest() {
        if (commandsOpen.get() && helloSeen.get() && pongSeen.get() && manifestSent.compareAndSet(false, true)) {
            sendCommand(Stage.MANIFEST, REQUEST_MANIFEST, "jbis", "manifest", "")
        }
    }

    /** 组装与 BridgeClient 当前协议形态一致的 command 文本帧。 */
    private fun sendCommand(stage: Stage, requestId: String, domain: String, action: String, payload: String) {
        this.stage.set(stage)
        sendText(
            "{\"type\":\"command\",\"action\":\"$action\",\"target\":\"\",\"reason\":\"\",\"requestId\":\"$requestId\",\"domain\":\"$domain\",\"payloadJson\":\"${escapeJson(payload)}\"}",
        )
    }

    /** 指定 command_result 的关联标识与 success 布尔值必须精确匹配。 */
    private fun checkCommand(text: String, requestId: String, success: Boolean) {
        check(jsonString(text, "requestId") == requestId) { "command_result requestId 不匹配" }
        check(jsonBoolean(text, "success") == success) { "command_result success 不匹配" }
    }

    /** SLOW 后继续下发 follow-up echo；其它阶段不应调用本方法。 */
    private fun requestIdFor(stage: Stage): String = when (stage) {
        Stage.SLOW -> REQUEST_SLOW
        Stage.FOLLOW_UP -> REQUEST_FOLLOW_UP
        else -> error("未定义后续 requestId:$stage")
    }

    /** 失败后下发 slow，slow 后下发 echo；其它阶段不应调用本方法。 */
    private fun actionFor(stage: Stage): String = when (stage) {
        Stage.SLOW -> "slow"
        Stage.FOLLOW_UP -> "echo"
        else -> error("未定义后续 action:$stage")
    }

    /** 发送未掩码的服务端帧；同一 fixture 仅有一个连接，但仍串行化避免并发交错。 */
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val stream = checkNotNull(output.get()) { "WebSocket 尚未完成握手" }
        synchronized(stream) {
            stream.writeByte(FIN or opcode)
            writeLength(stream, payload.size)
            stream.write(payload)
            stream.flush()
        }
    }

    private fun sendText(text: String) = sendFrame(OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))

    /** 服务端帧只支持本测试所需的小文本；超过 16 位长度仍按 RFC 6455 正确编码。 */
    private fun writeLength(stream: DataOutputStream, length: Int) {
        when {
            length < LEN_16 -> stream.writeByte(length)
            length <= MAX_16 -> {
                stream.writeByte(LEN_16)
                stream.writeShort(length)
            }
            else -> {
                stream.writeByte(LEN_64)
                stream.writeLong(length.toLong())
            }
        }
    }

    /** 读取并解掩客户端帧；RFC 6455 要求客户端帧必须携带掩码。 */
    private fun readFrame(input: InputStream): Frame {
        val first = readByte(input)
        val second = readByte(input)
        check((first and FIN) != 0) { "fixture 不支持分片帧" }
        check((second and MASK) != 0) { "客户端帧缺少掩码" }
        val length = readLength(input, second and LENGTH_MASK)
        val mask = ByteArray(MASK_BYTES) { readByte(input).toByte() }
        val payload = ByteArray(length)
        readFully(input, payload)
        payload.indices.forEach { index -> payload[index] = (payload[index].toInt() xor mask[index % MASK_BYTES].toInt()).toByte() }
        return Frame(first and OPCODE_MASK, payload)
    }

    /** 读取 7/16/64 位长度字段；fixture 只接受可放入 Int 的测试载荷。 */
    private fun readLength(input: InputStream, initial: Int): Int = when (initial) {
        LEN_16 -> (readByte(input) shl BYTE_BITS) or readByte(input)
        LEN_64 -> readLongLength(input)
        else -> initial
    }

    /** 64 位长度高位必须为零，避免测试 fixture 接受不可分配的大载荷。 */
    private fun readLongLength(input: InputStream): Int {
        val value = (0 until LONG_BYTES).fold(0L) { current, _ -> (current shl BYTE_BITS) or readByte(input).toLong() }
        check(value <= Int.MAX_VALUE) { "帧载荷过大:$value" }
        return value.toInt()
    }

    /** 从 JSON 文本取一个字符串字段，支持本协议所需的转义字符。 */
    private fun jsonString(text: String, key: String): String? {
        val match = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").find(text) ?: return null
        return unescapeJson(match.groupValues[1])
    }

    /** 从 JSON 文本取布尔字段。 */
    private fun jsonBoolean(text: String, key: String): Boolean? =
        Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(true|false)").find(text)?.groupValues?.get(1)?.toBoolean()

    /** 解码协议内字符串的最小 JSON 转义集合。 */
    private fun unescapeJson(value: String): String {
        val result = StringBuilder()
        var escaped = false
        value.forEach { character ->
            if (escaped) {
                result.append(if (character == 'n') '\n' else character)
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else {
                result.append(character)
            }
        }
        check(!escaped) { "JSON 字符串末尾转义不完整" }
        return result.toString()
    }

    /** 转义 command.payloadJson，使结构化 payload 作为 JSON 字符串传输。 */
    private fun escapeJson(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    /** 读取 CRLF HTTP 行，用于 WebSocket Upgrade。 */
    private fun readHttpLine(input: InputStream): String {
        val text = StringBuilder()
        var previous = -1
        while (true) {
            val current = input.read()
            if (current < 0) {
                throw EOFException("读取 HTTP 握手时连接关闭")
            }
            if (previous == CR && current == LF) {
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

    private fun readByte(input: InputStream): Int = input.read().takeIf { it >= 0 } ?: throw EOFException("读取 WebSocket 帧时连接关闭")

    private fun webSocketAccept(key: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest((key + WEB_SOCKET_GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(bytes)
    }

    private data class Frame(val opcode: Int, val payload: ByteArray)

    private enum class Stage {
        WAITING,
        MANIFEST,
        ECHO,
        EMIT,
        FAIL,
        SLOW,
        FOLLOW_UP,
    }

    companion object {
        /** 从启动前生成的测试配置读取一次性凭据，并在指定回环端口启动 Worker fixture。 */
        fun startFromProbeConfiguration(pluginsDirectory: File): ProtocolWorkerFixture {
            val config = File(pluginsDirectory, "$SERVER_PROBE_PLUGIN/config.yml")
            val values = config.readText()
            check(configValue(values, "enabled") == "true") { "FR9 测试桥未启用" }
            check(configValue(values, "instance") == INSTANCE_ID) { "FR9 测试实例标识错误" }
            val token = configValue(values, "token")
            val endpoint = URI(configValue(values, "url"))
            check(endpoint.scheme == "ws" && endpoint.host == "127.0.0.1") { "FR9 测试桥地址必须为本机 WS" }
            check(endpoint.port in 1..MAX_PORT && endpoint.path == BRIDGE_PATH) { "FR9 测试桥地址无效" }
            return ProtocolWorkerFixture(ServerSocket(endpoint.port, 1, InetAddress.getLoopbackAddress()), token)
        }

        /** 读取本测试生成的最小 YAML 配置项；配置缺失立即失败，禁止悄然降级。 */
        private fun configValue(content: String, key: String): String = checkNotNull(
            Regex("(?m)^\\s*${Regex.escape(key)}:\\s*\\\"?([^\\\"\\r\\n]+)\\\"?\\s*$").find(content)?.groupValues?.get(1)?.trim(),
        ) { "FR9 测试配置缺少:$key" }

        private const val SERVER_PROBE_PLUGIN = "ServerProbe"
        private const val BRIDGE_PATH = "/ws/plugin-bridge"
        private const val INSTANCE_ID = "e2e-fixture"
        private const val E2E_DOMAIN = "e2e"
        private const val INVENTORY_DOMAIN = "inventory"
        private const val EVENT_DEDUP_KEY = "fixture-event"
        private const val REQUEST_MANIFEST = "e2e-manifest"
        private const val REQUEST_ECHO = "e2e-echo"
        private const val REQUEST_EMIT = "e2e-emit"
        private const val REQUEST_FAIL = "e2e-fail"
        private const val REQUEST_SLOW = "e2e-slow"
        private const val REQUEST_FOLLOW_UP = "e2e-follow-up"
        private const val ECHO_PAYLOAD = "{\"value\":\"round-trip\"}"
        private const val FIXTURE_TIMEOUT_SECONDS = 25L
        private const val FIN = 0x80
        private const val MASK = 0x80
        private const val LENGTH_MASK = 0x7F
        private const val OPCODE_MASK = 0x0F
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA
        private const val LEN_16 = 126
        private const val LEN_64 = 127
        private const val MAX_16 = 0xFFFF
        private const val MASK_BYTES = 4
        private const val LONG_BYTES = 8
        private const val BYTE_BITS = 8
        private const val CR = 13
        private const val LF = 10
        private const val MAX_PORT = 65535
        private const val WEB_SOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private val PING_PAYLOAD = byteArrayOf(0x45, 0x32, 0x45)
    }
}
