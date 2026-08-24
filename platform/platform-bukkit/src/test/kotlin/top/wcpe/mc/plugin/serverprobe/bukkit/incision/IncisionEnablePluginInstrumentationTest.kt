package top.wcpe.mc.plugin.serverprobe.bukkit.incision

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import taboolib.module.incision.annotation.Lead
import taboolib.module.incision.annotation.Surgeon
import taboolib.module.incision.annotation.Trail
import taboolib.module.incision.api.Theatre

/** [IncisionEnablePluginInstrumentation] 的注解织入声明测试。 */
class IncisionEnablePluginInstrumentationTest {

    /** 目标方法必须以前后置 advice 成对声明，防止退回手工 DSL 接入。 */
    @Test
    fun `声明 SimplePluginManager 启用插件的前后置切点`() {
        val instrumentation = IncisionEnablePluginInstrumentation::class.java
        assertNotNull(instrumentation.getAnnotation(Surgeon::class.java), "必须由 Surgeon 扫描")

        val lead = instrumentation.getDeclaredMethod("beforeEnablePlugin", Theatre::class.java)
            .getAnnotation(Lead::class.java)
        val trail = instrumentation.getDeclaredMethod("afterEnablePlugin", Theatre::class.java)
            .getAnnotation(Trail::class.java)

        assertEquals(ENABLE_PLUGIN_TARGET, lead.scope, "前置切点必须精确命中 enablePlugin")
        assertEquals(lead.scope, trail.scope, "前后置切点必须作用于同一方法")
        assertTrue(trail.onThrow, "异常出口也必须清理计时作用域")
    }

    private companion object {
        const val ENABLE_PLUGIN_TARGET =
            "method:org.bukkit.plugin.SimplePluginManager#enablePlugin(org.bukkit.plugin.Plugin)V"
    }
}
