package top.wcpe.mc.plugin.serverprobe.ioc.scan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import taboolib.common.platform.Platform

/**
 * IoC 平台扫描兜底的契约测试。
 *
 * <h2>为什么要有包名兜底</h2>
 * <p>JDK 8 的注解解析器解析 `@PlatformSide(vararg Platform)` 这类**枚举数组**时会抛
 * `ArrayStoreException`（背后是 `AnnotationTypeMismatchExceptionProxy`），
 * 并沿 TabooLib 的注入链中断，导致异常类之后所有组件的 `@Inject` 字段全部未注入
 * （1.16.5 / JDK8 真机已证）。因此读取注解失败时必须按模块包名兜底判定平台。</p>
 *
 * <h2>为什么这个测试在 plugin 模块</h2>
 * <p>原先它在 `platform-bukkit` 里，靠读 `plugin/.../ComponentVisitor.kt` 的**源码文本**
 * 断言三个包名前缀存在。那有两个问题：跨模块读文件，且改注释/格式化就会假失败。
 * 现在测的是本模块自己的纯函数，直接传类名断言映射结果。</p>
 */
class IocPlatformScanContractTest {

    private val visitor = ComponentVisitor

    @Test
    @DisplayName("三个平台包名分别映射到各自的平台")
    fun `包名映射到平台`() {
        assertEquals(
            Platform.BUKKIT,
            visitor.platformOfPackage("top.wcpe.mc.plugin.serverprobe.bukkit.mcp.Foo"),
        )
        assertEquals(
            Platform.BUNGEE,
            visitor.platformOfPackage("top.wcpe.mc.plugin.serverprobe.bungee.bridge.Foo"),
        )
        assertEquals(
            Platform.VELOCITY,
            visitor.platformOfPackage("top.wcpe.mc.plugin.serverprobe.velocity.collector.Foo"),
        )
    }

    @Test
    @DisplayName("非平台包名不限定平台（返回 null）")
    fun `非平台包名不限定`() {
        assertNull(visitor.platformOfPackage("top.wcpe.mc.plugin.serverprobe.core.bridge.Foo"), "core 属于全平台通用，不得被包名规则拦住")
        assertNull(visitor.platformOfPackage("com.example.ThirdParty"), "第三方包名不得被误判为某个平台")
    }

    @Test
    @DisplayName("包名兜底在无法判定时放行（与原语义一致）")
    fun `无法判定时放行`() {
        // 原实现的 `else -> true`：包名不属于任何平台模块时视为“不限定”，
        // 这个语义必须保留，否则 core 组件会在某些平台上被静默跳过。
        // 用本测试类自身作示例：它就在 ioc.scan 包里，不属于任何平台模块。
        assertEquals(
            true,
            visitor.platformFromPackage(IocPlatformScanContractTest::class.java),
            "不属于任何平台模块的类必须放行，否则 core 组件会被静默过滤",
        )
    }
}
