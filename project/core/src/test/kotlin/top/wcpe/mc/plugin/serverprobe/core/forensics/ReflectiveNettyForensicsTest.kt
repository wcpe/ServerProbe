package top.wcpe.mc.plugin.serverprobe.core.forensics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import top.wcpe.mc.plugin.serverprobe.api.forensics.PacketDirection
import java.net.InetSocketAddress
import java.util.UUID

/** 代理 Netty 解码包和原始字节的配对测试。 */
class ReflectiveNettyForensicsTest {

    /** 已解码的插件消息应保留平台前缀和频道，供双白名单判定。 */
    @Test
    fun `解码插件消息与原始字节配对`() {
        val observations = ArrayList<PacketForensicsObservation>()
        val recorder = NettyPacketForensicsRecorder(
            enqueue = { observations += it; true },
            aggregator = PacketTrafficAggregator(),
            capturePolicy = PacketCapturePolicy(setOf("velocity.PluginMessagePacket"), setOf("serverprobe:debug"), 64),
            nowMs = { 123L },
        )
        val pairing = NettyPacketPairing(
            identity = NettyPacketIdentity("uuid", "玩家", "203.0.113.77"),
            platformId = "velocity",
            recorder = recorder,
        )
        pairing.decodedHandlerAvailable = true

        pairing.onInboundRaw(NettyRawPacketCapture(3, "hash", byteArrayOf(1, 2, 3), "velocity.raw.ingress.1"))
        pairing.onInboundPacket(PluginMessagePacket())

        assertEquals(1, observations.size)
        assertEquals(PacketDirection.INGRESS, observations.single().direction)
        assertEquals("velocity.PluginMessagePacket", observations.single().packetType)
        assertEquals("serverprobe:debug", observations.single().channel)
        assertNotNull(observations.single().payload.payloadBase64)
    }

    /** BungeeCord 解码器用 PacketWrapper 包装 PluginMessage 时，白名单频道仍应可用于受限载荷保存。 */
    @Test
    fun `BungeeCord PacketWrapper 中的白名单插件消息可配对`() {
        val observations = ArrayList<PacketForensicsObservation>()
        val recorder = NettyPacketForensicsRecorder(
            enqueue = { observations += it; true },
            aggregator = PacketTrafficAggregator(),
            capturePolicy = PacketCapturePolicy(setOf("bungee.PluginMessage"), setOf("serverprobe:test"), 64),
            nowMs = { 123L },
        )
        val pairing = NettyPacketPairing(
            identity = NettyPacketIdentity("uuid", "玩家", "203.0.113.77"),
            platformId = "bungee",
            recorder = recorder,
        )
        pairing.decodedHandlerAvailable = true

        pairing.onInboundRaw(NettyRawPacketCapture(3, "hash", byteArrayOf(1, 2, 3), "bungee.raw.ingress.1"))
        pairing.onInboundPacket(PacketWrapper(PluginMessage("serverprobe:test")))

        assertEquals(1, observations.size)
        assertEquals("bungee.PluginMessage", observations.single().packetType)
        assertEquals("serverprobe:test", observations.single().channel)
        assertNotNull(observations.single().payload.payloadBase64)
    }

    /** 代理端身份提取只依赖稳定 getter，不与具体平台代理 API 耦合。 */
    @Test
    fun `代理身份读取完整IP`() {
        val identity = ReflectiveNettyPacketIdentity.resolve(FakePlayer(), "getUsername", "getRemoteAddress")

        assertEquals("00000000-0000-0000-0000-000000000001", identity.playerUuid)
        assertEquals("玩家", identity.playerName)
        assertEquals("203.0.113.77", identity.ip)
    }

    /** BungeeCord 的 UserConnection 通过 getCh 与 ChannelWrapper 的 getHandle 暴露真实 Channel。 */
    @Test
    fun `BungeeCord getter 链定位 ChannelWrapper 中的 Channel`() {
        val channel = EmbeddedChannel()

        assertSame(channel, locate(BungeeConnectionWithGetter(BungeeChannelWrapper(channel))))
    }

    /** 部分 BungeeCord 派生实现只保留 ch 字段，定位器应沿同名字段继续解析。 */
    @Test
    fun `BungeeCord 字段链定位 ChannelWrapper 中的 Channel`() {
        val channel = EmbeddedChannel()

        assertSame(channel, locate(BungeeConnectionWithField(FieldOnlyChannelWrapper(channel))))
    }

    /** BungeeCord 的 ChannelPipeline 同时提供 String 与 Class 重载，取证处理器必须使用 String 重载。 */
    @Test
    fun `BungeeCord ChannelPipeline 按名称安装并移除取证处理器`() {
        val channel = embeddedChannel()
        val connection = BungeeConnectionWithGetter(BungeeChannelWrapper(channel))
        val registry = ReflectiveNettyForensicsRegistry(
            platformId = "bungee",
            channelGetterPath = listOf("getCh", "getHandle"),
            decoderHandlerName = "packet-decoder",
            encoderHandlerName = "packet-encoder",
            recorder = recorder(),
            maxPayloadBytes = 64,
        )

        assertTrue(registry.attach(connection, NettyPacketIdentity(null, "玩家", "203.0.113.77")))
        assertEquals(3, channel.pipeline().names().count { it.startsWith("serverprobe-forensics-") })

        registry.detach(connection)

        assertEquals(0, channel.pipeline().names().count { it.startsWith("serverprobe-forensics-") })
    }

    /** 生产默认关闭取证调试日志，避免连接量大时产生无意义输出。 */
    @Test
    fun `取证调试输出默认关闭`() {
        val output = ArrayList<String>()
        val debug = NettyForensicsDebugLogger({ false }, output::add)

        debug.log("Netty 管线处理器顺序：packet-decoder,packet-encoder")

        assertTrue(output.isEmpty())
    }

