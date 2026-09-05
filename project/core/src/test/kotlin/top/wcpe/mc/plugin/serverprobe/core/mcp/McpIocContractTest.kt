package top.wcpe.mc.plugin.serverprobe.core.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 防止 MCP 组件重新注入具体 registry，导致 IoC 仅按接口索引时找不到 Bean。 */
class McpIocContractTest {

    @Test
    fun `控制面仅注入稳定控制接口`() {
        assertInjectedType("platformControl", PlatformControl::class.java, "platformControlRegistry")
        assertInjectedType("arthasControl", ArthasControl::class.java)
    }

    @Test
    fun `registry 同时提供控制和生命周期注册接口`() {
        assertTrue(PlatformControlRegistration::class.java.isAssignableFrom(PlatformControlRegistry::class.java))
        assertTrue(ArthasControlRegistration::class.java.isAssignableFrom(ArthasControlRegistry::class.java))
    }

    private fun assertInjectedType(name: String, expected: Class<*>, beanName: String? = null) {
        val field = McpControlPlane::class.java.getDeclaredField(name)
        assertEquals(expected, field.type)
        assertTrue(field.declaredAnnotations.any { it.annotationClass.java.name == INJECT_ANNOTATION })
        if (beanName != null) assertEquals(beanName, annotationValue(field, NAMED_ANNOTATION))
    }

    private fun annotationValue(field: java.lang.reflect.Field, name: String): String? = field.declaredAnnotations
        .firstOrNull { it.annotationClass.java.name == name }
        ?.let { annotation -> annotation.annotationClass.java.getMethod("value").invoke(annotation).toString() }

    private companion object {
        const val INJECT_ANNOTATION = "top.wcpe.taboolib.ioc.annotation.Inject"
        const val NAMED_ANNOTATION = "top.wcpe.taboolib.ioc.annotation.Named"
    }
}
