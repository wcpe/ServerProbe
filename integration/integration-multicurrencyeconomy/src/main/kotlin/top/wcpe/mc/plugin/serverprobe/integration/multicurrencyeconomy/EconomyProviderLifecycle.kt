package top.wcpe.mc.plugin.serverprobe.integration.multicurrencyeconomy

/** 生命周期监听器所需的经济 Provider 最小能力。 */
interface EconomyProviderLifecycle {

    /** 外部插件启用后刷新公开服务状态。 */
    fun refresh()

    /** 外部插件禁用或 IOC 卸载时撤销当前 Provider。 */
    fun unregister()
}
