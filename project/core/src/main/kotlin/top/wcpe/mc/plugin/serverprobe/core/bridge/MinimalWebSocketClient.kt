package top.wcpe.mc.plugin.serverprobe.core.bridge

import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 最小 WebSocket 客户端(RFC 6455),**JDK 8 兼容、零第三方依赖**。
 *
 * 探针锁 Java 8,不能用 JDK 11 的 `java.net.http.WebSocket`;又遵循探针「导出/外联只用 JDK 自带、
 * 不引第三方库」的既有约定(见 PrometheusExporter 用 `com.sun.net.httpserver`、WebhookAlertChannel 用
 * `HttpURLConnection`)。故在裸 [Socket] 之上手写握手与帧编解码,仅覆盖插件桥所需子集:
 *
 * - 仅 `ws://`(明文);探针与 Worker 同机走本机回环,无需 TLS。
 * - 仅发送/接收**文本帧**(opcode 0x1);控制帧支持 ping(0x9)/pong(0xA)/close(0x8)。
 * - 客户端发出的帧**必须掩码**(RFC 6455 §5.3);服务端帧不掩码,读取时据 mask 位处理。
 * - 不做分片发送(单帧 FIN=1);接收侧支持续帧拼接以兼容服务端可能的分片。
 *
 * ## 用法
 * 同步阻塞式:[connect] 完成握手;[sendText] 发文本;[readMessage] 阻塞读下一条文本消息
 * (内部自动回应 ping、消化 pong/close)。由调用方([top.wcpe.mc.plugin.serverprobe.core.bridge.BridgeClient])
 * 在独立线程里跑读循环并管理重连。本类不持有重连/心跳策略,仅做一条连接的 IO。
 *
 * ## 线程模型
 * 单连接非线程安全:读在调用方的读线程;写([sendText]/[sendPing])经 [writeLock] 串行化,
 * 允许读线程之外的心跳线程并发写。[close] 幂等。
 */
