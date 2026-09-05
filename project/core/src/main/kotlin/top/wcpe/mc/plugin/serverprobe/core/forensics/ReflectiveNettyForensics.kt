package top.wcpe.mc.plugin.serverprobe.core.forensics

import top.wcpe.mc.plugin.serverprobe.api.forensics.PacketDirection
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 代理连接在登录后可取得的稳定身份信息，不再回调平台 API。 */
data class NettyPacketIdentity(
    val playerUuid: String?,
    val playerName: String?,
    val ip: String?,
)

/** 原始字节的有界摘要，完整 ByteBuf 不会离开 Netty EventLoop。 */
data class NettyRawPacketCapture(
    val originalLength: Int,
    val sha256: String,
    val candidatePayload: ByteArray,
    val fallbackPacketType: String,
)

/** 代理取证诊断仅在显式开启 DEBUG 后输出，避免生产环境按连接刷屏。 */
class NettyForensicsDebugLogger(
    private val enabled: () -> Boolean,
    private val output: (String) -> Unit,
) {

    fun log(message: String) {
        if (enabled()) output(message)
    }
}

/** Netty EventLoop 侧仅完成聚合和入队的取证写入器。 */
class NettyPacketForensicsRecorder(
    private val enqueue: (PacketForensicsObservation) -> Boolean,
    private val aggregator: PacketTrafficAggregator,
    private val capturePolicy: PacketCapturePolicy,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** 写入已完成类型关联的原始包，不执行磁盘 IO。 */
    fun record(
        identity: NettyPacketIdentity,
        direction: PacketDirection,
        packetType: String,
        channel: String?,
        raw: NettyRawPacketCapture,
    ) {
        aggregator.record(direction, packetType, raw.originalLength, identity.ip)
        enqueue(
            PacketForensicsObservation(
                capturedAtMs = nowMs(),
                direction = direction,
                playerUuid = identity.playerUuid,
                playerName = identity.playerName,
                ip = identity.ip,
                packetType = packetType,
                channel = channel,
                payload = capturePolicy.capture(
                    packetType,
                    channel,
                    raw.originalLength,
                    raw.sha256,
                    raw.candidatePayload,
                ),
            ),
        )
    }
}

/** 解码包与原始字节按 EventLoop 顺序配对，溢出或卸载时降级为原始类型。 */
class NettyPacketPairing(
    private val identity: NettyPacketIdentity,
    private val platformId: String,
    private val recorder: NettyPacketForensicsRecorder,
) {

    private val inboundRaw = ArrayDeque<NettyRawPacketCapture>()
    private val outboundPackets = ArrayDeque<NettyPacketMetadata>()

    /** 解码处理器是否已成功安装，失败时原始数据立即以回退类型记录。 */
    var decodedHandlerAvailable: Boolean = false

    /** 入站原始数据先于解码包出现。 */
    fun onInboundRaw(raw: NettyRawPacketCapture) {
        if (!decodedHandlerAvailable) return recordFallback(PacketDirection.INGRESS, raw)
        if (inboundRaw.size >= MAX_PENDING_PACKETS) recordFallback(PacketDirection.INGRESS, inboundRaw.removeFirst())
        inboundRaw.addLast(raw)
    }

    /** 入站解码包与最早待配对原始数据关联。 */
    fun onInboundPacket(packet: Any) {
        val raw = inboundRaw.pollFirst() ?: return
        record(PacketDirection.INGRESS, NettyPacketMetadata.from(platformId, packet), raw)
    }

    /** 出站解码包先于编码后的原始数据出现。 */
    fun onOutboundPacket(packet: Any) {
        if (!decodedHandlerAvailable) return
        if (outboundPackets.size >= MAX_PENDING_PACKETS) outboundPackets.removeFirst()
        outboundPackets.addLast(NettyPacketMetadata.from(platformId, packet))
    }

    /** 出站原始数据与最早待配对解码包关联。 */
    fun onOutboundRaw(raw: NettyRawPacketCapture) {
        val metadata = outboundPackets.pollFirst()
        if (metadata == null) recordFallback(PacketDirection.EGRESS, raw) else record(PacketDirection.EGRESS, metadata, raw)
    }

    /** 卸载时保留尚未解码的入站证据，并释放待配对元数据。 */
    fun flush() {
        while (inboundRaw.isNotEmpty()) recordFallback(PacketDirection.INGRESS, inboundRaw.removeFirst())
        outboundPackets.clear()
    }

    private fun record(direction: PacketDirection, metadata: NettyPacketMetadata, raw: NettyRawPacketCapture) {
        recorder.record(identity, direction, metadata.packetType, metadata.channel, raw)
    }

    private fun recordFallback(direction: PacketDirection, raw: NettyRawPacketCapture) {
        recorder.record(identity, direction, raw.fallbackPacketType, null, raw)
    }

    private companion object {
        private const val MAX_PENDING_PACKETS = 32
    }
}

