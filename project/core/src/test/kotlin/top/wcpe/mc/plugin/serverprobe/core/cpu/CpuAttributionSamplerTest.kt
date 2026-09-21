package top.wcpe.mc.plugin.serverprobe.core.cpu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import taboolib.module.configuration.Configuration
import top.wcpe.mc.plugin.serverprobe.core.config.ProbeConfig
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.URLClassLoader

/**
 * [CpuAttributionSampler] 窗口聚合口径单测(FR2.6)。
 *
 * 采样为私有方法,经反射直接驱动(不经 IOC / 调度器),用固定的 `cpu.window-rounds` 制造
 * **窗口滑动淘汰**场景:淘汰最旧一轮时必须同步回退分母,否则各插件占比之和不再是 100%。
 *
 * 配置替身原因:单测 classpath 上只有 `basic-configuration` 的接口/门面,其配置后端被重定位到
 * `com.electronwill.nightconfig_3_6_7.*` 且未随测试下发,故 `Configuration.loadFromString(任意 Type)`
 * 均抛 [NoClassDefFoundError];这里改用动态代理仅实现本用例依赖的取值方法(`getInt`)。
 */
class CpuAttributionSamplerTest {

    /**
     * 窗口滑动淘汰后,分子(各插件计数)与分母(总样本数)必须同口径回退:
     * 窗口内各插件占比之和应恒为 100(±四舍五入误差)。
     */
    @Test
    fun `窗口淘汰后各插件占比之和仍为 100`() {
        ProbeConfig.conf = probeConfiguration(mapOf("cpu.window-rounds" to 2))

        val sampler = CpuAttributionSampler()
        // 归属判定以"定义加载器"为准,故注册一个**真正定义测试类**的加载器(URL 指向测试类所在目录、父为引导加载器):
        // 采样时本测试类的栈帧才会被归到 TestPlugin,保证每轮都有样本、断言检查的是聚合口径而非空集
        val testClasses = CpuAttributionSamplerTest::class.java.protectionDomain.codeSource.location
        sampler.registry = PluginClassLoaderRegistry().apply {
            register("TestPlugin", URLClassLoader(arrayOf(testClasses), null))
        }

        val sample = CpuAttributionSampler::class.java.getDeclaredMethod("sample").apply { isAccessible = true }
        // 4 轮 > 窗口 2 轮,触发两次最旧一轮的淘汰
        repeat(4) { sample.invoke(sampler) }

        val sum = sampler.snapshot(10).sumOf { it.percent }
        assertEquals(100.0, sum, 0.5, "窗口内各插件占比之和应为 100,实际 $sum")
    }

    /** 只回答取值方法的配置替身(单测 classpath 无配置后端,无法构造真实 [Configuration])。 */
    private fun probeConfiguration(values: Map<String, Any>): Configuration {
        val handler = InvocationHandler { _, method, args ->
            val fallback = args?.getOrNull(1)
            val value = values[args?.firstOrNull() as? String]
            when (method.name) {
                "getInt" -> value as? Int ?: fallback
                "getLong" -> (value as? Int)?.toLong() ?: fallback
                "getBoolean" -> value as? Boolean ?: fallback
                "getString" -> value as? String ?: fallback
                else -> fallback
            }
        }
        return Proxy.newProxyInstance(
            Configuration::class.java.classLoader,
            arrayOf(Configuration::class.java),
            handler
        ) as Configuration
    }
}
