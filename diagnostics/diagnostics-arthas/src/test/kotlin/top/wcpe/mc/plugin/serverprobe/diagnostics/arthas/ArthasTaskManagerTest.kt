package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasCommandRequest
import top.wcpe.mc.plugin.serverprobe.core.mcp.ArthasTaskState
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ArthasTaskManagerTest {

    @Test
    fun `任务输出受限且完成状态可查询`() {
        val tasks = ArthasTaskManager(ArthasCommandRunner { _, output -> output.append("x".repeat(32)) }, maxOutputChars = 16)
        try {
            val task = tasks.submit(ArthasCommandRequest("sc *", 1_000))
            waitFor(tasks, task.taskId)

            val result = tasks.output(task.taskId, 0)
            assertEquals(ArthasTaskState.SUCCEEDED, tasks.status(task.taskId).state)
            assertTrue(result.truncated)
        } finally {
            tasks.close()
        }
    }

    @Test
    fun `取消任务会中断执行器并记录取消状态`() {
        val started = CountDownLatch(1)
        val tasks = ArthasTaskManager(ArthasCommandRunner { _, _ ->
            started.countDown()
            Thread.sleep(TimeUnit.SECONDS.toMillis(10))
        })
        try {
            val task = tasks.submit(ArthasCommandRequest("trace A m", 5_000))
            assertTrue(started.await(1, TimeUnit.SECONDS))

            assertEquals(ArthasTaskState.CANCELLED, tasks.cancel(task.taskId).state)
        } finally {
            tasks.close()
        }
    }

    @Test
    fun `超时中断后保留超时状态而非误标为取消`() {
        val started = CountDownLatch(1)
        val tasks = ArthasTaskManager(ArthasCommandRunner { _, _ ->
            started.countDown()
            Thread.sleep(TimeUnit.SECONDS.toMillis(10))
        })
        try {
            val task = tasks.submit(ArthasCommandRequest("watch A m", 20))
            assertTrue(started.await(1, TimeUnit.SECONDS))
            waitFor(tasks, task.taskId)

            assertEquals(ArthasTaskState.TIMED_OUT, tasks.status(task.taskId).state)
        } finally {
            tasks.close()
        }
    }

    @Test
    fun `完成任务超过保留期后自动清理`() {
        val tasks = ArthasTaskManager(
            ArthasCommandRunner { _, _ -> Unit },
            settings = ArthasTaskSettings(completionRetentionMillis = 1),
        )
        try {
            val task = tasks.submit(ArthasCommandRequest("sc *", 1_000))
            waitFor(tasks, task.taskId)
            Thread.sleep(2)
            tasks.cleanupCompleted()

            assertEquals(ArthasTaskState.FAILED, tasks.status(task.taskId).state)
        } finally {
            tasks.close()
        }
    }

    @Test
    fun `反射失败状态保留目标异常类型而不泄露命令`() {
        val tasks = ArthasTaskManager(ArthasCommandRunner { _, _ ->
            throw InvocationTargetException(IllegalArgumentException("不应暴露的命令参数"))
        })
        try {
            val task = tasks.submit(ArthasCommandRequest("watch 私密参数", 1_000))
            waitFor(tasks, task.taskId)

            val result = tasks.status(task.taskId)
            assertEquals(ArthasTaskState.FAILED, result.state)
            assertTrue(result.message.contains("IllegalArgumentException"))
            assertTrue(result.message.contains("私密参数").not())
        } finally {
            tasks.close()
        }
    }

    @Test
    fun `关闭任务管理器会关闭可关闭的命令运行器`() {
        val runner = CloseableRunner()
        ArthasTaskManager(runner).close()

        assertTrue(runner.closed)
    }

    private fun waitFor(tasks: ArthasTaskManager, taskId: String) {
        repeat(50) {
            if (tasks.status(taskId).state != ArthasTaskState.RUNNING && tasks.status(taskId).state != ArthasTaskState.QUEUED) return
            Thread.sleep(10)
        }
    }

    private class CloseableRunner : ArthasCommandRunner, AutoCloseable {
        @Volatile var closed = false

        override fun run(command: String, output: ArthasTaskOutputBuffer) = Unit

        override fun close() {
            closed = true
        }
    }
}