/** 以受限 getter 链接入代理内部 Channel，失败时仅跳过该连接。 */
class ReflectiveNettyForensicsRegistry(
    private val platformId: String,
    private val channelGetterPath: List<String>,
    private val decoderHandlerName: String,
    private val encoderHandlerName: String,
    private val recorder: NettyPacketForensicsRecorder,
    private val maxPayloadBytes: Int,
    private val debug: NettyForensicsDebugLogger? = null,
) {

    private val attached = ConcurrentHashMap<Any, AttachedChannel>()
    private val sequence = AtomicLong()

    /** 将当前连接的双向处理器接入代理 Channel；重复调用保持幂等。 */
    fun attach(connection: Any, identity: NettyPacketIdentity): Boolean {
        val channel = ReflectiveNettyChannelLocator.find(connection, channelGetterPath) ?: return false
        attached[channel]?.takeIf { it.isInstalled() }?.let { return true }
        attached.remove(channel)?.remove()
        return createAttachment(channel, identity)?.let { attachment ->
            attached.putIfAbsent(channel, attachment)?.let { attachment.remove(); true } ?: run {
                attachment.logStatus("接入后")
                true
            }
        } ?: false
    }

    /** 移除一条连接上的全部处理器；连接已关闭时同样安全。 */
    fun detach(connection: Any) {
        ReflectiveNettyChannelLocator.find(connection, channelGetterPath)?.let { channel ->
            attached.remove(channel)?.also {
                it.logStatus("断连前")
                it.remove()
            }
        }
    }

    /** 插件卸载时移除所有已接入连接。 */
    fun detachAll() {
        attached.entries.toList().forEach { (channel, attachment) ->
            if (attached.remove(channel, attachment)) attachment.remove()
        }
    }

    private fun createAttachment(channel: Any, identity: NettyPacketIdentity): AttachedChannel? {
        val pipeline = ReflectiveNettyPipeline.create(channel) ?: return null
        val connectionDebug = ConnectionDebugLogger(debug)
        connectionDebug.pipeline(pipeline)
        val pairing = NettyPacketPairing(identity, platformId, recorder)
        val names = HandlerNames(sequence.incrementAndGet())
        val decoded = pipeline.createHandler(pairing::onInboundPacket, pairing::onOutboundPacket, null) ?: return null
        val inboundRaw = pipeline.createHandler(
            inbound = { message ->
                connectionDebug.inbound(message, pipeline, names)
                read(message, PacketDirection.INGRESS)?.let(pairing::onInboundRaw)
            },
            outbound = {},
            onRemoved = pairing::flush,
        ) ?: return null
        val outboundRaw = pipeline.createHandler(
            inbound = {},
            outbound = { message ->
                connectionDebug.outbound(message, pipeline, names)
                read(message, PacketDirection.EGRESS)?.let(pairing::onOutboundRaw)
            },
            onRemoved = null,
        ) ?: return null
        // 解码处理器必须位于编码器之后：入站可收到解码包，出站则先于编码器收到原始包对象。
        pairing.decodedHandlerAvailable = pipeline.addAfter(encoderHandlerName, names.decoded, decoded)
        val inboundAdded = pipeline.addBefore(decoderHandlerName, names.inboundRaw, inboundRaw)
        val outboundAdded = pipeline.addBefore(encoderHandlerName, names.outboundRaw, outboundRaw)
        if (!inboundAdded && !outboundAdded) {
            pipeline.remove(names.all())
            return null
        }
        return AttachedChannel(pipeline, names, pairing, debug)
    }

    private fun read(message: Any, direction: PacketDirection): NettyRawPacketCapture? =
        runCatching { ReflectiveNettyByteBufReader.read(message, platformId, direction, maxPayloadBytes) }.getOrNull()

    /** 每条连接使用唯一名称，避免与旧实例或其他插件发生冲突。 */
    private data class HandlerNames(private val id: Long) {
        val decoded = "serverprobe-forensics-decoded-$id"
        val inboundRaw = "serverprobe-forensics-inbound-$id"
        val outboundRaw = "serverprobe-forensics-outbound-$id"

        fun all(): List<String> = listOf(decoded, inboundRaw, outboundRaw)
    }

    /** 每条连接只输出一次管线和每个方向的消息类名，不含载荷或身份数据。 */
    private class ConnectionDebugLogger(private val debug: NettyForensicsDebugLogger?) {
        private var inboundLogged = false
        private var outboundLogged = false

        fun pipeline(pipeline: ReflectiveNettyPipeline) {
            debug?.log("代理连接 Netty 管线处理器顺序：${pipeline.names().joinToString(",")}")
        }

        fun inbound(message: Any, pipeline: ReflectiveNettyPipeline, names: HandlerNames) {
            if (!inboundLogged) logStatus(pipeline, names, "首个入站消息前")
            if (!inboundLogged) debug?.log("代理连接入站消息类型：${message.javaClass.name}")
            inboundLogged = true
        }

        fun outbound(message: Any, pipeline: ReflectiveNettyPipeline, names: HandlerNames) {
            if (!outboundLogged) logStatus(pipeline, names, "首个出站消息前")
            if (!outboundLogged) debug?.log("代理连接出站消息类型：${message.javaClass.name}")
            outboundLogged = true
        }

        private fun logStatus(pipeline: ReflectiveNettyPipeline, names: HandlerNames, stage: String) {
            val installed = pipeline.contains(names.inboundRaw) || pipeline.contains(names.outboundRaw)
            val state = if (installed) "处理器仍存在" else "处理器已移除"
            debug?.log("代理连接${stage}取证$state，当前 Netty 管线：${pipeline.names().joinToString(",")}")
        }
    }

    /** 一条已接入 Channel 的处理器集合。 */
    private class AttachedChannel(
        private val pipeline: ReflectiveNettyPipeline,
        private val names: HandlerNames,
        private val pairing: NettyPacketPairing,
        private val debug: NettyForensicsDebugLogger?,
    ) {

        fun isInstalled(): Boolean = pipeline.contains(names.inboundRaw) || pipeline.contains(names.outboundRaw)

        fun logStatus(stage: String) {
            val state = if (isInstalled()) "处理器仍存在" else "处理器已移除"
            debug?.log("代理连接${stage}取证$state，当前 Netty 管线：${pipeline.names().joinToString(",")}")
        }

        fun remove() {
            pairing.flush()
            pipeline.remove(names.all())
        }
    }
}

