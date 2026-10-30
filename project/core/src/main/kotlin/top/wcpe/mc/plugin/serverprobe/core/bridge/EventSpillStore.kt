package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.core.config.LineSettings
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * 不可丢事件的落盘兜底（线路治理规格 §3.4）。
 *
 * ## 解决的矛盾
 * "不可丢"必须比"入了内存队列"更强:队列满就是满,内存不提供持久性。
 * 本类把队列接不下的可靠事件写进磁盘,等重连或有容量时回放。
 *
 * ## 提交语义（至少一次）
 * 只有帧**真正被 socket 写出**才推进提交位点,未提交的记录会在下次回放时重发。
 * 重复由 JM 侧按 `dedupKey` 去重——这是刻意的"至少一次",不是"恰好一次"。
 *
 * 早期设计没有位点,只写"回放失败则保留文件",会让同一批记录被无限重试且永不清理
 * （队列满 → 回放被拒 → 保留 → 下次再来）,形成活锁。
 *
 * ## 如何确认"某帧已写出"
 * 回放时 [next] 把帧实例交给调用方,该**同一实例**经可靠队列流到发送线程;
 * 发送成功后调用 [onSent] 用**引用相等**确认是哪条记录。
 * 不用字节数或内容哈希推断:发送顺序与文件顺序不保证一致,推定会推进过头而丢数据。
 *
 * ## 为什么单独一个线程
 * 写盘必须在 `serverprobe-spill` 线程而非事件线程:事件线程被磁盘 IO 拖住会连带
 * 阻塞全部事件上行。[append] 只做内存投递,写盘由 [drainLoop] 执行。
 *
 * ## 文件布局
 * - `spill.jsonl`:追加写,一行一条;
 * - `spill.off`:已提交的字节偏移(单行十进制);
 * - `seq.state`:每域已分配的最大事件序号,见 [seqState]。
 *
 * 崩溃残留的半截尾行视为未提交并在装载时截断。
 */
// 落盘 + 轮转 + 回放 + 位点推进四块各自都要几个小方法（且每条降级路径都要能被单独读出）；
// 合并成少数大函数会让「哪个分支在什么条件下触发」变得不可局部验证。
@Suppress("TooManyFunctions")
class EventSpillStore(private val settings: LineSettings) {

    private val dir = File(settings.spillDir)

    private val dataFile = File(dir, DATA_FILE)

    private val offsetFile = File(dir, OFFSET_FILE)

    /**
     * 事件序号状态（spec §3.3）：落在**同一目录**，与事件缓冲同生命周期。
     *
     * 为什么挂在这里而不是另起一个组件：两者的持久化边界完全一致——都只在
     * `bridge.line.spill-enabled=true` 时存在，都要求"目录可写"，都要在停机时落盘。
     * 让调用方拿一个 [EventSpillStore] 就同时拿到两处状态，装配处少一个可错的接线点。
     */
    val seqState: EventSeqStore = EventSeqStore(dir)

    /** 待写盘队列(有界);满了丢最旧并计数——磁盘也接不下时只能降级。 */
    private val pending = ArrayDeque<OutboundFrame>()

    /**
     * 已写入文件、尚未确认的记录(按文件顺序)。
     *
     * 每项持有帧**实例**,用于 [onSent] 的引用相等判定。
     *
     * ## 为什么它必须有上限
     * 这里的记录代表「已落盘但还没真正写进 socket」的事件。Worker 长期不可达时,
     * 它们既不会被发送、也不会被丢弃——若不加限制,堆内存按「自断连起产生的全部可靠事件」
     * 线性增长,最终 OOM。而 OOM 的后果不是「丢一部分」,是**全部丢失 + 服务端崩溃**,
     * 比主动丢最旧的少量事件严重得多。
     */
    private val unacked = ArrayList<Record>()

    /** 磁盘缓冲也接不下的累计丢弃数。 */
    private val droppedBeyondDisk = AtomicLong(0)

