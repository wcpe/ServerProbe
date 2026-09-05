package top.wcpe.mc.plugin.serverprobe.bukkit.forensics

import org.bukkit.entity.Player
import top.wcpe.mc.plugin.serverprobe.api.forensics.PacketDirection
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 以反射接入跨版本 Bukkit 玩家 Netty Channel，并在卸载时幂等移除处理器。 */
internal class BukkitNettyChannelRegistry(
    private val recorder: BukkitPacketForensicsRecorder,
    private val maxPayloadBytes: Int,
) {

    private val attached = ConcurrentHashMap<Any, AttachedChannel>()
    private val sequence = AtomicLong()

    /** 将三个处理器接入登录后已可用的玩家连接；同一 Channel 重复调用不重复插入。 */
    fun attach(player: Player): Boolean {
        val channel = BukkitPlayerChannelLocator.find(player) ?: run {
            ProbeLogger.warn("无法定位玩家 ${player.name} 的 Netty Channel，已跳过本次数据包取证")
            return false
        }
        attached[channel]?.takeIf { it.isInstalled() }?.let { return true }
        attached.remove(channel)?.remove()
        return createAttachment(channel, player)?.let { attachment ->
            attached.putIfAbsent(channel, attachment)?.let { attachment.remove(); true } ?: true
        } ?: run {
            ProbeLogger.warn("无法向玩家 ${player.name} 的 Netty Pipeline 插入取证处理器，已跳过本次数据包取证")
            false
        }
    }

    /** 移除一个玩家连接的所有处理器；连接已关闭或已移除时同样安全。 */
    fun detach(player: Player) {
        BukkitPlayerChannelLocator.find(player)?.let { channel -> attached.remove(channel)?.remove() }
    }

    /** 插件卸载时释放所有可能仍存在的连接处理器。 */
    fun detachAll() {
        attached.entries.toList().forEach { (channel, attachment) ->
            if (attached.remove(channel, attachment)) attachment.remove()
        }
    }

    private fun createAttachment(channel: Any, player: Player): AttachedChannel? {
        val pipeline = ChannelPipelineAccess.create(channel) ?: run {
            ProbeLogger.warn("无法访问玩家 ${player.name} 的 Netty Pipeline")
            return null
        }
        val pairing = BukkitPacketPairing(identityOf(player), recorder)
        val id = sequence.incrementAndGet()
        val names = HandlerNames(id)
        val decoded = pipeline.createHandler(
            inbound = pairing::onInboundPacket,
            outbound = pairing::onOutboundPacket,
            onRemoved = null,
        ) ?: return handlerCreationFailed(player, "已解码")
        val inboundRaw = pipeline.createHandler(
            inbound = { message -> capture(message, PacketDirection.INGRESS)?.let(pairing::onInboundRaw) },
            outbound = {},
            onRemoved = pairing::flush,
        ) ?: return handlerCreationFailed(player, "入站原始")
        val outboundRaw = pipeline.createHandler(
            inbound = {},
            outbound = { message -> capture(message, PacketDirection.EGRESS)?.let(pairing::onOutboundRaw) },
            onRemoved = null,
        ) ?: return handlerCreationFailed(player, "出站原始")
        val decodedAdded = pipeline.addBefore(PACKET_HANDLER, names.decoded, decoded)
        pairing.decodedHandlerAvailable = decodedAdded
        val inboundAdded = pipeline.addBefore(DECODER_HANDLER, names.inboundRaw, inboundRaw)
        val outboundAdded = pipeline.addBefore(ENCODER_HANDLER, names.outboundRaw, outboundRaw)
        if (!inboundAdded && !outboundAdded) {
            ProbeLogger.warn("玩家 ${player.name} 的 Netty Pipeline 未找到 decoder/encoder，现有处理器=${pipeline.names()}")
        }
        return attachment(pipeline, names, pairing, inboundAdded || outboundAdded)
    }

    private fun handlerCreationFailed(player: Player, type: String): AttachedChannel? {
        ProbeLogger.warn("无法为玩家 ${player.name} 创建${type} Netty 取证处理器")
        return null
    }

    private fun attachment(
        pipeline: ChannelPipelineAccess,
        names: HandlerNames,
        pairing: BukkitPacketPairing,
        active: Boolean,
    ): AttachedChannel? {
        if (!active) {
            pipeline.remove(names.all())
            return null
        }
        return AttachedChannel(pipeline, names, pairing)
    }

    private fun capture(message: Any, direction: PacketDirection): RawPacketCapture? =
        runCatching { BukkitNettyByteBufReader.read(message, direction, maxPayloadBytes) }.getOrNull()

    private fun identityOf(player: Player): BukkitPacketIdentity = BukkitPacketIdentity(
        playerUuid = player.uniqueId.toString(),
        playerName = player.name,
        ip = player.address?.address?.hostAddress,
    )

    /** 当前 Channel 的处理器名称与拆除操作。 */
    private class AttachedChannel(
        private val pipeline: ChannelPipelineAccess,
        private val names: HandlerNames,
        private val pairing: BukkitPacketPairing,
    ) {

        fun isInstalled(): Boolean = pipeline.contains(names.inboundRaw) || pipeline.contains(names.outboundRaw)

        fun remove() {
            pairing.flush()
            pipeline.remove(names.all())
        }
    }

    /** 每连接唯一的处理器名称，避免与旧实例及其他插件冲突。 */
    private data class HandlerNames(private val id: Long) {
        val decoded = "serverprobe-forensics-decoded-$id"
        val inboundRaw = "serverprobe-forensics-inbound-$id"
        val outboundRaw = "serverprobe-forensics-outbound-$id"

        fun all(): List<String> = listOf(decoded, inboundRaw, outboundRaw)
    }

    private companion object {
        private const val DECODER_HANDLER = "decoder"
        private const val ENCODER_HANDLER = "encoder"
        private const val PACKET_HANDLER = "packet_handler"
    }
}

