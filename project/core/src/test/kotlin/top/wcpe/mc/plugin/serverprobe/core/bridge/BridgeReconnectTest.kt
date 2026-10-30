package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 连接自愈与心跳判定测试。
 *
 * 覆盖三条最容易写错的不变量：
 * - 心跳超时**必须门控**（发过 ping + pong 未覆盖 + 超时），否则忙连接会被周期性误杀；
 * - 握手状态码要能区分**认证失败**与**服务不可用**（退避策略不同）；
 * - **token 不得进异常消息**（会经上层日志落地）。
 */
class BridgeReconnectTest {

    /**
     * 未发过 ping 时不得判超时——否则刚连上、或对端忙但健康时会被误杀。
     */
    @Test
    fun `未发 ping 时不判心跳超时`() {
        val client = newClient()
        // lastPingSentAtMs 为 0
        assertFalse(client.isHeartbeatTimedOut(System.currentTimeMillis() + 1_000_000, 1_000), "未发 ping 不应判定超时")
    }

    /**
     * 已发 ping 但 pong 尚未到达、且超过阈值 → 判超时。
     *
     * 这里用一条**真实时序**构造“pong 未覆盖该 ping”：握手成功时把 pong 时刻初始化为当下，
     * 然后等过几毫秒再发 ping，于是 `lastPongAtMs < pingAt`。
     * （早先实现是反射把 `lastPongAtMs` 写小；现在不需要摸生产代码的私有字段了。）
     */
    @Test
    fun `已发 ping 且 pong 未到超时则判定`() {
        val client = connectedClient()
        // 跨过几毫秒，保证 ping 时刻晚于握手初始化的 pong 时刻
        Thread.sleep(5)
        client.sendPing()
        val pingAt = client.lastPingSentAtMs
        assertTrue(pingAt > 0, "sendPing 应记录发出时刻")
        assertTrue(
            client.isHeartbeatTimedOut(pingAt + 1_001, 1_000),
            "发出 ping 后超过阈值仍未收到 pong 应判超时",
        )
    }

    /**
     * pong 已覆盖该 ping 时不判超时——即使时间过去很久。
     *
     * 这里让测试服务端真的回一帧 **pong**（再加一帧文本，好让 [MinimalWebSocketClient.readMessage] 返回）：
     * 读循环消化 pong 时会把 `lastPongAtMs` 推到当下，于是“已覆盖”是它自己走出来的状态，而非被写进去的。
     */
    @Test
    fun `pong 覆盖后不判超时`() {
        val server = PongThenTextServer(FINISH_TEXT)
        val client = connectTo(server)
        client.sendPing()
        val pingAt = client.lastPingSentAtMs

        assertEquals(FINISH_TEXT, client.readMessage(), "读循环应消化 pong 后返回文本")
        assertFalse(
            client.isHeartbeatTimedOut(pingAt + 10_000_000, 1_000),
            "最近一次 ping 已被 pong 覆盖,不应判超时",
        )
        client.close()
    }

    /** `pongTimeoutMs <= 0` 表示关闭判定（兼容"对端不回 pong"的部署）。 */
    @Test
    fun `关闭心跳判定时不报警`() {
        val client = connectedClient()
        client.sendPing()
        assertFalse(
            client.isHeartbeatTimedOut(System.currentTimeMillis() + 10_000_000, 0),
            "pongTimeoutMs=0 应关闭判定",
        )
    }

    /** 握手被 401 拒绝时抛专用异常并带状态码。 */
    @Test
    fun `401 握手抛认证失败异常`() {
        val error = handshakeAgainst { output ->
            output.write("HTTP/1.1 401 Unauthorized\r\n\r\n".toByteArray())
            output.flush()
        }
        assertTrue(error is BridgeHandshakeException, "应抛 BridgeHandshakeException,实际=$error")
        assertEquals(401, (error as BridgeHandshakeException).statusCode)
    }

    /** 握手被 503 拒绝时同样带状态码（上层归为不可达而非认证失败）。 */
    @Test
    fun `503 握手带状态码`() {
        val error = handshakeAgainst { output ->
            output.write("HTTP/1.1 503 Service Unavailable\r\n\r\n".toByteArray())
            output.flush()
        }
        assertTrue(error is BridgeHandshakeException, "应抛 BridgeHandshakeException,实际=$error")
        assertEquals(503, (error as BridgeHandshakeException).statusCode)
    }

