package top.wcpe.mc.plugin.serverprobe.e2e

import java.nio.charset.StandardCharsets

/**
 * 帧分片 E2E 用的**确定性**大回执载荷。
 *
 * ## 为什么必须在两处用同一份构造规则
 * 服务端（业务 Provider）返回这段载荷，fixture 侧要断言"重组后字节与期望完全相同"。
 * 若两侧各自手写一份内容，任何一处笔误都会让断言失去意义；若只在一侧生成、
 * 另一侧"从收到的内容反推期望"，则等于什么都没校验。因此统一由本对象生成。
 *
 * ## 内容形态
 * `首标记 + 需转义的特殊字符块 + 填充 + 尾标记`，总 UTF-8 字节数恰为 [TARGET_BYTES]：
 * - 首尾标记让"丢了开头/结尾"这类截断一眼可辨；
 * - 特殊字符块含 `"`、`\`、换行、中文与 emoji：分片走 base64 后 `data` 不应出现任何需转义字符，
 *   而重组出的原文必须逐字节还原这些字符（规格 §3.1 / §5）；
 * - 填充是固定字母表重复，保证跨次运行、跨进程完全一致。
 */
internal object ChunkFixturePayload {

    /** 目标载荷字节数：**恰好** 1 MiB（规格 §5 的 E2E 判据）。 */
    const val TARGET_BYTES = 1_048_576

    /** 首标记：重组后必须原样出现在最前面。 */
    const val HEAD_MARKER = "<<e2e-chunk-fixture-head>>"

    /** 尾标记：重组后必须原样出现在最后面。 */
    const val TAIL_MARKER = "<<e2e-chunk-fixture-tail>>"

    /** 需转义/多字节的特殊字符块（引号、反斜杠、换行、中文、emoji）。 */
    const val SPECIAL_CHARS = "\"quote\"\\backslash\nnewline 中文😀"

    /** 固定填充字母表；重复它比随机数更容易在失败时肉眼比对。 */
    private const val FILLER = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    /** 补足到目标字节数用的单字节填充字符。 */
    private const val PAD_CHAR = '.'

    /**
     * 构造与 Provider 完全一致的载荷文本（结果缓存，避免每次命令重算 1 MiB）。
     *
     * 用 `by lazy` 而不是常量：常量会在类初始化时就构建，而 Provider 可能只想看 manifest。
     */
    val text: String by lazy { buildText() }

    /** 载荷文本的 UTF-8 字节，即 fixture 侧"字节完全相同"比较的期望值。 */
    val bytes: ByteArray by lazy { text.toByteArray(StandardCharsets.UTF_8) }

    /** 按固定规则拼接载荷；结果字节数必须精确等于 [TARGET_BYTES]。 */
    private fun buildText(): String {
        val builder = StringBuilder(TARGET_BYTES)
        val head = HEAD_MARKER + SPECIAL_CHARS
        builder.append(head)
        var bytes = head.toByteArray(StandardCharsets.UTF_8).size
        val tailBytes = TAIL_MARKER.toByteArray(StandardCharsets.UTF_8).size
        while (bytes + tailBytes + FILLER.length <= TARGET_BYTES) {
            builder.append(FILLER)
            bytes += FILLER.length
        }
        // 剩余空隙用单字节字符逐字补齐：填充段全是 ASCII，故"字符数"与"字节数"一致。
        repeat(TARGET_BYTES - bytes - tailBytes) { builder.append(PAD_CHAR) }
        builder.append(TAIL_MARKER)
        val payload = builder.toString()
        check(payload.toByteArray(StandardCharsets.UTF_8).size == TARGET_BYTES) { "分片载荷长度不足 $TARGET_BYTES 字节" }
        return payload
    }
}
