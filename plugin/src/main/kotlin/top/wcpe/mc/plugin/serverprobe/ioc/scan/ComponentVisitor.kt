package top.wcpe.mc.plugin.serverprobe.ioc.scan

import taboolib.common.LifeCycle
import taboolib.common.Inject
import taboolib.common.inject.ClassVisitor
import taboolib.common.platform.Awake
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.function.debug

/**
 * 与 IoC 原实现保持二进制兼容，供对象注入器收集当前插件的类。
 */
@Suppress("UNCHECKED_CAST")
fun getRunningClassesInJar(): List<Class<*>> {
    val scannerClass = Class.forName(PROJECT_SCANNER_CLASS)
    val classMap = scannerClass.methods
        .first { it.name == "getRunningClassMapInJar" }
        .invoke(null) as Map<String, Any>
    val toClass = classMap.values.firstOrNull()?.javaClass?.getMethod("toClass") ?: return emptyList()
    return classMap.values.mapNotNull { entry ->
        runCatching { toClass.invoke(entry) as? Class<*> }.getOrNull()
    }
}

/**
 * 单 jar 多平台的 IoC 扫描兼容补丁。
 *
 * taboolib-ioc 的原扫描器先读取所有组件的方法元数据，后处理 [PlatformSide]。
 * BungeeCord 上解析 Bukkit 方法签名会中断整个容器初始化，因此必须先按平台筛选。
 */
@Inject
@Awake
object ComponentVisitor : ClassVisitor(1) {

    override fun getLifeCycle(): LifeCycle = LifeCycle.LOAD

    @Awake(LifeCycle.LOAD)
    fun scanAll() {
        val classes = getRunningClassesInJar().filter(::matchesCurrentPlatform)
        val container = singleton(CONTAINER_CLASS)
        val scanner = invoke(container, "getScanner\$taboolib_ioc_core") ?: return
        val registry = invoke(container, "getRegistry\$taboolib_ioc_core") ?: return
        val conditionContext = invoke(container, "createConditionContext\$taboolib_ioc_core") ?: return
        val conditions = singleton(CONDITION_EVALUATOR_CLASS)
        val definitions = classes.mapNotNull { candidate ->
            // 单个候选扫描失败(如旧 JDK 注解解析缺陷、引用缺失的 Folia 专属类型)只跳过该类,
            // 绝不中断整条注入链——否则后续组件的 @Inject 字段会全部未注入。
            runCatching { definitionOrNull(candidate, scanner, registry, conditionContext, conditions) }
                .onFailure {
                    // println 而非 debug():该路径意味着有组件未注册,必须对服主可见(LOAD 期 ProbeLogger 尚未就绪)。
                    println("[ServerProbe][IoC] 跳过无法扫描的类:${candidate.name}:${it.javaClass.simpleName}:${it.message}")
                }
                .getOrNull()
        }
        definitions.forEach { definition -> invoke(registry, "register", definition) }
        debug("[IoC] 平台过滤扫描完成，注册 ${definitions.size} 个组件")
    }

    private fun definitionOrNull(
        candidate: Class<*>,
        scanner: Any,
        registry: Any,
        conditionContext: Any,
        conditions: Any
    ): Any? {
        val definition = invoke(scanner, "scan", candidate) ?: return null
        val name = invoke(definition, "getName") as? String ?: return null
        if (invokeBoolean(registry, "contains", name)) return null
        if (invokeBoolean(conditions, "shouldSkipOnScan", candidate, conditionContext)) return null
        return definition.takeUnless { invokeBoolean(conditions, "hasBeanCondition", candidate) }
    }

    private fun matchesCurrentPlatform(candidate: Class<*>): Boolean {
        // JDK 8 的注解解析器解析 @PlatformSide(vararg Platform) 这类枚举数组时会抛
        // ArrayStoreException(AnnotationTypeMismatchExceptionProxy),并沿 ClassVisitorHandler
        // 中断整条注入链,令异常类之后所有组件的 @Inject 字段全部未注入(1.16.5/JDK8 真机已证)。
        // 故读取失败时按模块包名约定兜底判定平台,与注解语义严格一致。
        val declaredPlatforms = runCatching { candidate.getAnnotation(PlatformSide::class.java)?.value }
            .getOrElse { return platformFromPackage(candidate) }
        return declaredPlatforms?.let { Platform.CURRENT in it } ?: true
    }

    /** 模块包名到运行平台的映射:`bukkit.`/`nms.` 归 Bukkit 系,`bungee.`/`velocity.` 各归其平台。 */
    private fun platformFromPackage(candidate: Class<*>): Boolean {
        val name = candidate.name
        return when {
            name.startsWith(BUNGEE_PACKAGE_PREFIX) -> Platform.BUNGEE == Platform.CURRENT
            name.startsWith(VELOCITY_PACKAGE_PREFIX) -> Platform.VELOCITY == Platform.CURRENT
            name.startsWith(BUKKIT_PACKAGE_PREFIX) -> Platform.BUKKIT == Platform.CURRENT
            else -> true
        }
    }

    private fun singleton(className: String): Any = Class.forName(className).getField("INSTANCE").get(null)

    private fun invoke(target: Any, name: String, vararg arguments: Any): Any? {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterCount == arguments.size
        } ?: return null
        return method.invoke(target, *arguments)
    }

    private fun invokeBoolean(target: Any, name: String, vararg arguments: Any): Boolean =
        invoke(target, name, *arguments) as? Boolean ?: false

    private const val CONTAINER_CLASS = "top.wcpe.mc.plugin.serverprobe.ioc.bean.BeanContainer"
    private const val CONDITION_EVALUATOR_CLASS = "top.wcpe.mc.plugin.serverprobe.ioc.condition.ConditionEvaluator"
    private const val BUKKIT_PACKAGE_PREFIX = "top.wcpe.mc.plugin.serverprobe.bukkit."
    private const val BUNGEE_PACKAGE_PREFIX = "top.wcpe.mc.plugin.serverprobe.bungee."
    private const val VELOCITY_PACKAGE_PREFIX = "top.wcpe.mc.plugin.serverprobe.velocity."
}

private const val PROJECT_SCANNER_CLASS = "top.wcpe.mc.plugin.serverprobe.taboolib.common.io.ProjectScannerKt"
