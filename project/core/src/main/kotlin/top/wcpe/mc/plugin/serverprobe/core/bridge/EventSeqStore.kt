package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 事件序号状态的持久化（spec §3.3 方案 B：重启<b>续接</b>）。
 *
 * ## 为什么需要落盘
 * `seq` 的全部价值在于"下游能凭单一序号判断缺口"。若重启后从 1 重新开始，
 * 下游无法区分「探针重启」与「事件回退」，位点语义立刻失效。
 * 因此每域已分配到的最大序号必须跨重启保留。
 *
 * ## 文件布局
 * 与事件落盘兜底同目录（[top.wcpe.mc.plugin.serverprobe.core.config.LineSettings.getSpillDir]），
 * 文件名 `seq.state`。内容是最简分隔符文本：
 *
 * ```text
 * # ServerProbe 事件序号状态（制表符分隔）
 * epoch	1
 * activity	1024
 * economy	7
 * ```
 *
 * 首行是注释（便于人工打开确认），`epoch` 行是全局 epoch，其余每行「域名 → 已分配的最大 seq」。
 *
 * ## 为什么不用 JSON
 * 与 [EventSpillStore] 同一理由：core 的裸单测类路径没有 nightconfig，
 * 而这段逻辑必须能被单测直接覆盖；且落盘格式应当**自持且稳定**，
 * 不该随运行期 JSON 库更换而变。
 *
 * ## 损坏与丢失的处理
 * 装载结果分三态（[LoadResult]），因为它们的正确处置**完全不同**：
 *
 * | 情形 | 续接起点 | epoch |
 * |---|---|---|
 * | 文件不存在（首次启动） | 1（spec §3.2「起始 1」） | 不变 |
 * | 文件存在但不可解析（丢失/损坏） | `时间戳毫秒 << 20`（spec §3.3 兜底） | 递增 |
 * | 文件可读 | `max + 1` | 沿用 |
 *
 * 把「首次启动」和「文件损坏」分开是必要的：二者若都走时间戳起跳，
 * spec §6 验收 1「连续 emit 100 条 → seq 1..100」在全新服务器上就永远不成立；
 * 若都从 1 起，一次磁盘故障就会让新事件冒充旧位点。
 *
 * **任何情况下都不拒绝发事件**：宁可序号跳变（下游会触发对账），也不能让业务无法上报。
 *
 * ## 线程安全
 * [persist] 只在序号扩容与停机时被调用，内部用一把锁串行化整文件重写——
 * 重写而非追加：文件只有几十行，整写比追加重放（还要考虑轮转与半截行）简单得多。
 */
class EventSeqStore(private val dir: File) {

    private val file = File(dir, STATE_FILE)

    /** 串行化写入：两个域同时扩容时会并发调用 [persist]。 */
    private val writeLock = Any()

    /**
     * 读取已持久化的序号状态。
     *
     * **「文件存在但一行有效内容都没有」判为 [LoadResult.Corrupt]**：那说明文件被截断或被别的
     * 东西覆盖了，"从 1 重新开始"在这个前提下是错的（旧值可能仍在 downstream）。
     * 只有"文件根本不存在"才是真正的首次启动。
     *
     * @return 三态结果（见类注释的表格）；永不抛
     */
    fun load(): LoadResult {
        if (!file.isFile) return LoadResult.Absent
        return runCatching {
            var epoch: Long? = null
            val domains = LinkedHashMap<String, Long>()
            file.readLines(StandardCharsets.UTF_8).forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val parts = line.split(SEPARATOR)
                if (parts.size < 2) return@forEach
                val value = parts[1].toLongOrNull() ?: return@forEach
                if (parts[0] == KEY_EPOCH) {
                    epoch = value
                } else if (parts[0].isNotEmpty()) {
                    domains[parts[0]] = value
                }
            }
            // 一行都没解析出来 = 文件内容不可信，按丢失处理（不猜"是不是首次启动"）。
            if (epoch == null && domains.isEmpty()) return LoadResult.Corrupt
            LoadResult.Present(SeqState(epoch ?: INITIAL_EPOCH, domains))
        }.getOrElse {
            ProbeLogger.warn("事件序号状态读取失败,按持久化丢失处理(${it.javaClass.simpleName})")
            LoadResult.Corrupt
        }
    }

    /**
     * 整份重写状态文件。
     *
     * **绝不抛**：写失败只告警，序号照常分配——磁盘只读或目录不可写时，
     * 让业务事件上报失败是本末倒置（丢的是"下一次重启能否续接"，不是"这条事件能否送达"）。
     *
     * @param epoch 当前全局 epoch
     * @param maxSeqs 域 → 已分配的最大 seq（0 表示该域从未分配过，不写入）
     */
    fun persist(epoch: Long, maxSeqs: Map<String, Long>) {
        synchronized(writeLock) {
            runCatching {
                dir.mkdirs()
                val text = buildString {
                    append("# ServerProbe 事件序号状态（制表符分隔，勿手工编辑）\n")
                    append(KEY_EPOCH).append(SEPARATOR).append(epoch).append('\n')
                    maxSeqs.entries
                        .filter { it.value > 0L }
                        .sortedBy { it.key }
                        .forEach { (domain, maxSeq) ->
                            append(domain).append(SEPARATOR).append(maxSeq).append('\n')
                        }
                }
                file.writeText(text, StandardCharsets.UTF_8)
            }.onFailure {
                ProbeLogger.warn("事件序号状态写入失败(${it.javaClass.simpleName}),本次运行期序号仍在内存中继续")
            }
        }
    }

    /** 状态文件路径（供测试与排障）。 */
    fun filePath(): String = file.absolutePath

    /** 一份序号状态快照。 */
    data class SeqState(
        /** 全局 epoch；下游据此判断"是否需要全量对账"。 */
        val epoch: Long,
        /** 域 → 已分配的最大 seq。 */
        val maxSeqs: Map<String, Long>,
    )

    /**
     * 装载结果的三种形态。
     *
     * 这三态**必须分开**：它们的正确续接起点完全不同（见类注释的表格）。
     * 合成一个可空值会让"首次启动"与"文件损坏"共用同一条兜底路径，
     * 而前者要求从 1 起（spec §3.2）、后者要求时间戳起跳（spec §3.3）。
     */
    sealed class LoadResult {

        /** 文件不存在：全新安装，从 1 起，epoch 不变。 */
        object Absent : LoadResult()

        /** 文件存在但不可解析：持久化丢失，从时间戳起跳并递增 epoch。 */
        object Corrupt : LoadResult()

        /**
         * 读到有效状态。
         *
         * @property state 已持久化的状态
         */
        class Present(val state: SeqState) : LoadResult()
    }

    companion object {

        /** 状态文件名。 */
        const val STATE_FILE = "seq.state"

        /** `epoch` 行的键名（不会被误当作域名：合法域名只含小写字母/数字/连字符）。 */
        const val KEY_EPOCH = "epoch"

        /** 字段分隔符：与 `spill.jsonl` 同选制表符，域名与数字都不可能含它。 */
        const val SEPARATOR = "\t"

        /** 首次启动（无状态文件）时的 epoch。 */
        const val INITIAL_EPOCH = 1L
    }
}
