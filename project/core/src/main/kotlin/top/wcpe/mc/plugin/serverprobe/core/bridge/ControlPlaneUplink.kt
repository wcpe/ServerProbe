package top.wcpe.mc.plugin.serverprobe.core.bridge

import top.wcpe.mc.plugin.serverprobe.core.control.ControlPlaneEventQueue
import top.wcpe.mc.plugin.serverprobe.core.json.Json
import top.wcpe.mc.plugin.serverprobe.core.util.ProbeLogger

/**
 * 启动期积压控制面事件的补发器。
 *
 * 从 [BridgeClient] 拆出来只为职责单一（桥本体管连接与治理帧，本类管"补发积压事件"），
 * 两者无状态共享，故用 `object` 实现、不纳入 IOC。
 *
 * ## 语义
 * 迁移/治理这类事件产生时桥可能还没连上，先入 [ControlPlaneEventQueue]；每次连接建立后调用 [flush]
 * 集中补发。单条发送失败则**重新入队**等下次连接重试（至少一次投递，控制面按事件内容幂等），
 * 绝不静默丢弃。
 */
internal object ControlPlaneUplink {

    /**
     * 补发当前积压的全部事件。
     *
     * @param ws 当前活动连接。
     * @param instance 本实例标识（帧内 `instance` 字段）。
     */
    fun flush(ws: MinimalWebSocketClient, instance: String) {
        val queued = ControlPlaneEventQueue.drain()
        if (queued.isEmpty()) {
            return
        }
        var sent = 0
        queued.forEach { event ->
            val ok = runCatching { ws.sendText(frame(event, instance)) }.getOrDefault(false)
            if (ok) {
                sent++
            } else {
                ControlPlaneEventQueue.enqueue(event.event, event.data)
            }
        }
        if (sent < queued.size) {
            ProbeLogger.warn("控制面事件补发未全部成功(成功 $sent/${queued.size}),失败条目已重新排队等待下次连接")
        } else {
            ProbeLogger.info("已补发 $sent 条启动期控制面事件")
        }
    }

    /** 构造事件帧 JSON（type=event + 事件名 + instance + timestamp + data）。 */
    private fun frame(event: ControlPlaneEventQueue.PendingEvent, instance: String): String = Json.encode(
        linkedMapOf(
            "type" to "event",
            "event" to event.event,
            "instance" to instance,
            "timestamp" to System.currentTimeMillis(),
            "data" to event.data,
        )
    )
}