    /**
     * 异常消息**不得包含 token**。
     *
     * token 在 query 里；若异常消息带完整 URI，就会被上层 `warn(...${e.message})` 写进日志。
     */
    @Test
    fun `握手失败消息不泄漏 token`() {
        val secret = "SECRET-TOKEN-abc123"
        val error = handshakeAgainst("token=$secret&instance=x") { output ->
            output.write("HTTP/1.1 401 Unauthorized\r\n\r\n".toByteArray())
            output.flush()
        }
        val text = (error?.message ?: "") + (error?.toString() ?: "")
        assertFalse(text.contains(secret), "异常消息不得包含 token,实际=$text")
    }

    /** 握手成功时初始化 lastPongAtMs，避免"刚连上就超时"。 */
    @Test
    fun `握手成功初始化 pong 时刻`() {
        val client = connectedClient()
        assertTrue(client.lastPongAtMs > 0, "握手成功应初始化 lastPongAtMs")
        assertEquals(0L, client.lastPingSentAtMs, "握手后尚未发 ping")
        assertFalse(client.isHeartbeatTimedOut(System.currentTimeMillis(), 1_000), "刚连上不应超时")
    }

    /** 未连接的客户端。 */
    private fun newClient(): MinimalWebSocketClient = MinimalWebSocketClient(
        uri = java.net.URI("ws://127.0.0.1:1/ws"),
        connectTimeoutMs = 100,
        readTimeoutMs = 100,
    )

    /** 通过真实握手建立连接（连到测试内嵌的最小 WS 服务端）。 */
    private fun connectedClient(): MinimalWebSocketClient {
        val server = ServerSocket(0)
        val peer = Thread {
            runCatching {
                server.accept().use { socket ->
                    completeHandshake(socket)
                    Thread.sleep(2_000)
                }
            }
        }.apply { isDaemon = true }
        peer.start()
        val client = MinimalWebSocketClient(
            uri = java.net.URI("ws://127.0.0.1:${server.localPort}/ws"),
            connectTimeoutMs = 2_000,
            readTimeoutMs = 200,
        )
        client.connect()
        server.close()
        return client
    }

    /**
     * 一个握手后主动推帧的小服务端：先回 [finishText] 前的一帧 **pong**，再回一帧文本。
     *
     * 用真实帧而不去改客户端内部字段：pong 帧是 `lastPongAtMs` 的唯一合法来源，
     * 让它走真实读循环，测的才是“心跳判定靠帧驱动”这个真正的行为。
     */
    private class PongThenTextServer(private val finishText: String) : AutoCloseable {

        private val server = ServerSocket(0)
        private val peer: Thread

        init {
            peer = Thread {
                runCatching {
                    server.accept().use { socket ->
                        completeHandshake(socket)
                        val out = DataOutputStream(socket.getOutputStream())
                        writeServerFrame(out, OPCODE_PONG, ByteArray(0))
                        writeServerFrame(out, OPCODE_TEXT, finishText.toByteArray(Charsets.UTF_8))
                        Thread.sleep(500)
                    }
                }
            }.apply { isDaemon = true }
            peer.start()
        }

        fun uri(): java.net.URI = java.net.URI("ws://127.0.0.1:${server.localPort}/ws")

        override fun close() {
            runCatching { server.close() }
        }

        /** 服务端→客户端的帧：不掩码、FIN=1，载荷长度走 7 位或 16 位分支。 */
        private fun writeServerFrame(out: DataOutputStream, opcode: Int, payload: ByteArray) {
            out.writeByte(0x80 or opcode)
            if (payload.size <= 125) {
                out.writeByte(payload.size)
            } else {
                out.writeByte(126)
                out.writeByte((payload.size shr 8) and 0xFF)
                out.writeByte(payload.size and 0xFF)
            }
            out.write(payload)
            out.flush()
        }