    /** 已提交字节偏移;回放从该处开始。 */
    private val committedOffset = AtomicLong(0)

    /** 文件当前写入位置(追加指针)。 */
    private val writeOffset = AtomicLong(0)

    /**
     * 回放读取位置。
     *
     * 写者有三个：落盘线程（[rotateIfNeeded] 轮转后归零）、连接/读线程
     * （[load] 装载、[next] 推进、[resetReplay] 重连重置）。读也在连接线程。
     * 跨线程可见性必须由 `@Volatile` 保证——否则 [next] 可能读到轮转前的旧值，
     * 在**刚被重写过的文件**里按旧偏移 seek，读出半行（`decode` 返回 null）
     * 导致本轮回放静默中断。
     */
    @Volatile
    private var replayOffset = 0L

    @Volatile
    private var running = true

    /** 装载已有文件:恢复位点、截断半截尾行、重建未确认记录。 */
    fun load() {
        runCatching {
            dir.mkdirs()
            if (offsetFile.isFile) {
                committedOffset.set(offsetFile.readText().trim().toLongOrNull() ?: 0L)
            }
            if (!dataFile.isFile) return
            truncateHalfTail()
            writeOffset.set(dataFile.length())
            rebuildUnacked()
            replayOffset = committedOffset.get()
            ProbeLogger.info(
                "桥落盘事件已装载:已提交 ${committedOffset.get()} 字节,待确认 ${unacked.size} 条,文件 ${dataFile.length()} 字节",
            )
        }.onFailure { ProbeLogger.warn("桥落盘事件装载失败,按空文件处理:${it.javaClass.simpleName}") }
    }

    /**
     * 追加一条到待写盘队列（**不阻塞、不做 IO**）。
     *
     * @return 恒为 true:内存缓冲满时会丢弃最旧的一条并计数,而不是拒绝新的
     *         （新的更接近当前状态;丢弃发生在磁盘长期不可用时,由 [droppedBeyondDisk] 暴露）。
     */
    fun append(frame: OutboundFrame): Boolean = synchronized(pending) {
        if (pending.size >= MAX_PENDING) {
            pending.removeFirst()
            droppedBeyondDisk.incrementAndGet()
        }
        pending.addLast(frame)
        true
    }

    /** 写盘线程主循环:把待写队列刷到文件并 fsync,同步登记未确认记录。 */
    fun drainLoop() {
        while (running) {
            val batch = synchronized(pending) {
                val copy = pending.toList()
                pending.clear()
                copy
            }
            if (batch.isEmpty()) {
                sleepQuietly()
                continue
            }
            runCatching { appendAll(batch) }
                .onFailure { ProbeLogger.warn("桥落盘写入失败(${batch.size} 条):${it.javaClass.simpleName}") }
        }
    }

    /** 重连成功后重置回放位置:未确认的记录会被重新读出并重发。 */
    fun resetReplay() {
        replayOffset = committedOffset.get()
    }

    /**
     * 取下一帧用于回放（从已提交位点顺序读）。
     *
     * 读出后把该帧实例**绑定**到对应的未确认记录上：后续 [onSent] 收到的正是这个实例，
     * 从而能精确确认是哪条记录（帧内容在落盘/装载后已不是原对象）。
     *
     * @return 待重发的帧;已读到文件尾或无文件时返回 null。
     */
    fun next(): OutboundFrame? {
        if (!dataFile.isFile) return null
        return runCatching {
            RandomAccessFile(dataFile, "r").use { raf ->
                if (replayOffset >= raf.length()) return null
                raf.seek(replayOffset)
                val line = raf.readLine() ?: return null
                val start = replayOffset
                replayOffset = raf.filePointer
                val decoded = decode(line) ?: return null
                bindReplayInstance(decoded, start)
                decoded
            }
        }.getOrNull()
    }

