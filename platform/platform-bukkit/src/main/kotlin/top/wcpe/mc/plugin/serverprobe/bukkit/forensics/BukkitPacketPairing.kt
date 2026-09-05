package top.wcpe.mc.plugin.serverprobe.bukkit.forensics

import top.wcpe.mc.plugin.serverprobe.api.forensics.PacketDirection
import java.lang.reflect.Modifier
import java.util.ArrayDeque

/** 解码包与原始字节间的 EventLoop 内顺序关联。 */
internal class BukkitPacketPairing(
    private val identity: BukkitPacketIdentity,
    private val recorder: BukkitPacketForensicsRecorder,
) {

    private val inboundRaw = ArrayDeque<RawPacketCapture>()
    private val outboundPacket = ArrayDeque<PacketMetadata>()

    /** 解码处理器是否已成功接入，失败时原始包立即按回退类型入队。 */
    var decodedHandlerAvailable: Boolean = false

    /** 入站原始字节先于解码包到达。 */
    fun onInboundRaw(raw: RawPacketCapture) {
        if (!decodedHandlerAvailable) return recordFallback(PacketDirection.INGRESS, raw)
        offerInbound(raw)
    }

    /** 入站解码包与最早待关联原始字节按 Netty 顺序配对。 */
    fun onInboundPacket(packet: Any) {
        val raw = inboundRaw.pollFirst() ?: return
        record(PacketDirection.INGRESS, PacketMetadata.from(packet), raw)
    }

    /** 出站解码包先于编码后的原始字节到达。 */
    fun onOutboundPacket(packet: Any) {
        if (!decodedHandlerAvailable) return
        if (outboundPacket.size >= MAX_PENDING_PACKETS) outboundPacket.removeFirst()
        outboundPacket.addLast(PacketMetadata.from(packet))
    }

    /** 出站原始字节与最早待关联解码包按 Netty 顺序配对。 */
    fun onOutboundRaw(raw: RawPacketCapture) {
        val metadata = outboundPacket.pollFirst()
        if (metadata == null) recordFallback(PacketDirection.EGRESS, raw) else record(PacketDirection.EGRESS, metadata, raw)
    }

    /** 卸载时将尚未匹配的入站字节按回退类型保留，避免连接末尾丢失证据。 */
    fun flush() {
        while (inboundRaw.isNotEmpty()) recordFallback(PacketDirection.INGRESS, inboundRaw.removeFirst())
        outboundPacket.clear()
    }

    private fun offerInbound(raw: RawPacketCapture) {
        if (inboundRaw.size >= MAX_PENDING_PACKETS) recordFallback(PacketDirection.INGRESS, inboundRaw.removeFirst())
        inboundRaw.addLast(raw)
    }

    private fun record(direction: PacketDirection, metadata: PacketMetadata, raw: RawPacketCapture) {
        recorder.record(identity, direction, metadata.packetType, metadata.channel, raw)
    }

    private fun recordFallback(direction: PacketDirection, raw: RawPacketCapture) {
        recorder.record(identity, direction, raw.fallbackPacketType, null, raw)
    }

    private companion object {
        private const val MAX_PENDING_PACKETS = 32
    }
}

/** 解码包的稳定展示名称及 Plugin Message 频道。 */
private data class PacketMetadata(val packetType: String, val channel: String?) {

    companion object {
        fun from(packet: Any): PacketMetadata {
            val simpleName = packet.javaClass.simpleName.ifEmpty { packet.javaClass.name.substringAfterLast('.') }
            val channel = PluginMessageChannel.resolve(packet)
            return PacketMetadata("bukkit.$simpleName", channel)
        }
    }
}

/** 以跨版本反射提取 Plugin Message 频道；无法提取时使用不可白名单的明确标记。 */
internal object PluginMessageChannel {

    fun resolve(packet: Any): String? {
        val potentialPluginMessage = isPluginMessage(packet.javaClass.simpleName)
        if (!potentialPluginMessage) return null
        val channel = directChannel(packet) ?: stringField(packet) ?: nestedPayloadChannel(packet)
        return channel ?: if (potentialPluginMessage) UNRESOLVED_CHANNEL else null
    }

    private fun directChannel(packet: Any): String? = invokeNamed(packet, CHANNEL_METHOD_NAMES)

    private fun nestedPayloadChannel(packet: Any): String? = payloads(packet)
        .mapNotNull(::identifierChannel)
        .firstOrNull()

    private fun payloads(packet: Any): Sequence<Any> = sequence {
        val namedPayload = invokeObjectNamed(packet, PAYLOAD_METHOD_NAMES)
        if (namedPayload != null) yield(namedPayload)
        objectFields(packet).forEach { payload -> yield(payload) }
    }

    private fun identifierChannel(payload: Any): String? = invokeNamed(payload, CHANNEL_METHOD_NAMES + IDENTIFIER_METHOD_NAMES)
        ?.takeIf(::isChannelIdentifier)
        ?: objectFields(payload).map(Any::toString).firstOrNull(::isChannelIdentifier)

    private fun objectFields(target: Any): Sequence<Any> = generateSequence(target.javaClass) { it.superclass }
        .flatMap { type -> type.declaredFields.asSequence() }
        .filterNot { field -> Modifier.isStatic(field.modifiers) || field.type.isPrimitive }
        .mapNotNull { field -> runCatching { field.isAccessible = true; field.get(target) }.getOrNull() }

    private fun isChannelIdentifier(value: String): Boolean = CHANNEL_IDENTIFIER.matches(value)

    private fun stringField(packet: Any): String? = generateSequence(packet.javaClass) { it.superclass }
        .flatMap { type -> type.declaredFields.asSequence() }
        .firstOrNull { field -> field.type == String::class.java }
        ?.let { field -> runCatching { field.isAccessible = true; field.get(packet) as? String }.getOrNull() }
        ?.takeIf(String::isNotBlank)

    private fun invokeNamed(target: Any, names: Set<String>): String? = invokeObjectNamed(target, names)
        ?.toString()
        ?.takeIf(String::isNotBlank)

    private fun invokeObjectNamed(target: Any, names: Set<String>): Any? = target.javaClass.methods
        .firstOrNull { method -> method.name in names && method.parameterTypes.isEmpty() }
        ?.let { method -> runCatching { method.invoke(target) }.getOrNull() }

    private fun isPluginMessage(simpleName: String): Boolean =
        simpleName.contains("CustomPayload", ignoreCase = true) || simpleName.contains("PluginMessage", ignoreCase = true)

    private const val UNRESOLVED_CHANNEL = "<未解析插件频道>"
    private val CHANNEL_IDENTIFIER = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
    private val CHANNEL_METHOD_NAMES = setOf("getChannel", "channel", "a")
    private val PAYLOAD_METHOD_NAMES = setOf("getPayload", "payload")
    private val IDENTIFIER_METHOD_NAMES = setOf("getId", "id")
}