/** 代理实现对象的公开 getter 链，最终对象必须是 Netty Channel。 */
private object ReflectiveNettyChannelLocator {

    fun find(connection: Any, getterPath: List<String>): Any? = runCatching {
        var current = connection
        getterPath.forEach { getter -> current = invokeGetter(current, getter) ?: readField(current, getter) ?: return@runCatching null }
        current.takeIf(::isChannel)
    }.getOrNull()

    private fun invokeGetter(value: Any, name: String): Any? = value.javaClass.methods
        .firstOrNull { method -> method.name == name && method.parameterTypes.isEmpty() }
        ?.invoke(value)

    private fun readField(value: Any, getterName: String): Any? = generateSequence(value.javaClass) { it.superclass }
        .flatMap { type -> type.declaredFields.asSequence() }
        .firstOrNull { field -> field.name in fieldNames(getterName) }
        ?.let { field -> runCatching { field.isAccessible = true; field.get(value) }.getOrNull() }

    private fun propertyName(getterName: String): String = getterName
        .removePrefix(GETTER_PREFIX)
        .replaceFirstChar { character -> character.lowercase() }

    private fun fieldNames(getterName: String): Set<String> = when (getterName) {
        BUNGEE_CHANNEL_GETTER, BUNGEE_HANDLE_GETTER -> setOf(BUNGEE_CHANNEL_FIELD, propertyName(getterName))
        else -> setOf(propertyName(getterName))
    }