    /** 把回放读出的实例绑定到对应记录（按绝对文件偏移定位）。 */
    private fun bindReplayInstance(frame: OutboundFrame, startOffset: Long) {
        synchronized(unacked) {
            val index = unacked.indexOfFirst { it.offset == startOffset }
            if (index >= 0) {
                unacked[index] = unacked[index].copy(frame = frame)
            }
        }
    }

    /**
     * 某帧已成功写出:确认对应记录并推进提交位点。
     *
     * 用**引用相等**定位记录;非本类回放出来的帧(普通事件)直接忽略。
     */
    fun onSent(frame: OutboundFrame) {
        synchronized(unacked) {
            val index = unacked.indexOfFirst { it.frame === frame }
            if (index < 0) return
            unacked[index] = unacked[index].copy(sent = true)
            // 只能推进连续已确认的前缀:位点一旦跳过未确认记录,那条记录就永久丢了。
            var advanced = 0L
            while (unacked.isNotEmpty() && unacked.first().sent) {
                advanced += unacked.removeAt(0).bytes
            }
            if (advanced <= 0L) return
            val next = (committedOffset.get() + advanced).coerceAtMost(writeOffset.get())
            committedOffset.set(next)
            persistOffset(next)
        }
    }

    /** 未提交的条数(供状态展示)。 */
    fun pending(): Int = synchronized(pending) { pending.size + synchronized(unacked) { unacked.size } }

    /**
     * 同步把待写队列刷盘并 fsync（**停机路径用**）。
     *
     * [shutdown] 只置 `running=false`，不 drain `pending`——而停机前一刻被 [append]
     * 受理（返回 true）的可靠事件会因此静默消失，且不计数。正常停机必须调本方法。
     *
     * @return 是否全部落盘（无待写时为 true）。
     */
    fun flush(): Boolean {
        val batch = synchronized(pending) {
            val copy = pending.toList()
            pending.clear()
            copy
        }
        if (batch.isEmpty()) return true
        return runCatching { appendAll(batch); true }
            .onFailure { ProbeLogger.warn("停机前落盘失败(${batch.size} 条):${it.javaClass.simpleName}") }
            .getOrDefault(false)
    }

    /**
     * 同步把待写队列刷盘。
     *
     * 供单测使用:避免依赖后台线程时序而引入 sleep（那会让测试既慢又不稳定）。
     * 生产路径由 [drainLoop] 周期性调用，或由停机路径的 [flush] 调用。
     */
    fun flushForTest() {
        flush()
    }

    /** 磁盘也接不下的累计丢弃数。 */
    fun droppedBeyondDisk(): Long = droppedBeyondDisk.get()

    /** 停止写盘线程。 */
    fun shutdown() {
        running = false
    }

    /** 把一批帧追加写盘并落盘,同时登记未确认记录。 */
    private fun appendAll(batch: List<OutboundFrame>) {
        val records = ArrayList<Record>(batch.size)
        RandomAccessFile(dataFile, "rw").use { raf ->
            val start = raf.length()
            raf.seek(start)
            var cursor = start
            batch.forEach { frame ->
                val bytes = frame.lineBytes()
                raf.write(bytes)
                records.add(Record(frame, bytes.size.toLong(), cursor, sent = false))
                cursor += bytes.size
            }
            raf.fd.sync()
            writeOffset.set(raf.length())
        }
        synchronized(unacked) {
            unacked.addAll(records)
            // 超出硬上限时丢**最旧**并计数。丢最旧而不是拒绝新的：
            // 新事件更接近当前状态，且被丢弃的旧记录已无法送达（它们本就躺在未提交区）。
            var overflow = unacked.size - MAX_UNACKED
            while (overflow > 0) {
                unacked.removeAt(0)
                droppedBeyondDisk.incrementAndGet()
                overflow--
            }
        }
        rotateIfNeeded()
    }

