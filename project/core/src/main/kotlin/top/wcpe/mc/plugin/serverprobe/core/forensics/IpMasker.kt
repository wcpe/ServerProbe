package top.wcpe.mc.plugin.serverprobe.core.forensics

import java.net.InetAddress

/** Prometheus 标签使用的 IP 前缀脱敏器。 */
object IpMasker {

    /** IPv4 保留 /24，IPv6 保留 /64；无法解析时不输出原值。 */
    fun mask(raw: String): String = runCatching {
        val address = InetAddress.getByName(raw)
        if (address.address.size == IPV4_BYTES) maskIpv4(address.address) else maskIpv6(address.address)
    }.getOrDefault(UNKNOWN)

    /** 清除 IPv4 最后一个字节。 */
    private fun maskIpv4(bytes: ByteArray): String =
        "${bytes[0].unsigned()}.${bytes[1].unsigned()}.${bytes[2].unsigned()}.0/24"

    /** 清除 IPv6 后 64 位并使用无压缩的稳定文本形式。 */
    private fun maskIpv6(bytes: ByteArray): String {
        val groups = ArrayList<String>(IPV6_GROUPS)
        for (index in 0 until IPV6_GROUPS) {
            val value = if (index < IPV6_GROUPS / 2) {
                (bytes[index * 2].unsigned() shl BYTE_BITS) or bytes[index * 2 + 1].unsigned()
            } else {
                0
            }
            groups += value.toString(16)
        }
        return groups.joinToString(":") + "/64"
    }

    /** 将有符号 byte 转为无符号整数。 */
    private fun Byte.unsigned(): Int = toInt() and BYTE_MASK

    private const val IPV4_BYTES = 4
    private const val IPV6_GROUPS = 8
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xff
    private const val UNKNOWN = "unknown"
}
