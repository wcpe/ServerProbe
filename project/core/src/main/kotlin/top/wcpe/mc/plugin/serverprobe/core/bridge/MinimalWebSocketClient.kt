package top.wcpe.mc.plugin.serverprobe.core.bridge

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger

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
// 手写 WS 协议栈必然要一组小函数（帧头/掩码/读写/握手/跳过载荷），
// 折成更少的大函数会让每一段都失去独立可测性，得不偿失。
@Suppress("TooManyFunctions")
class MinimalWebSocketClient(
    private val uri: URI,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    /** 写侧 watchdog 上限(毫秒);超时强关 socket 解除写阻塞。 */
    private val writeDeadlineMs: Long = DEFAULT_WRITE_DEADLINE_MS,
    /** 入站单帧硬上限(字节);超限按 [FrameTooLargeException] 处理。 */
    private val maxInboundBytes: Int = DEFAULT_MAX_INBOUND_BYTES,
) : OutboundSink {

    /** 底层 TCP 套接字;未连接或已关闭时为 null。 */
    @Volatile
    private var socket: Socket? = null

    /**
     * 当前写操作的开始时刻(epoch 毫秒);0 表示没有在途写。
     *
     * 写侧 watchdog 的观测点。由写线程置位/清零、读线程经 [checkWriteDeadline] 读取,故 `@Volatile`。
     */
    @Volatile
    private var writingSinceMs: Long = 0L

    /**
     * 最近一次收到服务端 pong 的时刻;握手成功时初始化为当前时刻。
     *
     * 握手即初始化是必要的:否则"刚连上、还没收到任何 pong"会被误判为心跳超时。
     */
    @Volatile
    var lastPongAtMs: Long = 0L
        private set

    /**
     * 最近一次主动发出 ping 的时刻;从未发过为 0。
     *
     * 心跳判定必须用它做门控:只有"已发出 ping 且之后没收到 pong"才算超时。
     * 否则连接上持续有下行流量、但 ping 尚未发出时,会被误判为超时。
     */
    @Volatile
    var lastPingSentAtMs: Long = 0L
        private set

    private var input: InputStream? = null
    private var output: DataOutputStream? = null

    /** 写锁:串行化 [sendText]/[sendPing]/close 帧的写出,避免读线程外的心跳线程与之交错。可中断尝试加锁(事件帧非阻塞写)。 */
    private val writeLock = ReentrantLock()

    private val random = SecureRandom()

    /**
     * 建立 TCP 连接并完成 WebSocket 升级握手。
     *
     * 流程:解析 host/port/path → connect(超时) → 发 HTTP Upgrade 请求(含随机 Sec-WebSocket-Key)→
     * 读响应行与头 → 校验 101 + Sec-WebSocket-Accept。任一步失败抛异常(由调用方捕获并退避重连)。
     *
     * @throws BridgeHandshakeException 服务端未返回 101(带状态码,供上层区分认证失败与不可达)。
     * @throws java.io.IOException 连接或握手失败。
     */
    fun connect() {
        require(uri.scheme.equals("ws", ignoreCase = true)) { "仅支持 ws:// (明文回环)，收到 scheme=${uri.scheme}" }
        // 安全:错误消息**不得**包含完整 uri——query 里带 token,会经上层日志落地。
        val host = uri.host ?: error("URL 缺少 host（已省略原文以免泄漏凭据）")
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
            // 抛专用异常:上层据状态码区分「认证失败」(401/403,应快退避重试)与「不可达/服务异常」。
            throw BridgeHandshakeException(parseStatusCode(statusLine), statusLine)
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
        // 握手成功即视作"最近一次心跳正常":避免刚连上就被判超时。
        lastPongAtMs = System.currentTimeMillis()
        lastPingSentAtMs = 0L
    }

    /**
     * 发送一条文本消息(单帧、FIN=1、客户端掩码)。
     *
     * **非阻塞语义(技术债修复)**:以 [tryLock] 尝试取写锁——写锁被占用(如心跳线程正被
     * 慢 flush 卡住)或对端停滞导致 flush 超时,本方法**立即失败丢弃而非阻塞调用线程**:
     * 上行事件(玩家 join/quit 等)由 Worker 侧周期全量补救,丢一条事件远好过卡死主线程
     * (探针"不成事故源"优先于"确保送达")。缓冲区满的 flush 最多阻塞 [WRITE_TIMEOUT_MS]
     * 毫秒,超时判定为连接坏死、主动关闭走重连。
     *
     * @param text UTF-8 文本。
     * @return 是否成功写出;false = 写锁不可得、写超时或连接已坏,消息被丢弃(调用方无需重试)。
     */
    override fun sendText(text: String): Boolean = writeFrameWithTimeout(OPCODE_TEXT, text.toByteArray(Charsets.UTF_8))

    /**
     * 主动发送一个 ping 控制帧(载荷为空)。服务端应回 pong;本客户端不强依赖 pong,
     * 心跳与断线判定由上层结合发送节奏与读超时决定。
     *
     * @throws java.io.IOException 写失败。
     */
    fun sendPing() {
        lastPingSentAtMs = System.currentTimeMillis()
        writeFrame(OPCODE_PING, EMPTY)
    }

    /**
     * 心跳是否已超时（三条件门控）。
     *
     * 只有同时满足以下三条才判超时，缺一不可：
     * 1. **发过 ping**（`lastPingSentAtMs > 0`）——没有依据不判定；
     * 2. **该 ping 尚未被 pong 覆盖**（`lastPongAtMs < lastPingSentAtMs`）；
     * 3. **距离发出已超过 `pongTimeoutMs`**。
     *
     * 为什么必须门控而不是只看"多久没收到 pong"：连接上持续有下行文本帧时读永不超时，
     * 若 ping 只在读超时时发（旧实现），就永远不会发 ping，`lastPongAtMs` 停在握手时刻，
     * 于是"忙但健康"的连接每 `pongTimeoutMs` 被误杀一次。周期性 ping + 门控才能同时覆盖
     * "完全静默"与"忙但无 pong"两种故障。
     *
     * @param nowMs 当前时刻（epoch 毫秒），由调用方传入以便测试注入。
     * @param pongTimeoutMs pong 等待上限；`<= 0` 表示关闭该判定。
     */
    fun isHeartbeatTimedOut(nowMs: Long, pongTimeoutMs: Long): Boolean {
        if (pongTimeoutMs <= 0L) return false
        val pingAt = lastPingSentAtMs
        if (pingAt <= 0L) return false
        if (lastPongAtMs >= pingAt) return false
        return nowMs - pingAt > pongTimeoutMs
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
        val payload = ByteArrayOutputStream()
        var messageOpcode = -1
        while (true) {
            val frame = readFrame(inp)
            when (frame.opcode) {
                OPCODE_PING -> {
                    writeFrame(OPCODE_PONG, frame.data) // 回应 ping
                    continue
                }
                OPCODE_PONG -> {
                    lastPongAtMs = System.currentTimeMillis()
                    continue
                }
                OPCODE_CLOSE -> throw EOFException("收到服务端 close 帧")
                OPCODE_TEXT, OPCODE_CONTINUATION -> {
                    if (frame.opcode == OPCODE_TEXT) messageOpcode = OPCODE_TEXT
                    requireMessageSize(payload.size(), frame.data.size)
                    payload.write(frame.data)
                    if (!frame.fin) continue // 分片未完:继续读续帧
                    if (messageOpcode == OPCODE_TEXT) return payload.toString("UTF-8")
                    // 非文本消息(如二进制):丢弃已积累载荷,继续读下一条
                    payload.reset()
                    messageOpcode = -1
                }
                else -> {
                    // 未知 opcode:忽略该帧,继续
                }
            }
        }
    }

    /** 入站消息累积量护栏(技术债修复:防内存炸弹)——对端用小分片也能堆爆内存,按累积量截断。 */
    private fun requireMessageSize(currentBytes: Int, incomingBytes: Int) {
        if (currentBytes + incomingBytes > MAX_MESSAGE_BYTES) {
            throw java.io.IOException("消息超过大小上限:$currentBytes+$incomingBytes > $MAX_MESSAGE_BYTES")
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
        // 服务端帧通常不掩码；若掩码则读 4 字节掩码键并解掩。
        val maskKey = if (masked) ByteArray(4) { readByte(inp).toByte() } else null
        // 入站硬上限：长度非法或超限时拒绝，绝不直接 ByteArray(len.toInt())。
        // 原实现 len > Int.MAX_VALUE 时 toInt() 溢出为负 → NegativeArraySizeException；
        // len 约 2 GiB 时触发巨型分配 → OutOfMemoryError。两者都会打死桥读线程，
        // 且 runLoop 只 catch Exception 接不住 Error，桥会永久离线。
        // （此上限可经构造参数 maxInboundBytes 覆盖，默认 1 MiB。）
        if (len < 0L || len > maxInboundBytes) {
            // 能安全跳过就跳过(流仍同步、连接可保);跳不动则交由上层重连——
            // 对 2 GiB 的声明长度做跳过,等于把畸形帧放大成带宽消耗。
            throw FrameTooLargeException(
                "入站帧长度非法或超限:$len(上限 $maxInboundBytes)",
                payloadSkipped = skipPayload(inp, len),
            )
        }
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
     * 尽力跳过 [len] 字节载荷,使流保持在帧边界上。
     *
     * 只在长度合法且不超过硬天花板时执行:超限帧的声明长度可能极大,
     * 盲目跳过等于把畸形帧放大成带宽消耗(也是 DoS 面)。
     *
     * @return true=已完整跳过,流仍同步;false=不可安全跳过,调用方应放弃该连接。
     */
    private fun skipPayload(inp: InputStream, len: Long): Boolean {
        if (len < 0L || len > maxInboundBytes.toLong() * SKIP_CEILING_FACTOR) return false
        var remaining = len
        val buffer = ByteArray(SKIP_BUFFER_BYTES)
        while (remaining > 0L) {
            val want = minOf(remaining, buffer.size.toLong()).toInt()
            val n = inp.read(buffer, 0, want)
            if (n < 0) return false
            remaining -= n
        }
        return true
    }

    /**
     * 检查在途写是否已超过 [writeDeadlineMs];超时则强关 socket 解除阻塞。
     *
     * ## 为什么必须强关 socket
     * Java 的 [Socket] **没有写超时**(`soTimeout` 只作用于读)。对端不读(TCP 窗口打满)时
     * `write`/`flush` 会永久阻塞并一直持着 [writeLock]:发送线程挂死 → 命令回执全不发;
     * 心跳在同一把锁上排队 → 对端判定探针失联;连 [close] 也会被写锁挡住。
     * 强关 socket 是 JDK 里唯一可移植的解阻塞手段;代价是可能截断半帧,
     * 由对端按协议丢弃不完整帧并重连。
     *
     * ## 必须在读循环的**每一次**迭代调用
     * 既在读到帧之后、也在读超时之后。只在读超时分支检查是错的——对端"持续下行但停止读取"
     * (最真实的拥塞形态)时读永不超时,写阻塞就永远发现不了。
     *
     * @return true=检测到写超时并已关闭连接。
     */
    fun checkWriteDeadline(): Boolean {
        val startedAt = writingSinceMs
        if (startedAt <= 0L) return false
        if (System.currentTimeMillis() - startedAt <= writeDeadlineMs) return false
        forceCloseSocket()
        return true
    }

    /**
     * 关闭连接(幂等)。
     *
     * **不写 close 帧、不取写锁**:写线程可能正卡在 [writeFrameLocked] 上持着写锁,
     * 若 close 去抢锁就会一起卡死(正是本次要修的问题)。直接关 socket 让写线程抛异常退出;
     * 对端看到 EOF 会按协议丢弃连接。"先关 socket 再发 close 帧"也不成立——
     * socket 关闭后输出流已失效。
     */
    fun close() {
        forceCloseSocket()
    }

    /** 强制关闭底层 socket 并清空句柄(不写帧、不取写锁)。 */
    private fun forceCloseSocket() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        writingSinceMs = 0L
    }

    /** 当前是否处于已连接状态(socket 未关闭)。 */
    fun isConnected(): Boolean = socket?.isClosed == false

    /** 写一帧(对外接口,加写锁)。写前后维护 [writingSinceMs] 供 watchdog 观测。 */
    private fun writeFrame(opcode: Int, data: ByteArray) {
        writeLock.lock()
        try {
            writeFrameLocked(opcode, data)
        } finally {
            writeLock.unlock()
        }
    }

    /**
     * 带超时的非阻塞写(事件帧用):tryLock 失败立即返回 false;取到锁后以写超时执行,
     * 超时判定连接坏死并关闭(由读循环的 EOF 兜底触发重连)。控制帧(ping/close)仍走
     * [writeFrame] 阻塞路径——它们在锁上等待是可接受的(低频、且必须发出)。
     */
    private fun writeFrameWithTimeout(opcode: Int, data: ByteArray): Boolean {
        val locked = writeLock.tryLock(WRITE_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!locked) return false
        try {
            // 借 socket 写超时实现"flush 至多等 WRITE_TIMEOUT_MS":临时收紧 soTimeout,
            // 写完恢复读超时——Java 对 OutputStream 无独立写超时,这是最小侵入的近似。
            val s = socket ?: return false
            val original = s.soTimeout
            s.soTimeout = WRITE_TIMEOUT_MS
            try {
                writeFrameLocked(opcode, data)
                return true
            } catch (e: java.net.SocketTimeoutException) {
                // 写超时 = 对端停滞/连接坏死:记日志(不吞异常语义)、主动关闭,读循环会因流结束进入重连
                ProbeLogger.warn("插件桥写出超时,判定连接坏死并重连:${e.message}")
                runCatching { close() }
                return false
            } finally {
                runCatching { s.soTimeout = original }
            }
        } finally {
            writeLock.unlock()
        }
    }

    /** 从状态行解析 HTTP 状态码；解析失败返回 -1（上层归为未知原因）。 */
    private fun parseStatusCode(statusLine: String): Int =
        statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1

    /** 写一帧(已持写锁):FIN=1 + opcode,客户端掩码位置 1 + 4 字节随机掩码键,载荷按键异或。 */
    private fun writeFrameLocked(opcode: Int, data: ByteArray) {
        val out = output ?: throw EOFException("连接未建立或已关闭")
        // 写侧 watchdog 的观测点：两条写路径（阻塞的 [writeFrame] 与非阻塞的
        // [writeFrameWithTimeout]）都经过这里，故在此统一置位/清零。
        // 置位必须包住 flush（真正的阻塞点），否则 watchdog 观测不到对端不读导致的卡死。
        writingSinceMs = System.currentTimeMillis()
        try {
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
        } finally {
            writingSinceMs = 0L
        }
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

        /** 入站单条消息(含分片累积)大小上限(字节);与帧上限双保险,小帧分片同样被截断。 */
        private const val MAX_MESSAGE_BYTES = 1 * 1024 * 1024

        /** 事件帧写出超时(毫秒):flush 卡住超过此时长判定连接坏死,丢弃消息并断线重连。 */
        private const val WRITE_TIMEOUT_MS = 1_000

        /** 写侧 watchdog 默认上限(毫秒):超时强关 socket 解除写阻塞。 */
        private const val DEFAULT_WRITE_DEADLINE_MS = 10_000L

        /** 入站单帧硬上限默认值(字节)。 */
        private const val DEFAULT_MAX_INBOUND_BYTES = 1_048_576

        /** 可安全跳过的载荷上限 = 帧上限 × 此系数(超过则放弃连接而非盲跳)。 */
        private const val SKIP_CEILING_FACTOR = 4L

        /** 跳过载荷时使用的读缓冲区大小(字节)。 */
        private const val SKIP_BUFFER_BYTES = 8_192

        private val EMPTY = ByteArray(0)
    }
}

/**
 * 入站帧长度非法或超过上限。
 *
 * 与普通 IO 错误分开,是为了让读循环能区分处置:
 * - [payloadSkipped] 为 true:载荷已被完整跳过、流仍同步 → **丢弃该帧并继续读**(连接保持);
 * - 为 false:流已不可同步(或声明长度大到不该硬跳)→ 由上层断开重连。
 *
 * 继承 [java.io.IOException] 以沿用上层既有的 IO 降级路径。
 */
class FrameTooLargeException(
    message: String,
    val payloadSkipped: Boolean,
) : java.io.IOException(message)

/**
 * WebSocket 握手被服务端拒绝（未返回 101）。
 *
 * 单独成类而非复用 [java.io.IOException]，是为了让上层能用**状态码**区分处置：
 * - `401` / `403` → 认证失败（token 无效或已轮换），应采用较短的退避以便轮换后快速恢复；
 * - 其余 4xx/5xx → 视为服务不可用，走常规退避。
 *
 * 注意 [statusLine] 可能包含服务端返回的文本，**不得**原样写入对外回执或状态字段
 * （只记状态码，文本仅用于 debug 日志且需脱敏）。
 */
class BridgeHandshakeException(
    val statusCode: Int,
    val statusLine: String,
) : java.io.IOException("WebSocket 握手被拒绝:状态码 $statusCode")

/**
 * 心跳超时（已发出 ping 且在 [pongTimeoutMs] 内未收到 pong）。
 *
 * **不得**继承 [java.io.EOFException]：`BridgeClient.runLoop` 会先 `catch (EOFException)`
 * 把它归因为"对端关闭"，继承关系会导致心跳超时被误报成 PEER_CLOSED。
 */
class HeartbeatTimeout(message: String) : java.io.IOException(message)
