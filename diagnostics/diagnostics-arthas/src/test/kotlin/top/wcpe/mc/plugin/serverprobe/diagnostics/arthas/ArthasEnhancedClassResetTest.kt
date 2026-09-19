package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 回归：卸载运行时必须先还原被增强的字节码（FR-24 真机发现的崩溃缺陷）。
 *
 * Java 8 + Arthas 3.1.1 真机实测 4/4 必现：执行 `watch`/`trace` 等增强类命令后直接卸载，
 * 被 retransform 的方法仍会回调已释放的 Arthas 类，下一 tick NPE 崩服务器，崩溃点即被插桩方法本身。
 * 修复在 [ArthasTaskManager.close] 落点：runner 的 `close()` 先执行 `reset` 再拆除加载器。
 *
 * 本测试锁定"关闭任务管理器会连带关闭运行器"这一链路（复位发生在 runner 内部，
 * 其真实行为依赖 Arthas，由真机验收覆盖），防止后续重构把 `(runner as? AutoCloseable)?.close()`
 * 这行漏掉——漏掉即等于遗留崩溃缺陷。
 */
class ArthasEnhancedClassResetTest {

    @Test
    fun `关闭任务管理器会关闭运行器`() {
        val runner = RecordingRunner()

        ArthasTaskManager(runner, bytecodeReader = { null }).close()

        assertTrue(runner.closed, "运行器必须被关闭，否则增强类不会被复位且线程泄漏")
    }

    @Test
    fun `运行器关闭异常不影响任务管理器释放线程`() {
        val runner = RecordingRunner(failOnClose = true)

        ArthasTaskManager(runner, bytecodeReader = { null }).close()

        // runCatching 兜底：复位失败也必须完成释放，否则非 daemon 线程会阻止 JVM 退出。
        assertTrue(runner.closed, "即使关闭抛异常也必须已尝试关闭")
    }

    /** 最小运行器替身：记录关闭调用，可模拟关闭失败。 */
    private class RecordingRunner(private val failOnClose: Boolean = false) : ArthasCommandRunner, AutoCloseable {

        var closed = false

        override fun run(command: String, output: ArthasTaskOutputBuffer) {
            output.append("ok")
        }

        override fun close() {
            closed = true
            if (failOnClose) error("模拟运行器关闭失败")
        }
    }
}