    private fun isChannel(value: Any): Boolean = runCatching {
        val channelType = Class.forName(CHANNEL_CLASS, false, value.javaClass.classLoader)
        channelType.isAssignableFrom(value.javaClass)
    }.getOrDefault(false)

    private const val CHANNEL_CLASS = "io.netty.channel.Channel"
    private const val GETTER_PREFIX = "get"
    private const val BUNGEE_CHANNEL_GETTER = "getCh"
    private const val BUNGEE_HANDLE_GETTER = "getHandle"
    private const val BUNGEE_CHANNEL_FIELD = "ch"
}

/** 不直接依赖特定 Netty 版本的最小 ChannelPipeline 反射门面。 */
private class ReflectiveNettyPipeline private constructor(private val pipeline: Any, private val loader: ClassLoader) {

    fun createHandler(inbound: (Any) -> Unit, outbound: (Any) -> Unit, onRemoved: (() -> Unit)?): Any? = runCatching {
        val inboundHandler = Class.forName(INBOUND_HANDLER_CLASS, false, loader)
        val outboundHandler = Class.forName(OUTBOUND_HANDLER_CLASS, false, loader)
        Proxy.newProxyInstance(loader, arrayOf(inboundHandler, outboundHandler), ReflectiveNettyHandler(inbound, outbound, onRemoved))
    }.getOrNull()

    fun addBefore(baseName: String, name: String, handler: Any): Boolean = add(ADD_BEFORE, baseName, name, handler)

    fun addAfter(baseName: String, name: String, handler: Any): Boolean = add(ADD_AFTER, baseName, name, handler)

    fun contains(name: String): Boolean = runCatching { stringMethod(GET).invoke(pipeline, name) != null }.getOrDefault(false)

    fun remove(names: List<String>) {
        names.filter(::contains).forEach { name -> runCatching { stringMethod(REMOVE).invoke(pipeline, name) } }
    }

    fun names(): List<String> = runCatching {
        val names = pipeline.javaClass.methods
            .firstOrNull { candidate -> candidate.name == NAMES && candidate.parameterTypes.isEmpty() }
            ?.invoke(pipeline) as? Collection<*>
        names?.mapNotNull { it as? String } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun add(methodName: String, baseName: String, name: String, handler: Any): Boolean = runCatching {
        if (contains(name)) return true
        method(methodName, 3).invoke(pipeline, baseName, name, handler)
        contains(name)
    }.getOrDefault(false)

    private fun method(name: String, parameterCount: Int): Method = pipeline.javaClass.methods
        .first { candidate -> candidate.name == name && candidate.parameterTypes.size == parameterCount }

    private fun stringMethod(name: String): Method = pipeline.javaClass.methods
        .first { candidate -> candidate.name == name && candidate.parameterTypes.contentEquals(arrayOf(String::class.java)) }

    companion object {
        fun create(channel: Any): ReflectiveNettyPipeline? = runCatching {
            val pipeline = channel.javaClass.methods.first { it.name == PIPELINE && it.parameterTypes.isEmpty() }.invoke(channel)
            ReflectiveNettyPipeline(pipeline, channel.javaClass.classLoader)
        }.getOrNull()

        private const val PIPELINE = "pipeline"
        private const val GET = "get"
        private const val ADD_BEFORE = "addBefore"
        private const val ADD_AFTER = "addAfter"
        private const val REMOVE = "remove"
        private const val NAMES = "names"
        private const val INBOUND_HANDLER_CLASS = "io.netty.channel.ChannelInboundHandler"
        private const val OUTBOUND_HANDLER_CLASS = "io.netty.channel.ChannelOutboundHandler"
    }
}

/** 动态处理器只做无阻塞回调和原样转发，不保留 Netty 消息对象。 */
private class ReflectiveNettyHandler(
    private val inbound: (Any) -> Unit,
    private val outbound: (Any) -> Unit,
    private val onRemoved: (() -> Unit)?,
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, rawArguments: Array<Any?>?): Any? {
        val arguments = rawArguments ?: emptyArray()
        if (method.declaringClass == Any::class.java) return objectMethod(proxy, method.name, arguments)
        val context = arguments.firstOrNull() ?: return null
        return when (method.name) {
            CHANNEL_READ -> forwardRead(context, arguments)
            WRITE -> forwardWrite(context, arguments)
            HANDLER_REMOVED -> onRemoved?.invoke()
            HANDLER_ADDED -> null
            else -> forward(context, method.name, arguments.drop(1).toTypedArray())
        }
    }

