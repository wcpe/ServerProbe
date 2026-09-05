package top.wcpe.mc.plugin.serverprobe

import top.wcpe.mc.plugin.serverprobe.api.store.MetricStore
import top.wcpe.mc.plugin.serverprobe.api.store.MetricStoreRegistration
import top.wcpe.mc.plugin.serverprobe.core.store.SwitchingMetricStore
import top.wcpe.taboolib.ioc.bean.BeanContainer

/**
 * 跨插件存储扩展门面(FR8.2)。
 *
 * 第三方插件仅能通过 [install] 注册一个 [MetricStore]；读取 API 仍由 [ServerProbeApi] 单独提供，
 * 因此存储写入能力不会混入只读 API。
 */
object ServerProbeStorageApi {

    /**
     * 安装第三方存储并返回卸载句柄。
     *
     * ServerProbe 尚未就绪、或已有第三方存储时会抛出明确异常；调用方应在自身启用后调用，
     * 并在禁用时关闭返回的句柄以恢复默认存储。
     */
    @JvmStatic
    fun install(store: MetricStore): MetricStoreRegistration {
        val switchingStore = checkNotNull(BeanContainer.getBean(SwitchingMetricStore::class.java)) {
            "ServerProbe 存储服务尚未就绪"
        }
        return switchingStore.install(store)
    }
}
