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
        // 回归：Registry 必须转发 dumpClassBytes（曾漏转发导致 FR-19 自动备份生产恒降级）
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
}
