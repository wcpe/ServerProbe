package top.wcpe.mc.plugin.serverprobe.core.forensics

import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkForensicsStatus
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketPage
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketQuery
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketRecord
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 仅由单独写线程接触 SQLite 的本地数据包取证库。
 *
 * 网络线程只调用无阻塞 [enqueue]；数据库写入、保留清理和压缩均在 [writerThread] 中完成。
 */
class SqlitePacketForensicsStore(
    private val databasePath: Path,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val retentionDays: Int = DEFAULT_RETENTION_DAYS,
    private val maxDatabaseBytes: Long = DEFAULT_MAX_DATABASE_BYTES,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val driverLoader: () -> Unit = { Class.forName(SQLITE_DRIVER) },
    private val onWarning: (String) -> Unit = ProbeLogger::warn,
) : PacketForensicsStore {

    private val queue = ArrayBlockingQueue<PacketForensicsObservation>(queueCapacity.coerceAtLeast(1))
    private val closed = AtomicBoolean(false)
    private val available = AtomicBoolean(false)
    private val pending = AtomicInteger(0)
    private val dropped = AtomicLong(0)

    @Volatile
    private var unavailableReason: String? = null

    private var lastPruneMs = Long.MIN_VALUE
    private val writerThread: Thread? = initialize()

    /** 网络线程仅复制好的不可变元数据引用并立即返回，绝不等待数据库。 */
    override fun enqueue(observation: PacketForensicsObservation): Boolean {
        if (!available.get() || closed.get()) {
            return false
        }
        pending.incrementAndGet()
        if (queue.offer(observation)) {
            return true
        }
        pending.decrementAndGet()
        dropped.incrementAndGet()
        return false
    }

    /** 读取方自行承担数据库读取线程语义；查询条件先收敛再使用参数绑定拼接。 */
    override fun query(query: NetworkPacketQuery): NetworkPacketPage {
        validateQuery(query)
        if (!available.get()) {
            return NetworkPacketPage.empty()
        }
        return runCatching { openConnection().use { connection -> query(connection, query) } }
            .getOrElse { error ->
                markUnavailable("SQLite 查询失败，已关闭取证：${error.javaClass.simpleName}")
                NetworkPacketPage.empty()
            }
    }

    override fun status(): NetworkForensicsStatus = NetworkForensicsStatus.builder()
        .available(available.get())
        .droppedRecords(dropped.get())
        .unavailableReason(unavailableReason)
        .build()

    /** 供单元测试与有序关闭等待既有队列完全被写线程处理。 */
    fun awaitIdle(timeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (pending.get() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(IDLE_WAIT_MILLIS)
        }
        return pending.get() == 0
    }

    /** 当前取证文件及 WAL/共享内存辅助文件的物理占用。 */
    fun databaseBytes(): Long = databaseFiles().sumOf { path -> Files.size(path) }

    /** 先停止接收新记录，再让单写线程排空队列并释放 SQLite 文件句柄。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        writerThread?.join(CLOSE_TIMEOUT_MILLIS)
    }

    /** 初始化驱动、Schema 和写线程；驱动或原生库失败时仅降级取证。 */
    // SQLite JDBC 还可能在加载本地原生库时抛 Error，必须按 FR11 降级而非阻断插件，故 catch(Throwable) 有意为之。
    @Suppress("TooGenericExceptionCaught")
    private fun initialize(): Thread? = try {
        databasePath.parent?.let(Files::createDirectories)
        driverLoader()
        openConnection().use(::createSchema)
        available.set(true)
        Thread(::writeLoop, WRITER_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    } catch (error: Throwable) {
        // SQLite JDBC 还可能在加载本地原生库时抛 Error，必须按 FR11 降级而非阻断插件。
        markUnavailable("SQLite JDBC 驱动不可用，数据包取证已关闭：${error.javaClass.simpleName}")
        null
    }

    /** 单写线程按有限批次提交，任何数据库 IO 都不会落到 Netty EventLoop。 */
    private fun writeLoop() {
        runCatching {
            openConnection().use { connection ->
                while (!closed.get() || queue.isNotEmpty()) {
                    val batch = takeBatch() ?: continue
                    try {
                        writeBatch(connection, batch)
                    } finally {
                        pending.addAndGet(-batch.size)
                    }
                }
            }
        }.onFailure { error ->
            markUnavailable("SQLite 写入失败，数据包取证已关闭：${error.javaClass.simpleName}")
            drainPending()
        }
    }

    /** 至少等待一条记录，再无阻塞地补齐当前批次。 */
    private fun takeBatch(): List<PacketForensicsObservation>? {
        val first = queue.poll(WRITER_POLL_MILLIS, TimeUnit.MILLISECONDS) ?: return null
        return buildList(batchSize.coerceAtLeast(1)) {
            add(first)
            queue.drainTo(this, batchSize.coerceAtLeast(1) - 1)
        }
    }

    /** 一批记录只提交一次事务，随后在同一写线程执行限频清理。 */
    private fun writeBatch(connection: Connection, batch: List<PacketForensicsObservation>) {
        connection.autoCommit = false
        // 批量提交失败需对任意异常回滚后原样上抛,不允许吞掉,故 catch(Exception) 有意为之。
        @Suppress("TooGenericExceptionCaught")
        try {
            connection.prepareStatement(INSERT_SQL).use { statement ->
                batch.forEach { observation -> bindInsert(statement, observation); statement.addBatch() }
                statement.executeBatch()
            }
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
        pruneIfDue(connection)
    }

    /** 过期清理每分钟至多一次；容量上限每批检查，溢出即从最早证据开始删除。 */
    private fun pruneIfDue(connection: Connection) {
        val now = nowMs()
        var deleted = 0
        if (lastPruneMs == Long.MIN_VALUE || now - lastPruneMs >= PRUNE_INTERVAL_MILLIS) {
            lastPruneMs = now
            deleted = deleteExpired(connection, now - retentionDays.coerceAtLeast(0) * DAY_MILLIS)
        }
        while (databaseBytes() > maxDatabaseBytes.coerceAtLeast(0) && deleteOldest(connection) > 0) {
            deleted += PRUNE_BATCH_SIZE
            compact(connection)
        }
        if (deleted > 0) {
            compact(connection)
        }
    }

    /** 使用时间与自增标识的复合顺序删除，确保并列时间戳也严格最早优先。 */
    private fun deleteExpired(connection: Connection, cutoffMs: Long): Int =
        connection.prepareStatement(DELETE_EXPIRED_SQL).use { statement ->
            statement.setLong(1, cutoffMs)
            statement.executeUpdate()
        }

    /** 删除一个小批次，避免容量回收时生成过大的单次 SQLite 事务。 */
    private fun deleteOldest(connection: Connection): Int {
        val removable = recordCount(connection) - 1
        if (removable <= 0) {
            return 0
        }
        return connection.prepareStatement(DELETE_OLDEST_SQL).use { statement ->
            statement.setInt(1, removable.coerceAtMost(PRUNE_BATCH_SIZE))
            statement.executeUpdate()
        }
    }

    /** 容量清理始终保留最新一条证据，除非该单条自身已经超过运维配置的极小上限。 */
    private fun recordCount(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(QUERY_RECORD_COUNT).use { result ->
                result.next()
                result.getInt(1)
            }
        }

    /** 容量已溢出时才压缩，物理文件上限才能在删除后实际回落。 */
    private fun compact(connection: Connection) {
        runCatching {
            connection.createStatement().use { statement ->
                statement.execute(QUERY_VACUUM)
                statement.execute(QUERY_CHECKPOINT)
            }
        }.onFailure { error ->
            onWarning("SQLite 取证库压缩失败，将在下次清理重试：${error.javaClass.simpleName}")
        }
    }

    /** 构造动态过滤 SQL，同时始终使用预编译参数，避免查询条件形成注入面。 */
    private fun query(connection: Connection, query: NetworkPacketQuery): NetworkPacketPage {
        val parameters = ArrayList<Any>()
        val sql = StringBuilder(SELECT_PREFIX).apply {
            append(" captured_at_ms >= ? AND captured_at_ms <= ?")
            parameters += query.sinceMs!!
            parameters += query.untilMs!!
            appendOptional(this, "direction", query.direction?.name, parameters)
            appendOptional(this, "packet_type", query.packetType, parameters)
            appendOptional(this, "player_uuid", query.playerUuid, parameters)
            appendOptional(this, "player_name", query.playerName, parameters)
            appendOptional(this, "ip", query.ip, parameters)
            appendCursor(this, query, parameters)
            append(" ORDER BY captured_at_ms DESC, id DESC LIMIT ?")
            parameters += query.limit + 1
        }
        return connection.prepareStatement(sql.toString()).use { statement ->
            parameters.forEachIndexed { index, value -> bindQuery(statement, index + 1, value) }
            statement.executeQuery().use { result -> page(result, query.limit) }
        }
    }

    /** 读取一条额外记录以判定下一页，而不把该条交给调用方。 */
    private fun page(result: ResultSet, limit: Int): NetworkPacketPage {
        val records = ArrayList<NetworkPacketRecord>(limit)
        while (result.next() && records.size <= limit) {
            records += record(result)
        }
        val hasNext = records.size > limit
        if (hasNext) {
            records.removeAt(records.lastIndex)
        }
        val cursor = records.lastOrNull()
        return NetworkPacketPage.builder()
            .records(records)
            .nextCursorCapturedAtMs(cursor?.capturedAtMs)
            .nextCursorId(cursor?.id)
            .hasNextPage(hasNext)
            .build()
    }

    /** 将数据库行转换为 API 不可变模型，二进制白名单载荷返回安全的 Base64 文本。 */
    private fun record(result: ResultSet): NetworkPacketRecord = NetworkPacketRecord.builder()
        .id(result.getLong("id"))
        .capturedAtMs(result.getLong("captured_at_ms"))
        .direction(enumValueOf(result.getString("direction")))
        .playerUuid(result.getString("player_uuid"))
        .playerName(result.getString("player_name"))
        .ip(result.getString("ip"))
        .packetType(result.getString("packet_type"))
        .channel(result.getString("channel"))
        .originalLength(result.getInt("original_length"))
        .payloadSha256(result.getString("payload_sha256"))
        .payloadBase64(result.getBytes("payload")?.let(Base64.getEncoder()::encodeToString))
        .payloadCaptured(result.getBoolean("payload_captured"))
        .payloadTruncated(result.getBoolean("payload_truncated"))
        .build()

    /** 统一 SQLite 连接初始化；WAL 与 busy_timeout 只作用于取证库。 */
    private fun openConnection(): Connection = DriverManager.getConnection(databaseUrl()).also { connection ->
        connection.createStatement().use { statement ->
            statement.execute(QUERY_WAL)
            statement.execute(QUERY_BUSY_TIMEOUT)
            statement.execute(QUERY_SYNCHRONOUS)
        }
    }

    /** 新库创建 Schema 与索引；旧库重复执行保持幂等。 */
    private fun createSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(QUERY_AUTO_VACUUM)
            statement.execute(CREATE_RECORDS_TABLE)
            CREATE_INDEXES.forEach(statement::execute)
        }
    }

    /** 标记不可用只告警一次，避免持续写失败刷屏。 */
    private fun markUnavailable(reason: String) {
        if (available.getAndSet(false) || unavailableReason == null) {
            unavailableReason = reason
            onWarning(reason)
        }
    }

    /** 写线程异常后将尚未写入的条目从待处理计数中扣除，防止关闭等待永不返回。 */
    private fun drainPending() {
        var removed = 0
        while (queue.poll() != null) {
            removed++
        }
        pending.addAndGet(-removed)
    }

    /** 连接 URL 使用绝对路径，避免不同平台工作目录变化写到意外位置。 */
    private fun databaseUrl(): String = "jdbc:sqlite:${databasePath.toAbsolutePath()}"

    /** 取证数据库实际可能产生 WAL/共享内存辅助文件，容量上限必须把它们一并计入。 */
    private fun databaseFiles(): Sequence<Path> = sequenceOf(
        databasePath,
        databasePath.resolveSibling(databasePath.fileName.toString() + WAL_SUFFIX),
        databasePath.resolveSibling(databasePath.fileName.toString() + SHM_SUFFIX),
    ).filter(Files::exists)

    /** 参数绑定仅接受本查询内生成的 String、Long、Int 类型。 */
    private fun bindQuery(statement: PreparedStatement, index: Int, value: Any) {
        when (value) {
            is Long -> statement.setLong(index, value)
            is Int -> statement.setInt(index, value)
            is String -> statement.setString(index, value)
            else -> throw IllegalArgumentException("不支持的取证查询参数类型")
        }
    }

    /** 取证写入的载荷已由白名单策略决定，未捕获时数据库字段明确为 null。 */
    private fun bindInsert(statement: PreparedStatement, observation: PacketForensicsObservation) {
        statement.setLong(1, observation.capturedAtMs)
        statement.setString(2, observation.direction.name)
        statement.setString(3, observation.playerUuid)
        statement.setString(4, observation.playerName)
        statement.setString(5, observation.ip)
        statement.setString(6, observation.packetType)
        statement.setString(7, observation.channel)
        statement.setInt(8, observation.payload.originalLength)
        statement.setString(9, observation.payload.sha256)
        statement.setBytes(10, observation.payload.payloadBase64?.let(Base64.getDecoder()::decode))
        statement.setBoolean(11, observation.payload.payloadBase64 != null)
        statement.setBoolean(12, observation.payload.truncated)
    }

    /** 查询边界必须在 IO 前判定，避免调用者不慎发起无界或超页查询。 */
    private fun validateQuery(query: NetworkPacketQuery) {
        requireNotNull(query.sinceMs) { "取证查询必须提供起始时间" }
        requireNotNull(query.untilMs) { "取证查询必须提供结束时间" }
        require(query.sinceMs <= query.untilMs) { "取证查询时间范围无效" }
        require(query.limit in MIN_PAGE_SIZE..MAX_PAGE_SIZE) { "取证查询每页最多 $MAX_PAGE_SIZE 条" }
        require((query.cursorCapturedAtMs == null) == (query.cursorId == null)) { "取证查询游标必须完整" }
    }

    private companion object {
        private const val SQLITE_DRIVER = "org.sqlite.JDBC"
        private const val WRITER_THREAD_NAME = "ServerProbe-Forensics-SQLite"
        private const val DEFAULT_QUEUE_CAPACITY = 8_192
        private const val DEFAULT_BATCH_SIZE = 200
        private const val DEFAULT_RETENTION_DAYS = 60
        private const val DEFAULT_MAX_DATABASE_BYTES = 4L * 1_024L * 1_024L * 1_024L
        private const val DEFAULT_IDLE_TIMEOUT_MILLIS = 5_000L
        private const val IDLE_WAIT_MILLIS = 5L
        private const val CLOSE_TIMEOUT_MILLIS = 5_000L
        private const val WRITER_POLL_MILLIS = 100L
        private const val PRUNE_INTERVAL_MILLIS = 60_000L
        private const val PRUNE_BATCH_SIZE = 1_000
        private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
        private const val MIN_PAGE_SIZE = 1
        private const val MAX_PAGE_SIZE = 100
        private const val WAL_SUFFIX = "-wal"
        private const val SHM_SUFFIX = "-shm"

        private const val QUERY_WAL = "PRAGMA journal_mode=WAL"
        private const val QUERY_BUSY_TIMEOUT = "PRAGMA busy_timeout=5000"
        private const val QUERY_SYNCHRONOUS = "PRAGMA synchronous=NORMAL"
        private const val QUERY_AUTO_VACUUM = "PRAGMA auto_vacuum=INCREMENTAL"
        private const val QUERY_CHECKPOINT = "PRAGMA wal_checkpoint(TRUNCATE)"
        private const val QUERY_VACUUM = "VACUUM"
        private const val QUERY_RECORD_COUNT = "SELECT COUNT(*) FROM network_packet_records"
        private const val CREATE_RECORDS_TABLE = """
            CREATE TABLE IF NOT EXISTS network_packet_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                captured_at_ms INTEGER NOT NULL,
                direction TEXT NOT NULL,
                player_uuid TEXT,
                player_name TEXT,
                ip TEXT,
                packet_type TEXT NOT NULL,
                channel TEXT,
                original_length INTEGER NOT NULL,
                payload_sha256 TEXT NOT NULL,
                payload BLOB,
                payload_captured INTEGER NOT NULL,
                payload_truncated INTEGER NOT NULL
            )
        """
        private val CREATE_INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS idx_packet_capture ON network_packet_records(captured_at_ms DESC, id DESC)",
            "CREATE INDEX IF NOT EXISTS idx_packet_direction ON network_packet_records(direction, captured_at_ms DESC, id DESC)",
            "CREATE INDEX IF NOT EXISTS idx_packet_type ON network_packet_records(packet_type, captured_at_ms DESC, id DESC)",
            "CREATE INDEX IF NOT EXISTS idx_packet_player_uuid ON network_packet_records(player_uuid, captured_at_ms DESC, id DESC)",
            "CREATE INDEX IF NOT EXISTS idx_packet_player_name ON network_packet_records(player_name, captured_at_ms DESC, id DESC)",
            "CREATE INDEX IF NOT EXISTS idx_packet_ip ON network_packet_records(ip, captured_at_ms DESC, id DESC)",
        )
        private const val INSERT_SQL = """
            INSERT INTO network_packet_records (
                captured_at_ms, direction, player_uuid, player_name, ip, packet_type, channel,
                original_length, payload_sha256, payload, payload_captured, payload_truncated
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        private const val DELETE_EXPIRED_SQL = "DELETE FROM network_packet_records WHERE captured_at_ms < ?"
        private const val DELETE_OLDEST_SQL = """
            DELETE FROM network_packet_records WHERE id IN (
                SELECT id FROM network_packet_records ORDER BY captured_at_ms ASC, id ASC LIMIT ?
            )
        """
        private const val SELECT_PREFIX = "SELECT * FROM network_packet_records WHERE"

        private fun appendOptional(sql: StringBuilder, column: String, value: String?, parameters: MutableList<Any>) {
            if (value != null) {
                sql.append(" AND ").append(column).append(" = ?")
                parameters += value
            }
        }

        private fun appendCursor(sql: StringBuilder, query: NetworkPacketQuery, parameters: MutableList<Any>) {
            val capturedAtMs = query.cursorCapturedAtMs ?: return
            val id = query.cursorId!!
            sql.append(" AND (captured_at_ms < ? OR (captured_at_ms = ? AND id < ?))")
            parameters += capturedAtMs
            parameters += capturedAtMs
            parameters += id
        }
    }
}
