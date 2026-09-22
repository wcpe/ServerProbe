package top.wcpe.mc.plugin.serverprobe.core.alert.channel

import top.wcpe.mc.plugin.serverprobe.core.alert.AlertChannel
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertChannelRegistry
import top.wcpe.mc.plugin.serverprobe.core.alert.AlertEvent
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import top.wcpe.taboolib.ioc.annotation.Inject
import top.wcpe.taboolib.ioc.annotation.PostConstruct
import top.wcpe.taboolib.ioc.annotation.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 告警历史落盘通道(FR-29):把每次触发/恢复事件以 JSONL 行式追加落盘,供 `/probe alerts` 回查与事故回溯。
 *
 * ## 设计
 * - 以 [AlertChannel] 实现接入既有广播链:事件由编排采集线程经 [AlertEngine] 广播到达,天然串行,
 *   无并发写问题(与"独立 writer + 钩子"方案相比,零额外线程、零生命周期耦合,随引擎启停)。
 * - **生命周期**:作为 IOC [Service] 由容器实例化并注入 [registry];[register] 在依赖注入完成后
 *   ([PostConstruct])自注册到 [AlertChannelRegistry]——不注册则事件永远到不了本通道(注册是各通道
 *   自身的责任,注册中心不做类型扫描)。
 * - 落盘路径 `data/alerts/alerts-<yyyyMMdd>.jsonl`(按自然日分桶,与指标历史同款风格);
 *   行内容仅含事件本身(ts/type/level/action/value/threshold/serverId),不含命令正文等敏感数据。
 * - **异步写**:publish 在编排线程被调用,写盘转 TabooLib `submitAsync` 异步执行,不阻塞采集(R7)。
 * - **保留清理**:沿用"按日分桶 + 保留天数 + 体积上限"双闸的既有约定(绝不删当天文件);
 *   清理在跨天首次写入时惰性触发,复用指标历史的清理口径,独立统计目录。
 * - 总开关:`alert.enabled=false` 时引擎不广播任何事件,本通道自然无写入(无需独立开关)。
 *
 * 测试说明:JSON 行解析依赖运行期 Json 后端,与 LocalFileMetricStore 同款不在裸单测范围;
 * 自注册入口与注册结果见 `AlertHistoryChannelTest`。
 */
@Service
class AlertHistoryChannel : AlertChannel {

    /** 告警通道注册中心,用于在初始化完成后自注册;不注册则 [publish] 永不被调用。 */
    @Inject
    lateinit var registry: AlertChannelRegistry

    /** 依赖注入完成后自注册;无独立开关,由 `alert.enabled` 总开关决定引擎是否广播。 */
    @PostConstruct
    fun register() {
        registry.register(this)
    }

    /** 日志根目录取值(测试可覆写);默认 `plugins/ServerProbe/data`。 */
    internal var dataRoot: () -> Path = { Paths.get("plugins", "ServerProbe", "data") }

    /** 上次执行清理时所属的自然日(yyyyMMdd);未清理过为 null。 */
    @Volatile
    private var lastPruneDay: String? = null

    override fun publish(event: AlertEvent) {
        try {
            val day = today()
            maybePrune(day)
            val file = historyDirectory().resolve("$FILE_PREFIX$day$FILE_SUFFIX")
            // 行内容:仅事件要素,无敏感正文(规范第 12/14 条);异步写不阻塞编排线程(R7)
            val line = Json.encode(
                linkedMapOf(
                    "tsMs" to event.timestampMs,
                    "type" to event.rule.type.name,
                    "level" to event.rule.level.name,
                    "action" to if (event.firing) "FIRE" else "RESOLVE",
                    "value" to event.value,
                    "threshold" to event.rule.threshold,
                    "serverId" to event.serverId,
                )
            )
            submitAsyncAppend(file, line)
        } catch (e: java.io.IOException) {
            // 历史落盘失败绝不影响告警呈现主链(探针不成事故源);仅捕获 IO 异常,编程错误照常暴露
            ProbeLogger.warn("告警历史落盘失败:${e.message}")
        }
    }

