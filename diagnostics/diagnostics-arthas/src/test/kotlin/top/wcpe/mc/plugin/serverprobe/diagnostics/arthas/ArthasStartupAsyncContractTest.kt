package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Arthas 启动期加载的异步契约(见 Issue #18)。
 *
 * 加载含运行包解包与 Instrumentation 附加(必要时 spawn helper 子进程注入,等待上限 30s);
 * 若在 `@PostEnable start()`(插件启用线程 = 服务器主线程)同步执行,即违反"主线程禁止阻塞
 * 磁盘 IO / 外部进程"的项目红线——命令路径早已异步化,启动路径必须在同一范式内。
 */
class ArthasStartupAsyncContractTest {

    private val source: String
        get() = File(
            "src/main/kotlin/top/wcpe/mc/plugin/serverprobe/diagnostics/arthas/ArthasDiagnosticsLifecycle.kt",
        ).readText()

    @Test
    fun `启动期自动加载必须经 submitAsync 异步执行`() {
        val startBody = source.substringAfter("@PostEnable fun start()").substringBefore("@PreDestroy")
        assertTrue(
            startBody.contains("submitAsync { startRuntime() }"),
            "启动期自动加载必须异步(命令路径范式),实际: $startBody",
        )
    }

    @Test
    fun `卸载后拒绝加载以免残留线程阻止 JVM 退出`() {
        assertTrue(source.contains("if (destroyed) return unavailableDestroyed()"), "异步加载可能晚于卸载被调度,必须拒绝")
    }
}
