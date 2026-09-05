package top.wcpe.mc.plugin.serverprobe.integration.allininventorysync

/** 生命周期监听器所需的背包 Provider 最小能力。 */
interface InventoryProviderLifecycle {

    /** 外部插件启用后刷新公开 API 状态。 */
    fun refresh()

    /** 外部插件禁用或 IOC 卸载时撤销当前 Provider。 */
    fun unregister()
}
