package top.wcpe.mc.plugin.serverprobe.core.alert

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import top.wcpe.mc.plugin.serverprobe.core.alert.channel.AlertHistoryChannel
import top.wcpe.mc.plugin.serverprobe.core.alert.channel.LogAlertChannel
import top.wcpe.mc.plugin.serverprobe.core.alert.channel.WebhookAlertChannel
import top.wcpe.taboolib.ioc.annotation.PostConstruct

/**
 * 告警通道"自注册入口"单元测试。
 *
 * 背景(真机验收推翻 FR-29 的教训):`AlertChannelRegistry` 不做类型扫描,各通道必须自己在
 * `@PostConstruct` 里 `registry.register(this)`——`AlertHistoryChannel` 交付时漏了这一步,
 * 编译、单测、CI 全绿,但真机上事件永远到不了历史通道,`/probe alerts` 恒为空。
 *
 * 故此处锁两件事:
 * - 每个内置通道都存在 `@PostConstruct` 自注册入口(漏了即红,不依赖运行期容器);
 * - 历史通道的 `register()` 在注入真实注册中心后确实进入 [AlertChannelRegistry.channels]
 *   (用真实被测对象 + 手动赋值注入字段,避免复刻桩掩盖语义缺口)。
 *
 * 注:仅历史通道可在此直接调用 `register()`——日志/Webhook 通道的注册读取 `ProbeConfig`,
 * 裸单测无配置后端,故对它们只断言入口存在。
 */
class AlertHistoryChannelTest {

    /** 内置告警通道清单;新增通道必须登记在此,否则漏注册会再次静默通过。 */
    private val builtInChannels = listOf(
        LogAlertChannel::class.java,
        WebhookAlertChannel::class.java,
        AlertHistoryChannel::class.java,
    )

    /** 每个内置通道都必须有 `@PostConstruct` 标注的自注册方法,否则事件永远到不了它。 */
    @Test
    fun `内置告警通道都提供 PostConstruct 自注册入口`() {
        builtInChannels.forEach { type ->
            val entry = type.methods.filter { it.isAnnotationPresent(PostConstruct::class.java) }
            assertTrue(
                entry.isNotEmpty(),
                "${type.simpleName} 缺少 @PostConstruct 自注册入口:注册中心不做类型扫描,不注册则该通道收不到任何告警",
            )
        }
    }

    /** 注入真实注册中心后调用 register,通道自身必须进入广播清单(不注册=事件到不了)。 */
    @Test
    fun `历史通道自注册后进入广播清单`() {
        val registry = AlertChannelRegistry()
        val channel = AlertHistoryChannel()
        channel.registry = registry

        channel.register()

        assertTrue(
            registry.channels.contains(channel),
            "历史通道未进入广播清单,告警事件将不会落盘",
        )
    }

    /** 重复注册(容器重建/二次装配)不得产生两个实例,避免同一条事件重复落盘。 */
    @Test
    fun `历史通道重复自注册不产生重复条目`() {
        val registry = AlertChannelRegistry()
        val channel = AlertHistoryChannel()
        channel.registry = registry

        channel.register()
        channel.register()

        assertNotNull(registry.channels.firstOrNull { it === channel })
        assertTrue(registry.channels.count { it === channel } == 1, "重复注册产生了重复条目")
    }
}