    /**
     * 超过体积上限时轮转:把**未提交的尾部**重写进新文件,丢掉已提交的前缀。
     *
     * ## 两种超限情形必须分开处置
     * - **有已提交前缀** → 正常轮转，丢掉已提交字节；
     * - **无已提交前缀**（Worker 长期不可达）→ 以前的做法是「暂停轮转以免丢未提交事件」，
     *   但这会让文件**无上限增长**直到打满磁盘——届时不是「不丢」而是连同服务端一起崩。
     *   现在改为硬封顶：保留最近的 `spillMaxBytes` 字节，更旧的尾部丢弃并计数，
     *   与 [unacked] 的上限保持同一种降级语义（宁丢旧不崩）。
     */
    private fun rotateIfNeeded() {
        val limit = settings.spillMaxBytes
        if (dataFile.length() <= limit) return
        val committed = committedOffset.get()
        if (committed <= 0L) {
            rotateWithoutCommit(limit)
            return
        }
        runCatching {
            val all = dataFile.readBytes()
            val tail = all.copyOfRange(committed.toInt().coerceAtMost(all.size), all.size)
            dataFile.writeBytes(tail)
            rebaseUnacked(committed)
            committedOffset.set(0L)
            writeOffset.set(tail.size.toLong())
            replayOffset = 0L
            persistOffset(0L)
            ProbeLogger.info("桥落盘文件已轮转,保留未提交 ${tail.size} 字节")
        }.onFailure { ProbeLogger.warn("桥落盘轮转失败:${it.javaClass.simpleName}") }
    }

    /**
     * 无已提交前缀时的硬封顶：保留文件末尾 [limit] 字节（对齐到行边界），更旧的丢弃。
     *
     * 只在与「已提交前缀轮转」同样持 [unacked] 锁的情形下运行，以保证偏移重建与文件重写原子。
     */
    private fun rotateWithoutCommit(limit: Long) {
        runCatching {
            val all = dataFile.readBytes()
            if (all.isEmpty()) return
            // 从尾部往前找落在 limit 边界后的第一个完整行开头。
            var cut = (all.size - limit).toInt().coerceAtLeast(0)
            while (cut < all.size && all[cut] != NEWLINE) cut++
            if (cut < all.size) cut++
            if (cut <= 0) return
            val keep = all.copyOfRange(cut, all.size)
            dataFile.writeBytes(keep)
            // 旧记录全部失效：重建为保留下来的那批（它们的偏移整体前移 cut）。
            rebaseUnackedButDrop(0L, cut.toLong())
            committedOffset.set(0L)
            writeOffset.set(keep.size.toLong())
            replayOffset = 0L
            persistOffset(0L)
            droppedBeyondDisk.addAndGet(1)
            ProbeLogger.warn(
                "桥落盘文件无已提交前缀,按体积上限硬裁切:丢弃 $cut 字节旧尾部,保留 ${keep.size} 字节" +
                    "（Worker 长期不可达时的降级,丢弃总数 ${droppedBeyondDisk.get()}）",
            )
        }.onFailure { ProbeLogger.warn("桥落盘硬裁切失败:${it.javaClass.simpleName}") }
    }

    /**
     * 轮转后重建未确认记录的偏移。
     *
     * ## 为什么必须重算
     * 文件被重写为「原 committed 之后的内容」，新文件从 0 开始。而 [Record.offset] 存的是
     * **重写前的绝对偏移**——不重算的话：
     * 1. [bindReplayInstance] 按新偏移找不到任何记录 → 回放帧无法绑定；
     * 2. [onSent] 按引用相等也找不到 → [committedOffset] 永远停在 0；
     * 3. 于是每次重连都从 0 重放同一批（重复上行），且回放永远无法推进。
     *
     * @param committed 重写前已提交的字节数（新文件的 0 对应它的末尾）。
     */
    private fun rebaseUnacked(committed: Long) {
        synchronized(unacked) {
            val rebuilt = unacked.mapNotNull { record ->
                val shifted = record.offset - committed
                if (shifted < 0L) null else record.copy(offset = shifted)
            }
            unacked.clear()
            unacked.addAll(rebuilt)
        }
    }