    private fun forwardRead(context: Any, arguments: Array<Any?>): Any? {
        arguments.getOrNull(MESSAGE_ARGUMENT)?.let { message -> runCatching { inbound(message) } }
        return invokeContext(context, FIRE_CHANNEL_READ, arguments.drop(1).toTypedArray())
    }

    private fun forwardWrite(context: Any, arguments: Array<Any?>): Any? {
        arguments.getOrNull(MESSAGE_ARGUMENT)?.let { message -> runCatching { outbound(message) } }
        return invokeContext(context, WRITE, arguments.drop(1).toTypedArray())
    }

    private fun forward(context: Any, methodName: String, arguments: Array<Any?>): Any? = when (methodName) {
        EXCEPTION_CAUGHT -> invokeContext(context, FIRE_EXCEPTION_CAUGHT, arguments)
        CHANNEL_REGISTERED, CHANNEL_UNREGISTERED, CHANNEL_ACTIVE, CHANNEL_INACTIVE,
        CHANNEL_READ_COMPLETE, USER_EVENT_TRIGGERED, CHANNEL_WRITABILITY_CHANGED ->
            invokeContext(context, "fire${methodName.capitalized()}", arguments)
        else -> invokeContext(context, methodName, arguments)
    }

    private fun invokeContext(context: Any, name: String, arguments: Array<Any?>): Any? {
        val contextType = contextInterface(context) ?: return null
        return contextType.methods
            .firstOrNull { candidate -> compatible(candidate, name, arguments) }
            ?.let { method -> invokeMethod(method, context, arguments) }
    }

    /** 从公开接口取得方法，避免反射调用 Netty 包私有 Context 实现时触发访问异常。 */
    private fun contextInterface(context: Any): Class<*>? = runCatching {
        Class.forName(CONTEXT_CLASS, false, context.javaClass.classLoader)
    }.getOrNull()

    private fun invokeMethod(method: Method, context: Any, arguments: Array<Any?>): Any? = when (arguments.size) {
        0 -> method.invoke(context)
        1 -> method.invoke(context, arguments[0])
        2 -> method.invoke(context, arguments[0], arguments[1])
        3 -> method.invoke(context, arguments[0], arguments[1], arguments[2])
        else -> null
    }

    private fun compatible(method: Method, name: String, arguments: Array<Any?>): Boolean =
        method.name == name && method.parameterTypes.size == arguments.size && method.parameterTypes.indices.all { index ->
            arguments[index] == null || method.parameterTypes[index].isAssignableFrom(arguments[index]!!.javaClass)
        }

    private fun objectMethod(proxy: Any, name: String, arguments: Array<Any?>): Any? = when (name) {
        "toString" -> "ServerProbeNettyForensicsHandler"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === arguments.firstOrNull()
        else -> null
    }

    private fun String.capitalized(): String = replaceFirstChar { it.uppercase() }

    private companion object {
        private const val HANDLER_ADDED = "handlerAdded"
        private const val HANDLER_REMOVED = "handlerRemoved"
        private const val CHANNEL_READ = "channelRead"
        private const val WRITE = "write"
        private const val EXCEPTION_CAUGHT = "exceptionCaught"
        private const val CHANNEL_REGISTERED = "channelRegistered"
        private const val CHANNEL_UNREGISTERED = "channelUnregistered"
        private const val CHANNEL_ACTIVE = "channelActive"
        private const val CHANNEL_INACTIVE = "channelInactive"
        private const val CHANNEL_READ_COMPLETE = "channelReadComplete"
        private const val USER_EVENT_TRIGGERED = "userEventTriggered"
        private const val CHANNEL_WRITABILITY_CHANGED = "channelWritabilityChanged"
        private const val FIRE_CHANNEL_READ = "fireChannelRead"
        private const val FIRE_EXCEPTION_CAUGHT = "fireExceptionCaught"
        private const val MESSAGE_ARGUMENT = 1
        private const val CONTEXT_CLASS = "io.netty.channel.ChannelHandlerContext"
    }
}

