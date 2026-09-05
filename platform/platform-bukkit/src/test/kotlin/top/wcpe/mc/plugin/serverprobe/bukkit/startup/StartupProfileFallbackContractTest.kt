package top.wcpe.mc.plugin.serverprobe.bukkit.startup

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 启动画像兜底装配的合同约束。
 *
 * `org.bukkit.event.server.ServerLoadEvent` 自 1.12 才存在;在 1.8–1.11 服务端上 TabooLib 只会打印
 * WARN 并跳过注册,导致启动画像(FR1 核心交付)在旧版本上永不生成。合同要求存在 ACTIVE 兜底装配路径,
 * 且两条路径以原子标记防重。
 */
class StartupProfileFallbackContractTest {

    private val source: String
        get() = File(
            "src/main/kotlin/top/wcpe/mc/plugin/serverprobe/bukkit/startup/StartupLoadListener.kt",
        ).readText()

    @Test
    fun `事件缺失时以 ACTIVE 首帧兜底装配启动画像`() {
        assertTrue(source.contains("Class.forName(\"org.bukkit.event.server.ServerLoadEvent\""))
        assertTrue(source.contains("LifeCycle.ACTIVE"))
        assertTrue(source.contains("onActiveFallback"))
    }

    @Test
    fun `事件与兜底两条装配路径以原子标记防重`() {
        assertTrue(source.contains("AtomicBoolean"))
        assertTrue(source.contains("assemblyTriggered"))
    }

    @Test
    fun `事件签名隔离在独立桥对象中`() {
        // 主类被 1.8-1.11 扫描时不得在签名中引用 ServerLoadEvent,否则自身 @Inject 无法注入。
        assertTrue(source.contains("object StartupServerLoadBridge"))
        val mainClass = source.substringBefore("object StartupServerLoadBridge")
        assertTrue(!mainClass.contains(": ServerLoadEvent"), "主类签名不得引用 ServerLoadEvent")
    }
}