        /** 完成最小 WS 握手（回 101 + 正确 accept）。 */
        private fun completeHandshake(socket: Socket) {
            val input = BufferedInputStream(socket.getInputStream())
            val request = readHandshakeRequest(input)
            val key = request.lineSequence()
                .firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()
                ?: error("缺少 Sec-WebSocket-Key")
            val accept = Base64.getEncoder().encodeToString(
                java.security.MessageDigest.getInstance("SHA-1")
                    .digest((key + WS_GUID).toByteArray(Charsets.US_ASCII)),
            )
            val output = DataOutputStream(socket.getOutputStream())
            output.write(
                (
                    "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII),
            )
            output.flush()
        }

        /** 读完握手请求头（到空行为止）。 */
        private fun readHandshakeRequest(input: java.io.InputStream): String {
            val text = StringBuilder()
            // 读到首个空行为止：空行即"头部结束"与"输入已结束"的同一个终止条件。
            @Suppress("LoopWithTooManyJumpStatements")
            while (true) {
                val line = readLine(input) ?: break
                text.append(line).append('\n')
                if (line.isEmpty()) break
            }
            return text.toString()
        }

        /** 读一行（\r\n 结尾），EOF 返回 null。 */
        private fun readLine(input: java.io.InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (line.isEmpty()) null else line.toString()
                if (b == '\n'.code) return line.toString().trimEnd('\r')
                line.append(b.toChar())
            }
        }
    }

    /** 连到给定的测试服务端（握手由服务端自己完成）。 */
    private fun connectTo(server: PongThenTextServer): MinimalWebSocketClient {
        val client = MinimalWebSocketClient(server.uri(), 2_000, 2_000)
        client.connect()
        return client
    }

    /** 对着一个只回指定响应的服务端做握手，返回异常。 */
    private fun handshakeAgainst(
        query: String? = null,
        respond: (DataOutputStream) -> Unit,
    ): Throwable? {
        ServerSocket(0).use { server ->
            val done = CountDownLatch(1)
            val peer = Thread {
                runCatching {
                    server.accept().use { socket ->
                        readHandshakeRequest(BufferedInputStream(socket.getInputStream()))
                        respond(DataOutputStream(socket.getOutputStream()))
                        done.countDown()
                        Thread.sleep(300)
                    }
                }
            }.apply { isDaemon = true }
            peer.start()

            val target = if (query == null) {
                java.net.URI("ws://127.0.0.1:${server.localPort}/ws")
            } else {
                java.net.URI("ws://127.0.0.1:${server.localPort}/ws?$query")
            }
            val client = MinimalWebSocketClient(target, 2_000, 200)
            return try {
                client.connect()
                null
            } catch (e: Throwable) {
                e
            } finally {
                done.await(1, TimeUnit.SECONDS)
                client.close()
            }
        }
    }

    /** 读完握手请求头（到空行为止）。 */
    private fun readHandshakeRequest(input: java.io.InputStream): String {
        val text = StringBuilder()
        // 读到首个空行为止：空行即"头部结束"与"输入已结束"的同一个终止条件。
        @Suppress("LoopWithTooManyJumpStatements")
        while (true) {
            val line = readLine(input) ?: break
            text.append(line).append('\n')
            if (line.isEmpty()) break
        }
        return text.toString()
    }

    /** 完成最小 WS 握手（回 101 + 正确 accept）。 */
    private fun completeHandshake(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val request = readHandshakeRequest(input)
        val key = request.lineSequence()
            .firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
            ?: error("缺少 Sec-WebSocket-Key")
        val accept = Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
        )
        val output = DataOutputStream(socket.getOutputStream())
        output.write(
            (
                "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII),
        )
        output.flush()
    }

    /** 读一行（\r\n 结尾），EOF 返回 null。 */
    private fun readLine(input: java.io.InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            line.append(b.toChar())
        }
    }

    private companion object {
        /** 服务端的帧不掩码，opcode 直接写在低 4 位。 */
        const val OPCODE_TEXT = 0x1
        const val OPCODE_PONG = 0xA

        /** RFC 6455 握手魔数。 */
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** “读循环已处理完 pong”的哨兵文本。 */
        const val FINISH_TEXT = "pong-digested"
    }
}
