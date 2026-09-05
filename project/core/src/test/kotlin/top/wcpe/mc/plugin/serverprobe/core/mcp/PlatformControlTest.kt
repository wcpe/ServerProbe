package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/** 平台控制契约必须保持无平台 API 依赖，并限制控制台输出。 */
class PlatformControlTest {

    @Test
    fun `未注册平台控制器时返回中文失败结果`() {
        val result = PlatformControlRegistry().execute(PlatformCommandRequest("list", BoundedCommandOutput(32))).join()

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("未注册"))
    }

    @Test
    fun `注册后由平台控制器执行并在注销后失效`() {
        val registry = PlatformControlRegistry()
        val control = object : PlatformControl {
            override fun execute(request: PlatformCommandRequest): CompletableFuture<PlatformCommandResult> {
                request.output.append("执行完成")
                return CompletableFuture.completedFuture(PlatformCommandResult.success(request.output.snapshot()))
            }
        }
        registry.register(control)

        val result = registry.execute(PlatformCommandRequest("list", BoundedCommandOutput(32))).join()
        registry.unregister(control)

        assertTrue(result.success)
        assertEquals("执行完成", result.output)
        assertFalse(registry.execute(PlatformCommandRequest("list", BoundedCommandOutput(32))).join().success)
    }

    @Test
    fun `控制台输出超出上限时截断`() {
        val output = BoundedCommandOutput(4)
        output.append("一二三四五")

        val snapshot = output.snapshot()
        assertEquals("一二三四", snapshot.text)
        assertTrue(snapshot.truncated)
    }
}