/** 以反射分块读取 ByteBuf，避免整包复制或跨 EventLoop 持有。 */
private object ReflectiveNettyByteBufReader {

    fun read(message: Any, platformId: String, direction: PacketDirection, maxPayloadBytes: Int): NettyRawPacketCapture? {
        val access = ByteBufAccess.create(message) ?: return null
        val length = access.readableBytes().coerceAtLeast(0)
        val candidate = ByteArray(length.coerceAtMost(maxPayloadBytes.coerceAtLeast(0)))
        val digest = MessageDigest.getInstance(SHA_256)
        var offset = 0
        var candidateOffset = 0
        while (offset < length) {
            val chunk = ByteArray((length - offset).coerceAtMost(CHUNK_BYTES))
            access.getBytes(access.readerIndex() + offset, chunk)
            digest.update(chunk)
            val copied = (candidate.size - candidateOffset).coerceAtLeast(0).coerceAtMost(chunk.size)
            if (copied > 0) System.arraycopy(chunk, 0, candidate, candidateOffset, copied)
            candidateOffset += copied
            offset += chunk.size
        }
        val packetId = readVarInt(candidate) ?: UNKNOWN_PACKET_ID
        return NettyRawPacketCapture(length, hex(digest.digest()), candidate, "$platformId.raw.${direction.name.lowercase()}.$packetId")
    }

    private fun readVarInt(bytes: ByteArray): Int? {
        var result = 0
        bytes.take(MAX_VAR_INT_BYTES).forEachIndexed { index, byte ->
            result = result or ((byte.toInt() and VALUE_MASK) shl (BITS_PER_BYTE * index))
            if (byte.toInt() and CONTINUATION_MASK == 0) return result
        }
        return null
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private class ByteBufAccess private constructor(
        private val value: Any,
        private val readableBytes: Method,
        private val readerIndex: Method,
        private val getBytes: Method,
    ) {

        fun readableBytes(): Int = readableBytes.invoke(value) as Int

        fun readerIndex(): Int = readerIndex.invoke(value) as Int

        fun getBytes(index: Int, bytes: ByteArray) {
            getBytes.invoke(value, index, bytes)
        }

        companion object {
            fun create(value: Any): ByteBufAccess? {
                if (!isByteBuf(value.javaClass)) return null
                val type = value.javaClass
                val readable = type.methods.firstOrNull { it.name == "readableBytes" && it.parameterTypes.isEmpty() } ?: return null
                val index = type.methods.firstOrNull { it.name == "readerIndex" && it.parameterTypes.isEmpty() } ?: return null
                val bytes = type.methods.firstOrNull { method ->
                    method.name == "getBytes" && method.parameterTypes.contentEquals(
                        arrayOf(Int::class.javaPrimitiveType, ByteArray::class.java),
                    )
                } ?: return null
                return ByteBufAccess(value, readable, index, bytes)
            }

            private fun isByteBuf(type: Class<*>?): Boolean {
                if (type == null) return false
                if (type.name == BYTE_BUF_CLASS) return true
                return isByteBuf(type.superclass) || type.interfaces.any(::isByteBuf)
            }
        }
    }

    private const val SHA_256 = "SHA-256"
    private const val CHUNK_BYTES = 8 * 1_024
    private const val MAX_VAR_INT_BYTES = 5
    private const val BITS_PER_BYTE = 7
    private const val VALUE_MASK = 0x7F
    private const val CONTINUATION_MASK = 0x80
    private const val UNKNOWN_PACKET_ID = "unknown"
    private const val BYTE_BUF_CLASS = "io.netty.buffer.ByteBuf"
}

/** 已解码包的稳定展示名称及 Plugin Message 频道。 */
private data class NettyPacketMetadata(val packetType: String, val channel: String?) {