    /** 仅在显式 DEBUG 时输出接入与断连前的处理器存活状态，便于定位代理重建管线。 */
    @Test
    fun `代理取证调试输出处理器状态`() {
        val output = ArrayList<String>()
        val channel = embeddedChannel()
        val registry = ReflectiveNettyForensicsRegistry(
            platformId = "bungee",
            channelGetterPath = listOf("getCh", "getHandle"),
            decoderHandlerName = "packet-decoder",
            encoderHandlerName = "packet-encoder",
            recorder = recorder(),
            maxPayloadBytes = 64,
            debug = NettyForensicsDebugLogger({ true }, output::add),
        )
        val connection = BungeeConnectionWithGetter(BungeeChannelWrapper(channel))

        assertTrue(registry.attach(connection, NettyPacketIdentity(null, "玩家", "203.0.113.77")))
        registry.detach(connection)

        assertTrue(output.any { it.contains("接入后") && it.contains("处理器仍存在") })
        assertTrue(output.any { it.contains("断连前") && it.contains("处理器仍存在") })
    }

    /** 动态代理处理器必须把入站、出站及异常各自原样且仅一次传递给下一处理器。 */
    @Test
    fun `反射取证处理器不丢弃或重复转发 Netty 事件`() {
        val inboundTerminal = InboundTerminal()
        val outboundTerminal = OutboundTerminal()
        val channel = EmbeddedChannel()
        channel.pipeline().addLast("packet-decoder", ChannelDuplexHandler())
        channel.pipeline().addLast("packet-encoder", ChannelDuplexHandler())
        val registry = ReflectiveNettyForensicsRegistry(
            platformId = "bungee",
            channelGetterPath = emptyList(),
            decoderHandlerName = "packet-decoder",
            encoderHandlerName = "packet-encoder",
            recorder = recorder(),
            maxPayloadBytes = 64,
        )

        assertTrue(registry.attach(channel, NettyPacketIdentity(null, "玩家", "203.0.113.77")))
        channel.pipeline().addFirst("outbound-terminal", outboundTerminal)
        channel.pipeline().addLast("inbound-terminal", inboundTerminal)
        val inbound = Unpooled.wrappedBuffer(byteArrayOf(1, 2, 3))
        val outbound = Unpooled.wrappedBuffer(byteArrayOf(4, 5, 6))
        val failure = IllegalStateException("测试异常")

        assertTrue(channel.writeInbound(inbound))
        assertTrue(channel.writeOutbound(outbound))
        channel.pipeline().fireExceptionCaught(failure)

        assertEquals(1, inboundTerminal.messages)
        assertSame(inbound, channel.readInbound<Any>())
        assertEquals(1, outboundTerminal.messages)
        assertSame(outbound, channel.readOutbound<Any>())
        assertSame(failure, inboundTerminal.failure)
        inbound.release()
        outbound.release()
        channel.finishAndReleaseAll()
    }

    /** 模拟 Velocity 已解码插件消息；val 属性在 JVM 上即 getChannel() 读取器，供反射按名调用。 */
    private class PluginMessagePacket {
        val channel: String = "serverprobe:debug"
    }

    class PacketWrapper(@JvmField val packet: PluginMessage)

    class PluginMessage(private val tag: String) {
        fun getTag(): String = tag
    }

    /** 模拟代理公开玩家对象；val 属性在 JVM 上即 getXxx() 读取器，供反射按名调用。 */
    private class FakePlayer {
        val uniqueId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

        val username: String = "玩家"

        val remoteAddress: InetSocketAddress = InetSocketAddress("203.0.113.77", 25565)
    }

    private fun locate(connection: Any): Any? {
        val type = Class.forName("top.wcpe.mc.plugin.serverprobe.core.forensics.ReflectiveNettyChannelLocator")
        val finder = type.getDeclaredMethod("find", Any::class.java, List::class.java)
        finder.isAccessible = true
        return finder.invoke(type.getField("INSTANCE").get(null), connection, listOf("getCh", "getHandle"))
    }

    private class BungeeConnectionWithGetter(private val ch: BungeeChannelWrapper) {
        fun getCh(): BungeeChannelWrapper = ch
    }

    private class BungeeConnectionWithField(private val ch: FieldOnlyChannelWrapper)

    private class BungeeChannelWrapper(private val handle: EmbeddedChannel) {
        fun getHandle(): EmbeddedChannel = handle
    }

    private class FieldOnlyChannelWrapper(private val ch: EmbeddedChannel)

    private fun recorder(): NettyPacketForensicsRecorder = NettyPacketForensicsRecorder(
        enqueue = { true },
        aggregator = PacketTrafficAggregator(),
        capturePolicy = PacketCapturePolicy(emptySet(), emptySet(), 64),
    )

    private fun embeddedChannel(): EmbeddedChannel = EmbeddedChannel().also { channel ->
        channel.pipeline().addLast("packet-decoder", ChannelDuplexHandler())
        channel.pipeline().addLast("packet-encoder", ChannelDuplexHandler())
    }

    private class InboundTerminal : ChannelInboundHandlerAdapter() {
        var messages = 0
        var failure: Throwable? = null

        override fun channelRead(context: ChannelHandlerContext, message: Any) {
            messages++
            context.fireChannelRead(message)
        }

        override fun exceptionCaught(context: ChannelHandlerContext, cause: Throwable) {
            failure = cause
        }
    }

    private class OutboundTerminal : ChannelOutboundHandlerAdapter() {
        var messages = 0

        override fun write(context: ChannelHandlerContext, message: Any, promise: io.netty.channel.ChannelPromise) {
            messages++
            context.write(message, promise)
        }
    }
}
