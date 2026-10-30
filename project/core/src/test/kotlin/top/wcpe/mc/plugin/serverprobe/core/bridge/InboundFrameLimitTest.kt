package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64

/**
 * 入站帧长度防护测试（线路治理）。
 *
 * 原实现的 `ByteArray(len.toInt())` 有两处致命缺陷，本测试确保它们不会回归：
 * - `len > Int.MAX_VALUE` 时 `toInt()` 溢出为负 → `NegativeArraySizeException`；
 * - `len` 约 2 GiB 时巨型分配 → `OutOfMemoryError`（`runLoop` 只 catch `Exception` 接不住）。
 *
 * 两者都会打死桥读线程，且桥会永久离线。
 *
 * <h2>为什么不靠反射</h2>
 * <p>以前这里是反射把 `input` 字段换成 `ByteArrayInputStream`，理由是“`readFrame` 没有对外入口”。
 * 但真正要验的并不是“能不能注入流”，而是**线上真的来了这么一帧会怎样**。
 * 现在直接起一个只会握手、然后吐出构造帧的本地 WS 服务端，让客户端走自己的 `connect()`
 * 建好真实连接后再读——线路上是什么，就是它看到什么，一个私有字段都不碰。</p>
 */
class InboundFrameLimitTest {

    /**
     * 构造一个带帧头的服务端帧（未掩码），声明长度由参数指定。
     *
     * 注意：分支判断用 `declared in 0..125` 而非 `declared < 126`——负数会落进前者，
     * 导致只写 1 字节长度而进入错误分支（测试自身会因此失真）。
     */
    private fun frameWithDeclaredLength(declared: Long, actualPayload: ByteArray): ByteArray {
        val header = ByteArrayOutputStream()
        header.write(0x81) // FIN=1, opcode=text
        when {
            declared in 0L..125L -> header.write(declared.toInt())
            declared in 126L..0xFFFFL -> {
                header.write(126)
                header.write(((declared shr 8) and 0xFF).toInt())
                header.write((declared and 0xFF).toInt())
            }
            else -> {
                // 含负数(高位全 1)与 > 0xFFFF 的大长度,统一走 64 位分支
                header.write(127)
                for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
                    header.write(((declared ushr shift) and 0xFF).toInt())
                }
            }
        }
        header.write(actualPayload)
        return header.toByteArray()
    }

    /**
     * 载荷可跳过时,异常标记 `payloadSkipped=true`（读循环据此保持连接）。
     */
    @Test
    fun `超限但可跳过时标记为可恢复`() {
        val limit = 32
        // 声明长度 = limit+1(在 4 倍上限内),实际提供完整载荷
        val payload = ByteArray(limit + 1) { 'a'.code.toByte() }
        val bytes = frameWithDeclaredLength((limit + 1).toLong(), payload)

        val error = readError(bytes, maxInboundBytes = limit)
        assertTrue(error is FrameTooLargeException, "应抛 FrameTooLargeException,实际=$error")
        assertTrue((error as FrameTooLargeException).payloadSkipped, "载荷可跳过时应标记 payloadSkipped=true")
    }

    /**
     * 声明长度大到不该硬跳时,标记 `payloadSkipped=false`（读循环据此重连）。
     *
     * 这条防的是"把畸形帧放大成带宽消耗"：对 2 GiB 声明长度做跳过等于 DoS 放大器。
     */
    @Test
    fun `超限且不可跳过时标记为不可恢复`() {
        val limit = 32
        // 声明长度远超 4 倍上限
        val declared = limit.toLong() * 8
        val bytes = frameWithDeclaredLength(declared, ByteArray(0))

        val error = readError(bytes, maxInboundBytes = limit)
        assertTrue(error is FrameTooLargeException, "应抛 FrameTooLargeException,实际=$error")
        assertFalse((error as FrameTooLargeException).payloadSkipped, "声明长度过大时不应尝试跳过")
    }

    /** 长度字段溢出为负(64 位高位全 1)时也必须拒绝,且不得抛 NegativeArraySizeException。 */
    @Test
    fun `长度溢出为负被拒绝`() {
        // 必须走 64 位长度分支(127 哨兵 + 8 字节 0xFF),不能用 declared<126 的分支。
        val bytes = frameWithDeclaredLength(-1L, ByteArray(0))
        val error = readError(bytes, maxInboundBytes = 1024)
        assertTrue(error is FrameTooLargeException, "负数长度应抛 FrameTooLargeException,实际=$error")
        assertFalse((error as FrameTooLargeException).payloadSkipped, "负长度不可跳过")
    }

    /** 正常帧不受影响。 */
    @Test
    fun `正常帧可读取`() {
        val payload = "hello".toByteArray()
        val bytes = frameWithDeclaredLength(payload.size.toLong(), payload)
        assertEquals("hello", readMessage(bytes, maxInboundBytes = 1024))
    }

    /** 驱动一次读取,返回抛出的异常（无异常返回 null）。 */
    private fun readError(bytes: ByteArray, maxInboundBytes: Int): Throwable? = try {
        readMessage(bytes, maxInboundBytes)
        null
    } catch (e: Throwable) {
        e
    }

    /** 从真实连接上读一条消息。 */
    private fun readMessage(bytes: ByteArray, maxInboundBytes: Int): String = RawFrameServer(bytes).use { server ->
        val client = MinimalWebSocketClient(server.uri(), 2_000, 2_000, maxInboundBytes = maxInboundBytes)
        client.connect()
        try {
            client.readMessage()
        } finally {
            client.close()
        }
    }

    /**
     * 握手后把预先构造好的字节原样写到线上的最小 WS 服务端。
     *
     * 只写不读：读完发起方握手后就把 [frameBytes] 吐出去，让客户端的真实读循环去处理。
     */
    private class RawFrameServer(private val frameBytes: ByteArray) : AutoCloseable {

        private val server = ServerSocket(0)

        init {
            Thread {
                runCatching {
                    server.accept().use { socket ->
                        completeHandshake(socket)
                        val out = DataOutputStream(socket.getOutputStream())
                        out.write(frameBytes)
                        out.flush()
                        // 给客户端留出读完的时间，避免提前关连接变成 EOF 而不是长度异常。
                        Thread.sleep(500)
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        fun uri(): java.net.URI = java.net.URI("ws://127.0.0.1:${server.localPort}/ws")

        override fun close() {
            runCatching { server.close() }
        }

        /** 完成最小 WS 握手（回 101 + 正确 accept）。 */
        private fun completeHandshake(socket: Socket) {
            val input = BufferedInputStream(socket.getInputStream())
            val key = readHandshakeRequest(input).lineSequence()
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
        private fun readHandshakeRequest(input: InputStream): String {
            val text = StringBuilder()
            // 读到首个空行为止：空行即“头部结束”与“输入已结束”的同一个终止条件。
            @Suppress("LoopWithTooManyJumpStatements")
            while (true) {
                val line = readLine(input) ?: break
                text.append(line).append('\n')
                if (line.isEmpty()) break
            }
            return text.toString()
        }

        /** 读一行（\r\n 结尾），EOF 返回 null。 */
        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (line.isEmpty()) null else line.toString()
                if (b == '\n'.code) return line.toString().trimEnd('\r')
                line.append(b.toChar())
            }
        }
    }

    private companion object {
        /** RFC 6455 握手魔数。 */
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