    /**
     * 硬裁切后重建未确认记录：丢掉落在被裁掉区间里的旧记录，其余偏移前移 [cut]。
     *
     * @param committed 已提交前缀（无前缀时为 0）。
     * @param cut 从文件头部裁掉的字节数。
     */
    private fun rebaseUnackedButDrop(committed: Long, cut: Long) {
        synchronized(unacked) {
            val rebuilt = unacked.mapNotNull { record ->
                val shifted = record.offset - committed - cut
                if (shifted < 0L) null else record.copy(offset = shifted)
            }
            unacked.clear()
            unacked.addAll(rebuilt)
        }
    }

    /**
     * 装载时重建未确认记录(用于崩溃重启后精确推进位点)。
     *
     * 同样受 [MAX_UNACKED] 约束：文件在上一运行期可能已经很大（断连期间的未提交积累），
     * 若全部装进内存，重启会直接 OOM。只保留**最近**的 [MAX_UNACKED] 条，
     * 更旧的计入 [droppedBeyondDisk]——它们本来也无法在本轮全部回放。
     */
    private fun rebuildUnacked() {
        val all = dataFile.readBytes()
        val committed = committedOffset.get().toInt().coerceIn(0, all.size)
        if (committed >= all.size) return
        var cursor = committed
        // 先收集全部候选，再取尾部 MAX_UNACKED 条（保最新）。
        val candidates = ArrayList<Record>()
        while (cursor < all.size) {
            val end = indexOfNewlineFrom(all, cursor).let { if (it < 0) all.size else it + 1 }
            val line = String(all.copyOfRange(cursor, end), StandardCharsets.UTF_8).trim()
            if (line.isNotEmpty()) {
                decode(line)?.let {
                    candidates.add(Record(it, (end - cursor).toLong(), cursor.toLong(), sent = false))
                }
            }
            cursor = end
        }
        val overflow = candidates.size - MAX_UNACKED
        if (overflow > 0) {
            droppedBeyondDisk.addAndGet(overflow.toLong())
            ProbeLogger.warn("落盘未确认事件超过上限 $MAX_UNACKED,装载时丢弃最旧 $overflow 条")
            // 丢弃的头段必须同时把已提交位点前移——否则回放会从文件开头读到
            // 已被丢弃的记录（它们已不在 unacked 里，永远确认不了，位点就再也推不动）。
            val dropUntil = candidates[overflow].offset
            synchronized(unacked) {
                unacked.addAll(candidates.subList(overflow, candidates.size))
            }
            committedOffset.set(dropUntil)
            replayOffset = dropUntil
            persistOffset(dropUntil)
        } else {
            synchronized(unacked) { unacked.addAll(candidates) }
        }
    }

    /** 从 [from] 起查找第一个换行字节;未找到返回 -1。 */
    private fun indexOfNewlineFrom(bytes: ByteArray, from: Int): Int {
        for (i in from until bytes.size) {
            if (bytes[i] == NEWLINE) return i
        }
        return -1
    }

    /** 截断崩溃残留的半截尾行(最后一个不完整的 JSON 行)。 */
    private fun truncateHalfTail() {
        if (!dataFile.isFile) return
        val bytes = dataFile.readBytes()
        if (bytes.isEmpty()) return
        if (bytes.last() == NEWLINE) return
        val lastNewline = bytes.lastIndexOf(NEWLINE)
        val keep = if (lastNewline < 0) 0 else lastNewline + 1
        dataFile.writeBytes(bytes.copyOfRange(0, keep))
        ProbeLogger.warn("桥落盘文件存在半截尾行,已截断至 $keep 字节")
    }

    private fun persistOffset(offset: Long) {
        runCatching { offsetFile.writeText(offset.toString()) }
    }