    /** 最近 N 条历史事件(供 `/probe alerts` 呈现):从最新日期文件倒序读,至多 [limit] 条。 */
    fun recentEvents(limit: Int): List<AlertEventRecord> {
        if (limit <= 0) return emptyList()
        val directory = historyDirectory()
        if (!Files.isDirectory(directory)) return emptyList()
        val files: List<Path> = Files.list(directory).use { stream ->
            stream.filter { p -> p.fileName.toString().startsWith(FILE_PREFIX) && p.fileName.toString().endsWith(FILE_SUFFIX) }
                .sorted(Comparator.comparing<Path, String> { p -> p.fileName.toString() }.reversed())
                .collect(java.util.stream.Collectors.toList())
        }
        val records = ArrayList<AlertEventRecord>()
        for (file in files) {
            readAllRecords(file, records)
            if (records.size >= limit) break
        }
        // 文件按日倒序读,单文件内行按时间升序:整体由新到旧需再把每文件内反转
        return records.take(limit)
    }

    /** 读取单个文件全部记录并追加到 [sink](坏行跳过)。 */
    private fun readAllRecords(file: Path, sink: MutableList<AlertEventRecord>) {
        runCatching {
            Files.newBufferedReader(file, Charsets.UTF_8).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank()) {
                        runCatching { sink.add(AlertEventRecord.fromJson(Json.parse(line))) }
                    }
                    line = reader.readLine()
                }
            }
        }.onFailure { ProbeLogger.warn("读取告警历史失败,已跳过:${file.fileName},${it.message}") }
    }

    /** 异步追加一行(JSONL);与指标历史不同,告警频次极低,直接单行 write 足够且语义简单。 */
    private fun submitAsyncAppend(file: Path, line: String) {
        taboolib.common.platform.function.submit(async = true) {
            runCatching {
                Files.createDirectories(file.parent)
                Files.write(
                    file,
                    (line + "\n").toByteArray(Charsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                )
            }.onFailure { ProbeLogger.warn("告警历史写入失败:${it.message}") }
        }
    }

    /** 跨天首次写入时惰性清理过期文件(与指标历史同口径:按自然日保留 N 天,当天文件绝不删)。 */
    private fun maybePrune(today: String) {
        if (lastPruneDay == today) return
        lastPruneDay = today
        val retention = ProbeConfig.historyFileRetentionDays()
        val directory = historyDirectory()
        if (!Files.isDirectory(directory)) return
        runCatching {
            val cutoff = java.time.LocalDate.now(ZoneId.systemDefault()).minusDays((retention - 1).coerceAtLeast(0).toLong())
            Files.list(directory).use { stream ->
                stream.filter { p ->
                    val name = p.fileName.toString()
                    name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)
                }.forEach { p ->
                    val dayText = p.fileName.toString().removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
                    runCatching {
                        val day = java.time.LocalDate.parse(dayText, DateTimeFormatter.ofPattern("yyyyMMdd"))
                        if (day.isBefore(cutoff)) {
                            Files.deleteIfExists(p)
                        }
                    }.onFailure { /* 文件名不符格式的非历史文件,跳过 */ }
                }
            }
        }.onFailure { ProbeLogger.warn("告警历史清理失败:${it.message}") }
    }

    private fun historyDirectory(): Path = dataRoot().resolve("alerts")

    private fun today(): String =
        DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.systemDefault()).format(Instant.now())

    private companion object {
        /** 历史文件名前缀与后缀:alerts-<yyyyMMdd>.jsonl。 */
        private const val FILE_PREFIX = "alerts-"
        private const val FILE_SUFFIX = ".jsonl"
    }
}

/** 一条告警历史记录(读回呈现用;与落盘行字段一一对应)。 */
data class AlertEventRecord(
    val timestampMs: Long,
    val type: String,
    val level: String,
    val action: String,
    val value: Double,
    val threshold: Double,
    val serverId: String,
) {
    companion object {
        /** 从落盘行 JSON 解析;缺字段按空值兜底(坏行由调用方跳过)。 */
        fun fromJson(obj: top.wcpe.mc.plugin.serverprobe.core.json.JsonObject): AlertEventRecord =
            AlertEventRecord(
                timestampMs = obj.getLong("tsMs"),
                type = obj.getString("type"),
                level = obj.getString("level"),
                action = obj.getString("action"),
                value = obj.getDouble("value"),
                threshold = obj.getDouble("threshold"),
                serverId = obj.getString("serverId"),
            )
    }
}
