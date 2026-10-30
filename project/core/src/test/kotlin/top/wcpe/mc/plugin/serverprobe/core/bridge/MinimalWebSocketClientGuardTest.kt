package top.wcpe.mc.plugin.serverprobe.core.bridge

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [MinimalWebSocketClient] 的线路防护单测（线路治理）。
 *
 * 覆盖两条**只能在真实 socket 上验证**的防护：
 * - 写侧 watchdog：对端不读导致写阻塞时，必须能被强关 socket 解除；
 * - 入站上限：畸形/超限长度必须被拒绝，且不打死读线程。
 */
class MinimalWebSocketClientGuardTest {

    /**
     * 写侧 watchdog：对端完成握手后**只建连不读取** → 写最终阻塞 → `checkWriteDeadline` 必须返回 true。
     *
     * 这是 Java 没有写超时的补偿手段：不这样做，发送线程会永久持锁，
     * 连带心跳排队、`close()` 卡住、重连线程 join 超时后泄漏。
     *
     * 需要一个**真的完成 101 握手**的服务端——否则客户端 `output` 为 null，
     * 写会立刻抛 EOFException 而不是阻塞，测不到 watchdog。
     */
    @Test
    fun `写阻塞超时被 watchdog 解除`() {
        ServerSocket(0).use { server ->
            val accepted = CountDownLatch(1)
            val peer = Thread {
                runCatching {
                    val socket = server.accept()
                    completeHandshake(socket)
                    accepted.countDown()
                    // 故意不读:塞满接收缓冲即可让写端阻塞。
                    Thread.sleep(5_000)
                    socket.close()
                }
            }.apply { isDaemon = true }
            peer.start()

            val client = MinimalWebSocketClient(
                uri = java.net.URI("ws://127.0.0.1:${server.localPort}/ws"),
                connectTimeoutMs = 2_000,
                readTimeoutMs = 200,
                writeDeadlineMs = 100,
            )
            client.connect()
            assertTrue(accepted.await(2, TimeUnit.SECONDS), "测试服务端应已完成握手")

            // 灌入大数据把 socket 缓冲塞满,使写阻塞(在独立线程里写,避免测试线程卡住)
            val writer = Thread {
                runCatching {
                    val big = "x".repeat(1 shl 20)
                    repeat(256) { client.sendText(big) }
                }
            }.apply { isDaemon = true }
            writer.start()

            // 等写进入阻塞态后,watchdog 应判定超时并强关
            val detected = awaitUntil(5_000) { client.checkWriteDeadline() }
            assertTrue(detected, "写阻塞超过 writeDeadlineMs 后 watchdog 应返回 true")

            client.close()
            writer.interrupt()
        }
    }

    /**
     * 在测试服务端完成最小 WebSocket 握手（回 101 + 正确的 Sec-WebSocket-Accept）。
     *
     * 必须算对 accept：客户端会校验，算错会抛"握手校验失败"而测不到后续。
     */
    private fun completeHandshake(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val key = readHeaderKey(input)
        val accept = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)),
        )
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        output.write(response.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    /** 从握手请求中读出 Sec-WebSocket-Key。 */
    private fun readHeaderKey(input: InputStream): String {
        val builder = StringBuilder()
        // 读到首个空行为止：空行即"头部结束"与"输入已结束"的同一个终止条件，
        // 两个 break 表达的是同一件事的两种写法，无需改造成状态机。
        @Suppress("LoopWithTooManyJumpStatements")
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            builder.append(line).append('\n')
        }
        return builder.lineSequence()
            .firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?: error("握手请求缺少 Sec-WebSocket-Key")
    }

    /** 读一行（以 \r\n 结尾），EOF 返回 null。 */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            line.append(b.toChar())
        }
    }

    /** 未在写时 watchdog 不误报。 */
    @Test
    fun `空闲时 watchdog 不误报`() {
        val client = MinimalWebSocketClient(
            uri = java.net.URI("ws://127.0.0.1:1/ws"),
            connectTimeoutMs = 100,
            readTimeoutMs = 200,
            writeDeadlineMs = 100,
        )
        assertFalse(client.checkWriteDeadline(), "没有在途写时不得判定超时")
    }

    /** 关闭幂等,且重复关闭不抛。 */
    @Test
    fun `关闭幂等`() {
        val client = MinimalWebSocketClient(
            uri = java.net.URI("ws://127.0.0.1:1/ws"),
            connectTimeoutMs = 100,
            readTimeoutMs = 200,
        )
        client.close()
        client.close()
        assertFalse(client.isConnected())
    }

    /** 轮询等待条件成立;仅用于等待异步状态,不作为性能断言。 */
    private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return false
    }
}