class MinimalWebSocketClient(
    private val uri: URI,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
) {

    /** 底层 TCP 套接字;未连接或已关闭时为 null。 */
    @Volatile
    private var socket: Socket? = null

    private var input: InputStream? = null
    private var output: DataOutputStream? = null

    /** 写锁:串行化 [sendText]/[sendPing]/close 帧的写出,避免读线程外的心跳线程与之交错。 */
    private val writeLock = Any()

    private val random = SecureRandom()

    /**
     * 建立 TCP 连接并完成 WebSocket 升级握手。
     *
     * 流程:解析 host/port/path → connect(超时) → 发 HTTP Upgrade 请求(含随机 Sec-WebSocket-Key)→
     * 读响应行与头 → 校验 101 + Sec-WebSocket-Accept。任一步失败抛异常(由调用方捕获并退避重连)。
     *
     * @throws java.io.IOException 连接或握手失败。
     */
    fun connect() {
        require(uri.scheme.equals("ws", ignoreCase = true)) { "仅支持 ws:// (明文回环)，收到 scheme=${uri.scheme}" }
        val host = uri.host ?: error("URL 缺少 host: $uri")
        val port = if (uri.port > 0) uri.port else DEFAULT_WS_PORT
        val path = buildString {
            append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
            if (!uri.rawQuery.isNullOrEmpty()) append('?').append(uri.rawQuery)
        }

        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = readTimeoutMs
        val out = DataOutputStream(s.getOutputStream())
        val inp = BufferedInputStream(s.getInputStream())

        val key = generateKey()
        val req = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append(':').append(port).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }
        out.write(req.toByteArray(Charsets.US_ASCII))
        out.flush()

        val statusLine = readHttpLine(inp)
        if (!statusLine.startsWith("HTTP/1.1 101") && !statusLine.startsWith("HTTP/1.0 101")) {
            runCatching { s.close() }
            throw java.io.IOException("WebSocket 握手失败，服务端未返回 101：$statusLine")
        }
        // 读取并校验响应头中的 Sec-WebSocket-Accept;消化至空行(头结束)。
        var accept: String? = null
        while (true) {
            val line = readHttpLine(inp)
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (name.equals("Sec-WebSocket-Accept", ignoreCase = true)) accept = value
            }
        }
        val expected = computeAccept(key)
        if (accept != expected) {
            runCatching { s.close() }
            throw java.io.IOException("WebSocket 握手校验失败：Sec-WebSocket-Accept 不匹配")
        }

        socket = s
        input = inp
        output = out
    }

    /**
     * 发送一条文本消息(单帧、FIN=1、客户端掩码)。
     *
     * @param text UTF-8 文本。
     * @throws java.io.IOException 连接已关闭或写失败。
     */
    fun sendText(text: String) {
        writeFrame(OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))
    }

    /**
     * 主动发送一个 ping 控制帧(载荷为空)。服务端应回 pong;本客户端不强依赖 pong,
     * 心跳与断线判定由上层结合发送节奏与读超时决定。
     *
     * @throws java.io.IOException 写失败。
     */
    fun sendPing() {
        writeFrame(OPCODE_PING, EMPTY)
    }

    /**
     * 阻塞读取下一条**文本消息**,期间自动:对收到的 ping 回 pong、消化 pong、遇 close 抛 [EOFException]。
     * 支持续帧拼接(服务端分片)。读超时由 socket soTimeout 控制,超时抛 [java.net.SocketTimeoutException]
     * (上层据此与心跳节奏判断是否断线)。
     *
     * @return 下一条文本消息内容(UTF-8)。
     * @throws EOFException 连接关闭(收到 close 帧或流结束)。
     * @throws java.io.IOException 读失败。
     */
    // opcode 分发循环:控制帧(ping/pong)与未完成分片天然需 continue 跳过续读、return 仅在完整文本消息
    // 就绪时——这是 RFC 6455 帧循环的固有语义,强拆只会割裂可读性,故仅此一处豁免循环跳转检查。
    @Suppress("LoopWithTooManyJumpStatements")
    fun readMessage(): String {
        val inp = input ?: throw EOFException("连接未建立或已关闭")
        val payload = ArrayList<Byte>()
        var messageOpcode = -1
        while (true) {
            val frame = readFrame(inp)
            when (frame.opcode) {
                OPCODE_PING -> {
                    writeFrame(OPCODE_PONG, frame.data) // 回应 ping
                    continue
                }
                OPCODE_PONG -> continue // 消化 pong
                OPCODE_CLOSE -> throw EOFException("收到服务端 close 帧")
                OPCODE_TEXT, OPCODE_CONTINUATION -> {
                    if (frame.opcode == OPCODE_TEXT) messageOpcode = OPCODE_TEXT
                    payload.addAll(frame.data.asList())
                    if (!frame.fin) continue // 分片未完:继续读续帧
                    if (messageOpcode == OPCODE_TEXT) return String(payload.toByteArray(), Charsets.UTF_8)
                    // 非文本消息(如二进制):丢弃已积累载荷,继续读下一条
                    payload.clear()
                    messageOpcode = -1
                }
                else -> {
                    // 未知 opcode:忽略该帧,继续
                }
            }
        }
    }

    /** 一个已读入并解掩的 WebSocket 帧:FIN 标志 + opcode + 载荷。 */
    private class WsFrame(val fin: Boolean, val opcode: Int, val data: ByteArray)

    /**
     * 读一个完整 WebSocket 帧:帧头(FIN+opcode)+ 长度(7/16/64 位三档)+ 可选掩码键 + 载荷,
     * 返回已解掩的帧。把帧的字节级 I/O 从 [readMessage] 的 opcode 分发循环中分离以降复杂度。
     */
    private fun readFrame(inp: InputStream): WsFrame {
        val b0 = readByte(inp)
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val b1 = readByte(inp)
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()
        when (len.toInt()) {
            LEN_16 -> len = readUnsigned(inp, 2)
            LEN_64 -> len = readUnsigned(inp, 8)
        }
        // 服务端帧通常不掩码;若掩码则读 4 字节掩码键并解掩。
        val maskKey = if (masked) ByteArray(4) { readByte(inp).toByte() } else null
        val data = ByteArray(len.toInt())
        var read = 0
        while (read < data.size) {
            val n = inp.read(data, read, data.size - read)
            if (n < 0) throw EOFException("读取帧载荷时连接关闭")
            read += n
        }
        if (maskKey != null) {
            for (i in data.indices) data[i] = (data[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
        return WsFrame(fin, opcode, data)
    }

    /**
     * 关闭连接(幂等)。尽力发一个 close 帧后关闭 socket,异常吞并。
     */
    fun close() {
        val s = socket ?: return
        runCatching {
            synchronized(writeLock) { writeFrameLocked(OPCODE_CLOSE, EMPTY) }
        }
        runCatching { s.close() }
        socket = null
        input = null
        output = null
    }

    /** 当前是否处于已连接状态(socket 未关闭)。 */
    fun isConnected(): Boolean = socket?.isClosed == false

    /** 写一帧(对外接口,加写锁)。 */
    private fun writeFrame(opcode: Int, data: ByteArray) {
        synchronized(writeLock) { writeFrameLocked(opcode, data) }
    }

    /**
     * 写一帧(已持写锁):FIN=1 + opcode,客户端掩码位置 1 + 4 字节随机掩码键,载荷按键异或。
     * 长度按 RFC 6455 分 7 位 / 16 位 / 64 位三档编码。
     */
    private fun writeFrameLocked(opcode: Int, data: ByteArray) {
        val out = output ?: throw EOFException("连接未建立或已关闭")
        out.writeByte(0x80 or opcode) // FIN=1
        val len = data.size
        when {
            len < LEN_16 -> out.writeByte(0x80 or len) // MASK=1 + len
            len <= MAX_16 -> {
                out.writeByte(0x80 or LEN_16)
                out.writeByte((len ushr 8) and 0xFF)
                out.writeByte(len and 0xFF)
            }
            else -> {
                out.writeByte(0x80 or LEN_64)
                // 高 4 字节为 0(载荷远不及 4GB)
                for (i in 0 until 4) out.writeByte(0)
                out.writeByte((len ushr 24) and 0xFF)
                out.writeByte((len ushr 16) and 0xFF)
                out.writeByte((len ushr 8) and 0xFF)
                out.writeByte(len and 0xFF)
            }
        }
        val mask = ByteArray(4).also { random.nextBytes(it) }
        out.write(mask)
        val masked = ByteArray(len)
        for (i in 0 until len) masked[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
        out.write(masked)
        out.flush()
    }

    /** 生成 16 字节随机 Sec-WebSocket-Key 的 Base64。 */
    private fun generateKey(): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** 按 RFC 6455 计算期望的 Sec-WebSocket-Accept = base64(sha1(key + GUID))。 */
    private fun computeAccept(key: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val digest = sha1.digest((key + WS_GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest)
    }

    /** 读一行 HTTP 文本(以 CRLF 结尾,返回不含 CRLF);用于读握手响应行与头。 */
    // 读 CRLF 行天然有两个跳出点(流结束 / 行结束 CRLF),拆分无益,故豁免循环跳转检查。
    @Suppress("LoopWithTooManyJumpStatements")
    private fun readHttpLine(inp: InputStream): String {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val c = inp.read()
            if (c < 0) {
                if (sb.isEmpty()) throw EOFException("读取 HTTP 响应时连接关闭")
                break
            }
            if (prev == '\r'.code && c == '\n'.code) {
                sb.setLength(sb.length - 1) // 去掉末尾 \r
                break
            }
            sb.append(c.toChar())
            prev = c
        }
        return sb.toString()
    }

    /** 读一个无符号字节(0..255)。 */
    private fun readByte(inp: InputStream): Int {
        val c = inp.read()
        if (c < 0) throw EOFException("读取字节时连接关闭")
        return c
    }

    /** 读 n 字节大端无符号整数(用于 16/64 位帧长)。 */
    private fun readUnsigned(inp: InputStream, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or readByte(inp).toLong()
        return v
    }

    private companion object {

        /** WebSocket 握手魔术 GUID(RFC 6455 §4.2.2)。 */
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        private const val DEFAULT_WS_PORT = 80

        private const val OPCODE_CONTINUATION = 0x0
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA

        /** 7 位长度档的哨兵值:126 表示随后 16 位长度,127 表示随后 64 位长度。 */
        private const val LEN_16 = 126
        private const val LEN_64 = 127

        /** 16 位长度档的最大字节数。 */
        private const val MAX_16 = 0xFFFF

        private val EMPTY = ByteArray(0)
    }
}
