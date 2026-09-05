package top.wcpe.mc.plugin.serverprobe.core.forensics

import java.security.MessageDigest
import java.util.Base64

/** 数据包取证的白名单、载荷长度与哈希策略。 */
class PacketCapturePolicy(
    private val packetTypeWhitelist: Set<String>,
    private val pluginChannelWhitelist: Set<String>,
    private val maxPayloadBytes: Int,
) {

    /**
     * 生成取证载荷摘要。
     *
     * 哈希始终基于原始完整包；实际载荷仅在类型白名单和 Plugin Message 通道双白名单均满足时保存。
     */
    fun capture(packetType: String, channel: String?, payload: ByteArray): CapturedPacketPayload {
        return capture(packetType, channel, payload.size, sha256(payload), payload)
    }

    /**
     * 接受网络线程已分块计算的摘要与有界候选载荷。
     *
     * 调用方只能传入至多 [maxPayloadBytes] 的候选字节，避免为计算完整包哈希而复制整包；白名单规则仍由本策略统一裁决。
     */
    fun capture(
        packetType: String,
        channel: String?,
        originalLength: Int,
        sha256: String,
        candidatePayload: ByteArray,
    ): CapturedPacketPayload {
        val permitted = packetType in packetTypeWhitelist && (channel == null || channel in pluginChannelWhitelist)
        val captured = if (permitted) candidatePayload.copyOf(candidatePayload.size.coerceAtMost(maxPayloadBytes)) else null
        return CapturedPacketPayload(
            originalLength = originalLength.coerceAtLeast(0),
            sha256 = sha256,
            payloadBase64 = captured?.let(Base64.getEncoder()::encodeToString),
            truncated = captured != null && captured.size < originalLength,
        )
    }

    /** SHA-256 以小写十六进制表达，便于查询和跨平台比对。 */
    private fun sha256(payload: ByteArray): String = MessageDigest.getInstance(SHA_256)
        .digest(payload)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        private const val SHA_256 = "SHA-256"
    }
}

/** 一条取证记录保存的载荷摘要。 */
data class CapturedPacketPayload(
    val originalLength: Int,
    val sha256: String,
    val payloadBase64: String?,
    val truncated: Boolean,
)