/** 从 Bukkit 玩家实现的 NMS 连接对象图中有界寻找 Netty Channel。 */
internal object BukkitPlayerChannelLocator {

    fun find(player: Player): Any? = runCatching {
        val handle = player.javaClass.methods.firstOrNull { it.name == GET_HANDLE && it.parameterTypes.isEmpty() }
            ?.invoke(player) ?: return null
        findFromHandle(handle)
    }.getOrNull()

    /** 供回归测试直接验证连接对象图；生产入口仍只从 Bukkit Player 开始。 */
    internal fun findFromHandle(handle: Any): Any? = runCatching { breadthFirst(handle) }.getOrNull()

    private fun breadthFirst(root: Any): Any? {
        val queue = ArrayDeque<Node>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        queue.add(Node(root, 0))
        while (queue.isNotEmpty() && visited.size < MAX_VISITED_NODES) {
            val node = queue.removeFirst()
            if (!visited.add(node.value)) continue
            if (isChannel(node.value)) return node.value
            if (node.depth < MAX_GRAPH_DEPTH) fields(node.value).forEach { queue.add(Node(it, node.depth + 1)) }
        }
        return null
    }

    private fun fields(value: Any): Sequence<Any> = sequence {
        var type: Class<*>? = value.javaClass
        while (type != null && type.name !in STOP_TYPES) {
            type.declaredFields.filterNot { field -> Modifier.isStatic(field.modifiers) || field.type.isPrimitive }
                .forEach { field ->
                    val child = runCatching { field.isAccessible = true; field.get(value) }.getOrNull()
                    if (child != null) yield(child)
                }
            type = type.superclass
        }
    }

    private fun isChannel(value: Any): Boolean = runCatching {
        val channel = Class.forName(CHANNEL_CLASS, false, value.javaClass.classLoader)
        channel.isAssignableFrom(value.javaClass)
    }.getOrDefault(false)

    private data class Node(val value: Any, val depth: Int)

