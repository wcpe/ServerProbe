package top.wcpe.mc.plugin.serverprobe.core.config

import top.wcpe.mc.plugin.serverprobe.core.control.AtomicFileWrite
import top.wcpe.mc.plugin.serverprobe.core.json.JsonText
import top.wcpe.mc.plugin.serverprobe.core.util.IsoInstant
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.nio.file.Files
import java.nio.file.Path

/**
 * 托管配置的磁盘缓存 `managed-cache.json`(FR-44,协议规格 §3.5 的 fail-static 关键)。
 *
 * ## 为什么必须有它
 * 托管模式的配置真源在控制面。控制面不可达时探针**不能**回落到 `config.yml`(那会让"以为托管着"
 * 和"实际按旧文件跑"认知分裂),故启动时必须能在控制面缺席的情况下恢复上一次生效的配置——
 * 这就是本文件存在的唯一理由,也是"秒起、不等控制面"的载体。
 *
 * ## 与身份文件严格分离
 * 身份文件是 **bootstrap**(连谁、用什么凭据),本文件是**配置值**。两者格式、生命周期、敏感级别都不同,
 * 故不合并、不互相兜底:身份文件缺失 → 独立模式;缓存缺失 → 用 `config.yml`/代码默认值(托管模式下)。
 *
 * ## 原子写
 * 复用 [AtomicFileWrite] 的 `tmp → fsync → rename`:缓存是"下次启动的唯一真源",
 * 半写的缓存等于让探针下次启动读到残缺配置,危险程度高于根本不写。
 *
 * ## 读取永不抛
 * 文件不存在 → null(正常,首次托管);解析失败/schema 版本未知/体积异常 → null + WARN。
 * 一个坏缓存只能导致"退回 `config.yml` 起步",绝不允许让探针启动失败。
 */
internal object ManagedCacheStore {

    /** 缓存格式版本;格式演进时递增,未知版本按"无缓存"处理。 */
    const val SCHEMA_VERSION = 1

    /** 缓存文件名(协议规格约定的固定名,放在插件数据目录下)。 */
    const val FILE_NAME = "managed-cache.json"

    /** 缓存体积上限:66 个配置键序列化后只有几 KB,超过即视为被写坏(防御被塞入大文件)。 */
    private const val MAX_FILE_BYTES = 256L * 1024L

    /**
     * 一份缓存的内容。
     *
     * @property configVersion 这份值对应的下发版本。
     * @property values 生效值(与 [ManagedConfigStore] 同构)。
     * @property appliedAt 生效时刻(ISO-8601 UTC 文本,原样来自落盘/本次应用)。
     * @property appliedAtEpochSeconds 生效时刻的 epoch 秒;时间文本不可解析时为 null(如实留空)。
     */
    class Snapshot(
        val configVersion: Long,
        val values: Map<String, Any?>,
        val appliedAt: String,
        val appliedAtEpochSeconds: Long?,
    )

    /**
     * 读取缓存。
     *
     * @param path 缓存文件路径。
     * @return 缓存内容;文件不存在或不可用时为 null(后者会打 WARN)。
     */
    fun read(path: Path): Snapshot? {
        val text = readText(path) ?: return null
        return runCatching { decode(text) }.getOrElse { failure ->
            ProbeLogger.warn("托管配置缓存不可用,已按无缓存继续运行:${failure.message}")
            null
        }
    }

    /**
     * 原子写入缓存。
     *
     * @param path 缓存文件路径(父目录不存在时自动创建)。
     * @param snapshot 待写入内容。
     */
    fun write(path: Path, snapshot: Snapshot) {
        AtomicFileWrite.write(path, encode(snapshot))
    }

    /**
     * 启动期把缓存装载进内存(托管模式判定为 MANAGED 时调用)。
     *
     * 装载成功即把版本与应用时刻发布到 [ManagedConfigState],使 `config_state` 的
     * `configVersion` / `cacheAgeSeconds` 从一开始就是真实值。
     *
     * @param path 缓存文件路径。
     * @return true 表示装载到了有效缓存。
     */
    fun loadInto(path: Path): Boolean {
        val snapshot = read(path) ?: return false
        ManagedConfigStore.replace(snapshot.values)
        ManagedConfigState.markApplied(snapshot.configVersion, snapshot.appliedAtEpochSeconds)
        ProbeLogger.info(
            "已加载托管配置缓存:configVersion=${snapshot.configVersion}, 键数=${snapshot.values.size}, " +
                "生效于 ${snapshot.appliedAt}",
        )
        return true
    }

    /** 读文件文本;不存在返回 null(不打日志),读取异常 WARN 后返回 null。 */
    private fun readText(path: Path): String? {
        if (!Files.isRegularFile(path)) {
            return null
        }
        return runCatching {
            val size = Files.size(path)
            if (size > MAX_FILE_BYTES) {
                ProbeLogger.warn("托管配置缓存体积 $size 字节超出上限 $MAX_FILE_BYTES,已忽略")
                return null
            }
            String(Files.readAllBytes(path), Charsets.UTF_8)
        }.getOrElse { failure ->
            ProbeLogger.warn("读取托管配置缓存失败,已按无缓存继续运行:${failure.javaClass.simpleName}")
            null
        }
    }

    /**
     * 解析缓存文本；结构不合法时抛出（由调用方统一降级）。
     *
     * 走 [JsonText] 而不是 `Json` 门面：这是**损坏即降级**的安全路径，
     * 必须能在裸单测类路径被覆盖（理由与身份文件相同，见 `JsonText` 的说明）。
     */
    private fun decode(text: String): Snapshot {
        val root = JsonText.parseObject(text)
        val schemaVersion = intOf(root["schemaVersion"]) ?: 0
        require(schemaVersion == SCHEMA_VERSION) { "schemaVersion=$schemaVersion 不在支持范围(当前 $SCHEMA_VERSION)" }
        val configVersion = longOf(root["configVersion"]) ?: -1L
        require(configVersion >= 0L) { "缺少合法的 configVersion" }
        val values = asValues(root["values"])
        val appliedAt = root["appliedAt"] as? String ?: ""
        return Snapshot(configVersion, values, appliedAt, IsoInstant.parseEpochSeconds(appliedAt))
    }

    /** 序列化缓存；字段顺序固定，便于人工排查与逐字节比对。 */
    private fun encode(snapshot: Snapshot): String = JsonText.encode(
        linkedMapOf(
            "schemaVersion" to SCHEMA_VERSION,
            "configVersion" to snapshot.configVersion,
            "values" to snapshot.values,
            "appliedAt" to snapshot.appliedAt,
        ),
    )

    /** 取整数值：JSON 数字可能被解析成 Long 或 Double，两者都接受。 */
    private fun longOf(value: Any?): Long? = when (value) {
        is Int, is Long, is Short, is Byte -> (value as Number).toLong()
        is Double -> value.toLong().takeIf { it.toDouble() == value }
        is Float -> value.toLong().takeIf { it.toFloat() == value }
        else -> null
    }

    /** 取 schema 版本（int 口径）。 */
    private fun intOf(value: Any?): Int? = longOf(value)?.toInt()

    /** 取嵌套对象为取值表；缺失或类型不符时为空表。 */
    @Suppress("UNCHECKED_CAST")
    private fun asValues(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()
}
