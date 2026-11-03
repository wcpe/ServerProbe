package top.wcpe.mc.plugin.serverprobe.core.net

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * 一个已启动的本地 HTTP 监听句柄（对外只暴露停止能力）。
 *
 * 之所以不直接持有 [HttpServer]：托管模式下必须**零监听**，把 bind 收口到
 * [HttpListenerFactory] 后，单测可以用替身断言"未发生 bind"，而不必真的开端口。
 */
internal interface LocalHttpListener {

    /** 立即停止监听并释放端口。 */
    fun stop()
}

/**
 * 本地 HTTP 监听器工厂（`metrics` / `web` 两个监听面的唯一 bind 入口）。
 *
 * ## 为什么要有这一层
 * 协议规格 §3.6 的核心承诺是"托管模式下探针不再监听任何端口"。这个承诺必须可被单测断言，
 * 而 `com.sun.net.httpserver.HttpServer.create` 是静态调用、无法观察；抽出本接口后，
 * 测试注入一个只记录调用次数的替身即可证明"托管模式下一次 bind 都没发生"。
 *
 * ## configure 延迟到 bind 时执行
 * 上下文挂载（[configure]）在 `HttpServer.create` 之后、`start` 之前执行；测试替身可以完全不执行它，
 * 因此断言"未 bind"时不会连带触碰处理器依赖，测试无需构造整套采集管线。
 *
 * ## 零第三方依赖
 * 生产实现 [JdkHttpListenerFactory] 只用 JDK 自带 HttpServer，不引任何库。
 */
internal interface HttpListenerFactory {

    /**
     * 绑定端口并启动监听。
     *
     * @param address 绑定地址（host + port）。
     * @param backlog 等待队列长度。
     * @param configure 上下文挂载回调（在 create 之后、start 之前执行）。
     * @return 已启动的监听句柄。
     */
    fun bind(address: InetSocketAddress, backlog: Int, configure: (HttpServer) -> Unit): LocalHttpListener
}

/** JDK 自带 `com.sun.net.httpserver.HttpServer` 的生产实现（零第三方依赖）。 */
internal object JdkHttpListenerFactory : HttpListenerFactory {

    override fun bind(address: InetSocketAddress, backlog: Int, configure: (HttpServer) -> Unit): LocalHttpListener {
        val server = HttpServer.create(address, backlog)
        configure(server)
        server.start()
        return object : LocalHttpListener {
            override fun stop() {
                server.stop(0)
            }
        }
    }
}
