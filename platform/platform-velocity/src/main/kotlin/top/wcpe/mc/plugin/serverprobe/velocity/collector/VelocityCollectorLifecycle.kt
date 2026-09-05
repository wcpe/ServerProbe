package top.wcpe.mc.plugin.serverprobe.velocity.collector

import taboolib.common.platform.service.PlatformExecutor

/** Velocity 采集器的周期任务与缓存释放边界。 */
internal class VelocityCollectorLifecycle(
    private val cache: MutableMap<*, *>,
) {

    private var task: PlatformExecutor.PlatformTask? = null

    /** 登记当前周期任务，供平台卸载时统一取消。 */
    fun start(task: PlatformExecutor.PlatformTask) {
        this.task = task
    }

    /** 取消任务并释放当前平台的后端探测缓存。 */
    fun close() {
        task?.cancel()
        task = null
        cache.clear()
    }
}
