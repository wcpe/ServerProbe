package top.wcpe.mc.plugin.serverprobe.bungee.collector

import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** 子服 ping 的平台隔离结果，不向方法签名泄露 BungeeCord 类型。 */
internal data class BungeePingResult(val pingMs: Int, val reachable: Boolean)

/** 通过反射调用 BungeeCord 各版本共有的 Callback 式 ping 接口。 */
internal object BungeeServerPingInvoker {

    /** 发起一次 ping，并在调用方给定的后台等待上限内返回结果。 */
    fun ping(serverInfo: Any, timeout: Long, unit: TimeUnit): BungeePingResult {
        val startedAt = System.nanoTime()
        return runCatching {
            val pingMethod = findCallbackPingMethod(serverInfo)
            val completion = CompletableFuture<CallbackCompletion>()
            val callback = callbackProxy(pingMethod.parameterTypes[0], completion)
            pingMethod.invoke(serverInfo, callback)
            val result = completion.get(timeout, unit)
            if (result.error == null) reachableResult(startedAt) else unreachableResult()
        }.getOrElse { unreachableResult() }
    }

    /** 查找 `ping(Callback)`，兼容没有无参 Future 重载的新版 BungeeCord。 */
    private fun findCallbackPingMethod(serverInfo: Any): Method {
        for (method in serverInfo.javaClass.methods) {
            val parameter = method.parameterTypes.singleOrNull() ?: continue
            if (method.name == PING_METHOD && parameter.name == CALLBACK_CLASS) return method
        }
        error("当前 BungeeCord 未提供兼容的子服 ping(Callback) 接口")
    }

    /** 创建不携带 BungeeCord 静态签名的动态回调。 */
    private fun callbackProxy(type: Class<*>, completion: CompletableFuture<CallbackCompletion>): Any {
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            if (method.name == CALLBACK_DONE_METHOD) {
                completion.complete(CallbackCompletion(args?.getOrNull(1) as? Throwable))
            }
            null
        }
    }

    /** 把纳秒计时转换为非负毫秒 RTT。 */
    private fun reachableResult(startedAt: Long): BungeePingResult {
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        return BungeePingResult(elapsed.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), true)
    }

    /** 统一不可达结果。 */
    private fun unreachableResult() = BungeePingResult(UNREACHABLE_PING_MS, false)

    /** Callback 完成信号。 */
    private data class CallbackCompletion(val error: Throwable?)

    private const val PING_METHOD = "ping"
    private const val CALLBACK_CLASS = "net.md_5.bungee.api.Callback"
    private const val CALLBACK_DONE_METHOD = "done"
    private const val UNREACHABLE_PING_MS = -1
}