    private const val GET_HANDLE = "getHandle"
    private const val CHANNEL_CLASS = "io.netty.channel.Channel"
    // Spigot 1.20.1 的 ServerPlayer 对象图包含大量世界状态字段，连接不保证落在前 64 个引用内。
    private const val MAX_GRAPH_DEPTH = 8
    private const val MAX_VISITED_NODES = 1_024
    private val STOP_TYPES = setOf("java.lang.Object", "java.lang.Class")
}

/** 对 Netty ChannelPipeline 的最小反射门面，避免平台模块编译依赖 Netty 版本。 */
internal class ChannelPipelineAccess private constructor(private val pipeline: Any, private val loader: ClassLoader) {

    fun createHandler(inbound: (Any) -> Unit, outbound: (Any) -> Unit, onRemoved: (() -> Unit)?): Any? = runCatching {
        val inboundHandler = Class.forName(INBOUND_HANDLER_CLASS, false, loader)
        val outboundHandler = Class.forName(OUTBOUND_HANDLER_CLASS, false, loader)
        Proxy.newProxyInstance(loader, arrayOf(inboundHandler, outboundHandler), NettyHandler(inbound, outbound, onRemoved))
    }.getOrNull()

    fun addBefore(baseName: String, name: String, handler: Any): Boolean = runCatching {
        if (contains(name)) return true
        method(ADD_BEFORE, 3).invoke(pipeline, baseName, name, handler)
        contains(name)
    }.getOrDefault(false)

    fun contains(name: String): Boolean = runCatching { stringMethod(GET).invoke(pipeline, name) != null }.getOrDefault(false)

    fun names(): List<String> = runCatching {
        @Suppress("UNCHECKED_CAST")
        method(NAMES, 0).invoke(pipeline) as? List<String> ?: emptyList()
    }.getOrDefault(emptyList())

    fun remove(names: List<String>) {
        names.filter(::contains).forEach { name -> runCatching { stringMethod(REMOVE).invoke(pipeline, name) } }
    }

    private fun method(name: String, parameterCount: Int): Method = pipeline.javaClass.methods
        .first { candidate -> candidate.name == name && candidate.parameterTypes.size == parameterCount }
        .apply { isAccessible = true }

    private fun stringMethod(name: String): Method = pipeline.javaClass.methods
        .first { candidate -> candidate.name == name && candidate.parameterTypes.contentEquals(arrayOf(String::class.java)) }
        .apply { isAccessible = true }

    companion object {
        fun create(channel: Any): ChannelPipelineAccess? = runCatching {
            val method = channel.javaClass.methods
                .first { it.name == PIPELINE && it.parameterTypes.isEmpty() }
                .apply { isAccessible = true }
            val pipeline = method.invoke(channel)
            ChannelPipelineAccess(pipeline, channel.javaClass.classLoader)
        }.getOrNull()

        private const val PIPELINE = "pipeline"
        private const val GET = "get"
        private const val ADD_BEFORE = "addBefore"
        private const val REMOVE = "remove"
        private const val NAMES = "names"
        private const val INBOUND_HANDLER_CLASS = "io.netty.channel.ChannelInboundHandler"
        private const val OUTBOUND_HANDLER_CLASS = "io.netty.channel.ChannelOutboundHandler"
    }
}

/** 动态处理器只做本地回调和原样转发，绝不阻塞或保留 Netty 消息对象。 */
private class NettyHandler(
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

    private fun invokeContext(context: Any, name: String, arguments: Array<Any?>): Any? = context.javaClass.methods
        .firstOrNull { candidate -> compatible(candidate, name, arguments) }
        ?.let { method -> invokeMethod(method, context, arguments) }

    private fun invokeMethod(method: Method, context: Any, arguments: Array<Any?>): Any? {
        method.isAccessible = true
        return when (arguments.size) {
            0 -> method.invoke(context)
            1 -> method.invoke(context, arguments[0])
            2 -> method.invoke(context, arguments[0], arguments[1])
            3 -> method.invoke(context, arguments[0], arguments[1], arguments[2])
            else -> null
        }
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
    }
}
