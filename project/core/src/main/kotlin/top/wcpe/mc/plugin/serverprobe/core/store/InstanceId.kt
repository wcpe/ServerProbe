package top.wcpe.mc.plugin.serverprobe.core.store

import taboolib.common.platform.function.getDataFolder
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger
import java.io.File
import java.util.UUID

/**
 * 实例标识解析器。
 *
 * 为每份指标快照/启动画像确定稳定的 `serverId`(api 契约要求恒非空):
 * - 配置显式指定 `server-name` 时直接采用(运维可读、可控);
 * - 未指定时,读取/生成数据目录下的 `data/.instance-id` 文件中的 UUID,保证**跨重启稳定**——
 *   同一服务端实例多次启动得到同一标识,便于历史归并与对比。
 *
 * 无状态纯逻辑(每次按入参与磁盘状态解析),以 `object` 实现,不纳入 IOC 容器。
 * 为便于单测,把"按给定数据目录解析"的核心逻辑拆为内部的 [resolveAt],公开的 [resolve] 仅注入平台数据目录后委派。
 */
object InstanceId {

    /**
     * 解析实例标识(使用平台数据目录)。
     *
     * @param configuredName 配置覆盖的实例名(已去空白);为空白/未配置时传 null。
     * @return 稳定的实例标识(恒非空)。
     */
    fun resolve(configuredName: String?): String = resolveAt(configuredName, getDataFolder())

    /**
     * 在指定数据目录下解析实例标识(核心逻辑,便于单测注入临时目录)。
     *
     * 配置名非空白则直接采用;否则读取/生成 `<dataFolder>/data/.instance-id` 中的稳定 ID。
     * 读写异常时降级为一次性随机 ID(不落盘),仅记录告警——避免因磁盘问题阻断采集主流程。
     *
     * @param configuredName 配置覆盖的实例名(已去空白);为空白/未配置时传 null。
     * @param dataFolder 数据根目录(插件数据目录)。
     * @return 稳定的实例标识(恒非空)。
     */
    internal fun resolveAt(configuredName: String?, dataFolder: File): String {
        if (!configuredName.isNullOrBlank()) {
            return configuredName
        }
        val idFile = File(dataFolder, "$DATA_DIR_NAME/$INSTANCE_ID_FILE_NAME")
        runCatching {
            if (idFile.isFile) {
                val existing = idFile.readText().trim()
                if (existing.isNotEmpty()) {
                    return existing
                }
            }
            val generated = UUID.randomUUID().toString()
            idFile.parentFile?.mkdirs()
            idFile.writeText(generated)
            return generated
        }.onFailure {
            ProbeLogger.warn("读取/写入实例 ID 失败,本次使用临时随机标识:${it.message}")
        }
        return UUID.randomUUID().toString()
    }

    /** 数据子目录名(相对插件数据目录)。 */
    private const val DATA_DIR_NAME = "data"

    /** 实例 ID 持久化文件名。 */
    private const val INSTANCE_ID_FILE_NAME = ".instance-id"
}
