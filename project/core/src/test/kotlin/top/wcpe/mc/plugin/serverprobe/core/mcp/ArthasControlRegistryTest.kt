package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArthasControlRegistryTest {

    @Test
    fun `未注册诊断实现时明确返回降级状态`() {
        val registry = ArthasControlRegistry()

        val result = registry.submit(ArthasCommandRequest("sc *", 1_000L))

        assertEquals(ArthasTaskState.FAILED, result.state)
        assertTrue(result.message.contains("未注册"))
    }

    @Test
    fun `注册实现后转发任务与取消请求`() {
        val registry = ArthasControlRegistry()
        val control = object : ArthasControl {
            override fun submit(request: ArthasCommandRequest) = ArthasTaskSnapshot("task-1", ArthasTaskState.QUEUED, "已提交")
            override fun status(taskId: String) = ArthasTaskSnapshot(taskId, ArthasTaskState.RUNNING, "执行中")
            override fun output(taskId: String, offset: Int) = ArthasTaskOutput(taskId, "输出", 2, false)
            override fun cancel(taskId: String) = ArthasTaskSnapshot(taskId, ArthasTaskState.CANCELLED, "已取消")
        }
        registry.register(control)

        assertEquals("task-1", registry.submit(ArthasCommandRequest("sc *", 1_000L)).taskId)
        assertEquals(ArthasTaskState.RUNNING, registry.status("task-1").state)
        assertEquals("输出", registry.output("task-1", 0).content)
        assertEquals(ArthasTaskState.CANCELLED, registry.cancel("task-1").state)
    }

    @Test
    fun `注册实现后转发补丁前备份字节码读取`() {
        // 回归：Registry 必须转发 dumpClassBytes（曾漏转发导致自动备份生产恒降级）
        val registry = ArthasControlRegistry()
        val control = object : ArthasControl {
            override fun submit(request: ArthasCommandRequest) = ArthasTaskSnapshot("task-1", ArthasTaskState.QUEUED, "已提交")
            override fun status(taskId: String) = ArthasTaskSnapshot(taskId, ArthasTaskState.RUNNING, "执行中")
            override fun output(taskId: String, offset: Int) = ArthasTaskOutput(taskId, "输出", 2, false)
            override fun cancel(taskId: String) = ArthasTaskSnapshot(taskId, ArthasTaskState.CANCELLED, "已取消")
            override fun dumpClassBytes(className: String): ByteArray? = byteArrayOf(1, 2, 3)
        }
        registry.register(control)

        assertEquals(3, registry.dumpClassBytes("com.example.Foo")?.size)
    }

    /**
     * 回归：Registry 必须转发 executeSync。
     *
     * 曾在接口上加了这个方法、也在 ArthasTaskManager 里实现了，但忘了在
     * ArthasControlRegistry 里重写——于是它落到接口默认实现 `= null`，
     * 调用方（jfr 取真实 recording id）恒拿 null 并默默降级成自增计数，
     * 表面上“能力未提供”，实际是转发漏了。
     */
    @Test
    fun `注册实现后转发同步命令执行`() {
        val registry = ArthasControlRegistry()
        val control = object : ArthasControl {
            override fun submit(request: ArthasCommandRequest) = ArthasTaskSnapshot("task-1", ArthasTaskState.QUEUED, "已提交")
            override fun status(taskId: String) = ArthasTaskSnapshot(taskId, ArthasTaskState.RUNNING, "执行中")
            override fun output(taskId: String, offset: Int) = ArthasTaskOutput(taskId, "输出", 2, false)
            override fun cancel(taskId: String) = ArthasTaskSnapshot(taskId, ArthasTaskState.CANCELLED, "已取消")
            override fun executeSync(command: String, timeoutMillis: Long) = "Recording: recording=3 name=Recording-3 (running)"
        }
        registry.register(control)

        assertEquals(
            "Recording: recording=3 name=Recording-3 (running)",
            registry.executeSync("jfr status", 1_000L),
            "Registry 未转发 executeSync 时这里会是 null，调用方会误以为能力不可用",
        )
    }

    /** 未注册时 executeSync 返回 null（与接口默认实现一致，调用方据此降级）。 */
    @Test
    fun `未注册时同步执行返回空`() {
        assertEquals(null, ArthasControlRegistry().executeSync("jfr status", 1_000L))
    }
}
