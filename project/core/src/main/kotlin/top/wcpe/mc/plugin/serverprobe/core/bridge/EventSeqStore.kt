package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
 * 首行是注释（便于人工打开确认），`epoch` 行是全局 epoch，其余每行「域名 → 已分配的最大 seq」，
 * 末行固定是结束标记 `# EOF`（见下节）。
 *
 * ## 为什么不用 JSON
 * 与 [EventSpillStore] 同一理由：core 的裸单测类路径没有 nightconfig，
 * 而这段逻辑必须能被单测直接覆盖；且落盘格式应当**自持且稳定**，
 * 不该随运行期 JSON 库更换而变。
 *
 * ## 完整性判定：结束标记 `# EOF`
 * [persist] 一律走**原子写**（写临时文件 → fsync → 原子改名覆盖），但仍保留末行结束标记，
 * 因为原子写只能防"我们自己正写到一半被打断"，防不住：文件系统不支持原子改名（回退路径）、
 * 容器镜像/运维脚本把文件截断、磁盘损坏只坏了尾部。
 *
 * 判据必须比"有没有解析出内容"严格。直接 `writeText` 是「截断后重写」，中途崩溃会留下
 * **可解析的前缀**（注释行 + epoch 行 + 若干域行）；若按"能解析出东西就算正常"处理，
 * 各域会从**偏小的 max + 1** 续接，与已经发出去的号**静默重号**，而且 epoch 没变，
 * 下游连"该对账"的信号都收不到。这比跳号严重得多——跳号至少会让下游看见缺口并触发对账。
 *
 * **向后兼容上的取舍**：老版本写出的文件没有结束标记，升级后第一次启动会被判 [LoadResult.Corrupt]
 * → 从时间戳起跳并递增 epoch，下游因此做一次全量对账。这是一次性代价；
 * 换来的是"任何不完整文件都不可能被误当成有效状态"。两者相权：
 * 宁可重启跳号（可见、可对账），不可静默重号（下游无从察觉）。
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
 *
 * 这把锁只保证"同一次调用内部不自相交"，**不保证多次调用之间的先后**：
 * 两个线程各自在锁外算好快照、再先后进入 [persist] 时，"后算的"可能"先写"，
 * 磁盘就被覆盖成更小的值（重启即重号）。要挡住这一点，快照的生成与提交必须由调用方
 * 串行化——见 `EventSeqAllocator.persistNow`，那一层的锁才是位点语义正确性的来源。
 */
class EventSeqStore(private val dir: File) {

    private val file = File(dir, STATE_FILE)

    /** 串行化写入：两个域同时扩容时会并发调用 [persist]。 */
    private val writeLock = Any()

    /**
     * 读取已持久化的序号状态。
     *
     * **「文件存在但没有结束标记」以及「一行有效内容都没有」都判为 [LoadResult.Corrupt]**：
     * 前者说明这份文件是写不完整的前缀（典型形态是截断），后者说明被别的东西覆盖了。
     * 两种情形下"从 1 或从小值重新开始"都是错的（旧值可能仍在 downstream）。
     * 只有"文件根本不存在"才是真正的首次启动。
     *
     * @return 三态结果（见类注释的表格）；永不抛
     */
    fun load(): LoadResult {
        if (!file.isFile) return LoadResult.Absent
        return runCatching {
            var epoch: Long? = null
            var sawEndMarker = false
            val domains = LinkedHashMap<String, Long>()
            file.readLines(StandardCharsets.UTF_8).forEach { raw ->
                val line = raw.trim()
                if (line == END_MARKER) {
                    sawEndMarker = true
                    return@forEach
                }
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
            // 没有结束标记 = 这份文件"写不完整"（典型形态：截断出来的可解析前缀）。
            // 必须在这里拦住：它的 epoch 行与部分域行都读得出来，按"能解析就算正常"处理，
            // 各域就会从偏小的 max + 1 续接 → 与已发出的号静默重号，epoch 又没变，
            // 下游连"该对账"都无从得知。宁可判损坏、走时间戳起跳（下游做一次全量对账）。
            if (!sawEndMarker) {
                ProbeLogger.warn("事件序号状态缺少结束标记($END_MARKER),按写不完整处理")
                return LoadResult.Corrupt
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
     * **原子写**：先写同目录的临时文件并 fsync，再原子改名覆盖目标。
     * 不用 `file.writeText`——那是「截断后重写」，中途崩溃/断电会留下**可解析的前缀**，
     * 下次启动从偏小的 max 续接即静默重号（详见类注释）。
     *
     * **本方法只保证单次调用内部的原子性，不保证多次调用之间的先后**：
     * 若两个线程各自在锁外算好快照再先后进来，先算出的旧快照可能后落盘。
     * 调用方必须让"快照生成 + 提交"整体串行化（见 `EventSeqAllocator.persistNow`）。
     *
     * @param epoch 当前全局 epoch
     * @param maxSeqs 域 → 已分配的最大 seq（0 表示该域从未分配过，不写入）
     */
    fun persist(epoch: Long, maxSeqs: Map<String, Long>) {
        synchronized(writeLock) {
            // 临时文件放在**同一目录**：跨文件系统的改名没有原子语义，只有同目录 rename 才有。
            val tmp = File(dir, STATE_FILE + TMP_SUFFIX)
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
                    // 结束标记必须是**最后一行**：截断一定把它丢掉，[load] 据此识别不完整文件。
                    append(END_MARKER).append('\n')
                }
                // 先落数据再改名：只改名不 fsync 的话，改名成功但内容可能还留在页缓存里，
                // 断电后会得到一个"存在且带结束标记、但内容全零/半截"的文件。
                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
                runCatching {
                    Files.move(
                        tmp.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }.recoverCatching {
                    // ATOMIC_MOVE 并非所有文件系统都支持（部分网络盘、容器叠加层会抛
                    // AtomicMoveNotSupportedException）。退回普通改名：不具原子语义，
                    // 但至少不是"截断后重写"——失败窗口从"整份内容的一半"缩到两次目录项更新之间，
                    // 且结束标记仍能兜住不完整文件（宁可判损坏，不可静默重号）。
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }.getOrThrow()
            }.onFailure {
                // 清掉残留临时文件：写失败时它可能是半截的，留着只会误导排障。
                runCatching { tmp.delete() }
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

        /**
         * 结束标记行（文件的最后一行）。
         *
         * [load] 见不到它就判文件"写不完整"：截断/半截文件必然丢掉末行，而它前面的内容
         * 仍可解析——只看"解析出了什么"是无法识别这种形态的。
         */
        const val END_MARKER = "# EOF"

        /** 原子写的临时文件后缀；与目标同目录，保证改名不跨文件系统。 */
        const val TMP_SUFFIX = ".tmp"
    }
}