    companion object {
        fun from(platformId: String, packet: Any): NettyPacketMetadata {
            val decoded = unwrapBungeePluginMessage(platformId, packet)
            val simpleName = decoded.javaClass.simpleName.ifEmpty { decoded.javaClass.name.substringAfterLast('.') }
            return NettyPacketMetadata("$platformId.$simpleName", ReflectivePluginMessageChannel.resolve(decoded))
        }

        /** BungeeCord 仅解开 PacketWrapper 中明确的 PluginMessage，其他内部包保持包装类型。 */
        private fun unwrapBungeePluginMessage(platformId: String, packet: Any): Any {
            if (platformId != BUNGEE_PLATFORM || packet.javaClass.simpleName != PACKET_WRAPPER) return packet
            val nested = runCatching { packet.javaClass.getField(PACKET_FIELD).get(packet) }.getOrNull() ?: return packet
            return nested.takeIf { it.javaClass.simpleName == PLUGIN_MESSAGE } ?: packet
        }

        private const val BUNGEE_PLATFORM = "bungee"
        private const val PACKET_WRAPPER = "PacketWrapper"
        private const val PACKET_FIELD = "packet"
        private const val PLUGIN_MESSAGE = "PluginMessage"
    }
}

/** 以跨版本反射提取代理 Plugin Message 频道；无法提取时不可命中白名单。 */
private object ReflectivePluginMessageChannel {

    fun resolve(packet: Any): String? {
        if (!isPluginMessage(packet.javaClass.simpleName)) return null
        val channel = invokeNamed(packet, CHANNEL_METHOD_NAMES) ?: stringField(packet) ?: nestedPayloadChannel(packet)
        return channel ?: UNRESOLVED_CHANNEL
    }

    private fun nestedPayloadChannel(packet: Any): String? = invokeNamed(packet, PAYLOAD_METHOD_NAMES)
        ?.let { payload -> invokeNamed(payload, CHANNEL_METHOD_NAMES + IDENTIFIER_METHOD_NAMES) }

    private fun stringField(packet: Any): String? = generateSequence(packet.javaClass) { it.superclass }
        .flatMap { type -> type.declaredFields.asSequence() }
        .firstOrNull { field -> field.type == String::class.java }
        ?.let { field -> runCatching { field.isAccessible = true; field.get(packet) as? String }.getOrNull() }
        ?.takeIf(String::isNotBlank)

    private fun invokeNamed(target: Any, names: Set<String>): String? = target.javaClass.methods
        .firstOrNull { method -> method.name in names && method.parameterTypes.isEmpty() }
        ?.let { method -> runCatching { method.invoke(target)?.toString() }.getOrNull() }
        ?.takeIf(String::isNotBlank)

    private fun isPluginMessage(simpleName: String): Boolean =
        simpleName.contains("PluginMessage", ignoreCase = true) || simpleName.contains("CustomPayload", ignoreCase = true)

    private const val UNRESOLVED_CHANNEL = "<未解析插件频道>"
    private val CHANNEL_METHOD_NAMES = setOf("getChannel", "channel", "getTag", "tag", "getIdentifier", "identifier")
    private val PAYLOAD_METHOD_NAMES = setOf("getPayload", "payload")
    private val IDENTIFIER_METHOD_NAMES = setOf("getId", "id")
}

/** 从代理公开玩家对象的稳定 getter 提取连接身份，避免平台 API 进入服务签名。 */
object ReflectiveNettyPacketIdentity {

    /** 读取 UUID、名字与远端 IP；缺失信息保留为空，不影响流量聚合。 */
    fun resolve(player: Any, nameGetter: String, addressGetter: String): NettyPacketIdentity = NettyPacketIdentity(
        playerUuid = invoke(player, UUID_GETTER)?.toString(),
        playerName = invoke(player, nameGetter)?.toString(),
        ip = (invoke(player, addressGetter) as? java.net.InetSocketAddress)?.address?.hostAddress,
    )

    private fun invoke(target: Any, name: String): Any? = target.javaClass.methods
        .firstOrNull { method -> method.name == name && method.parameterTypes.isEmpty() }
        ?.let { method -> runCatching { method.invoke(target) }.getOrNull() }

    private const val UUID_GETTER = "getUniqueId"
}