    private fun decode(line: String): OutboundFrame? = runCatching {
        // 行格式：type \t event \t domain \t topic \t requestId \t eventSeq \t seqEpoch \t payload
        // 用制表符分隔而非 JSON：落盘格式不该依赖运行期 JSON 库（nightconfig 在裸单测类路径不存在），
        // 也不该随 JSON 库更换而变。
        //
        //：位点（eventSeq / seqEpoch）必须**一起落盘**——补发时沿用原序号，
        // 重新分配会让"补发"在序列上看起来像新事件，而下游正靠 seq 判断缺口。
        val parts = line.split(SEPARATOR)
        if (parts.size < LEGACY_FIELD_COUNT) return null
        // 兼容之前写入的 6 字段行（payload 在 [5]）：老文件不该因升级而读不出来。
        // 那种行的位点必然是 0（当时还没有位点概念），语义正确。
        val legacy = parts.size == LEGACY_FIELD_COUNT
        OutboundFrame(
            type = parts[0],
            event = parts[1],
            domain = parts[2],
            payload = if (legacy) parts[5] else parts[7],
            priority = OutboundFrame.Priority.RELIABLE_EVENT,
            topic = parts[3].ifEmpty { null },
            requestId = parts[4],
            eventSeq = if (legacy) 0L else parts[5].toLongOrNull() ?: 0L,
            seqEpoch = if (legacy) 0L else parts[6].toLongOrNull() ?: 0L,
        )
    }.getOrNull()

    private fun sleepQuietly() {
        runCatching { Thread.sleep(DRAIN_IDLE_MS) }
            .onFailure {
                Thread.currentThread().interrupt()
                running = false
            }
    }

    /** 一条已写入文件、尚未确认的记录。 */
    private data class Record(
        val frame: OutboundFrame,
        val bytes: Long,
        /** 该记录在文件中的绝对起始偏移,用于回放读出的实例与记录配对。 */
        val offset: Long,
        val sent: Boolean,
    )

    private companion object {
        const val DATA_FILE = "spill.jsonl"
        const val OFFSET_FILE = "spill.off"
        const val MAX_PENDING = 4_096

        /**
         * [unacked] 的硬上限（条）。
         *
         * 取 `MAX_PENDING` 的 4 倍：允许「已落盘但还在等回放」的窗口比内存队列宽一些，
         * 但绝不无限——超过即丢最旧并计入 [droppedBeyondDisk]。
         * 以单条事件帧常见 <4KiB 计，上限约 64 MiB 量级，不会自己就把堆睡爆。
         */
        const val MAX_UNACKED = 16_384
        const val DRAIN_IDLE_MS = 200L
        const val NEWLINE = '\n'.code.toByte()

        /** 字段分隔符：选制表符，业务 payload 里不会出现（JSON 文本中不产生裸 TAB）。 */
        const val SEPARATOR = "\t"

        /** 当前行格式的固定字段数（含的位点两列）。 */
        const val FIELD_COUNT = 8

        /** 之前的旧行格式字段数（无位点）；装载时按此识别并兼容。 */
        const val LEGACY_FIELD_COUNT = 6
    }
}

/**
 * 把一帧编码为落盘行(含换行)。
 *
 * 格式为 `type \t event \t domain \t topic \t requestId \t eventSeq \t seqEpoch \t payload \n`。
 *
 * ## 为什么不用 JSON
 * 落盘格式若依赖运行期 JSON 库（nightconfig 在 core 的裸单测类路径不存在），
 * 会带来两个问题：单测无法覆盖位点逻辑；将来更换 JSON 后端会让历史落盘文件不可读。
 * 落盘格式应当是**自持且稳定**的，故用最简分隔符格式。
 *
 * ## 为什么位点也要落盘
 * 补发必须**沿用原序号**。若只落 payload，回放时无从得知原序号，只能重新分配——
 * 于是下游看到的是"一串新事件"，缺口判定彻底失效。
 */
private fun OutboundFrame.lineBytes(): ByteArray {
    val fields = listOf(
        type,
        event,
        domain,
        topic ?: "",
        requestId,
        eventSeq.toString(),
        seqEpoch.toString(),
        payload,
    )
    val line = fields.joinToString("\t") + "\n"
    return line.toByteArray(StandardCharsets.UTF_8)
}
