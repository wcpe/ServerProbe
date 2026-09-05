package top.wcpe.mc.plugin.serverprobe.bukkit.forensics

import top.wcpe.mc.plugin.serverprobe.api.forensics.PacketDirection
import java.lang.reflect.Method
import java.security.MessageDigest

/** 以反射分块读取服务端 Netty ByteBuf，不把 ByteBuf 或整包移出 EventLoop。 */
internal object BukkitNettyByteBufReader {

    /** 读取摘要和至多 [maxPayloadBytes] 的前缀；非 ByteBuf 消息返回 null。 */
    fun read(message: Any, direction: PacketDirection, maxPayloadBytes: Int): RawPacketCapture? {
        val access = ByteBufAccess.create(message) ?: return null
        val length = access.readableBytes().coerceAtLeast(0)
        val candidate = ByteArray(length.coerceAtMost(maxPayloadBytes.coerceAtLeast(0)))
        val digest = MessageDigest.getInstance(SHA_256)
        consume(access, length, candidate, digest)
        return RawPacketCapture(length, hex(digest.digest()), candidate, fallbackType(direction, candidate))
    }

    private fun consume(access: ByteBufAccess, length: Int, candidate: ByteArray, digest: MessageDigest) {
        var offset = 0
        var candidateOffset = 0
        while (offset < length) {
            val chunk = ByteArray((length - offset).coerceAtMost(CHUNK_BYTES))
            access.getBytes(access.readerIndex() + offset, chunk)
            digest.update(chunk)
            candidateOffset += copyCandidate(chunk, candidate, candidateOffset)
            offset += chunk.size
        }
    }

    private fun copyCandidate(chunk: ByteArray, candidate: ByteArray, offset: Int): Int {
        val copied = (candidate.size - offset).coerceAtLeast(0).coerceAtMost(chunk.size)
        if (copied > 0) System.arraycopy(chunk, 0, candidate, offset, copied)
        return copied
    }

    private fun fallbackType(direction: PacketDirection, candidate: ByteArray): String =
        "bukkit.raw.${direction.name.lowercase()}.${readVarInt(candidate) ?: UNKNOWN_PACKET_ID}"

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
