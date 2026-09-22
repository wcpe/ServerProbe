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
    // 整段反射防护(技术债 #26-⑥):PROJECT_SCANNER 类/方法缺失(打包 relocate 漂移、taboolib-ioc 升级)
    // 会在此抛 ClassNotFound/NoSuchMethod——不捕获就等于"注入链整体中断",违背本文件自述的防护初衷。
    // 失败返回空列表,由 scanAll 的告警把"0 个组件"对服主可见。
    val scannerClass = runCatching { Class.forName(PROJECT_SCANNER_CLASS) }.getOrElse {
        println("[ServerProbe][IoC] 平台扫描未生效:找不到 $PROJECT_SCANNER_CLASS(${it.javaClass.simpleName}),本插件的 @Inject 组件将全部未注册")
        return emptyList()
    }
    val classMap = runCatching {
        scannerClass.methods
            .first { it.name == "getRunningClassMapInJar" }
            .invoke(null) as Map<String, Any>
    }.getOrElse {
        println("[ServerProbe][IoC] 平台扫描未生效:getRunningClassMapInJar 调用失败(${it.javaClass.simpleName}:${it.message})")
        return emptyList()
    }
    val toClass = classMap.values.firstOrNull()?.javaClass?.getMethod("toClass") ?: run {
        println("[ServerProbe][IoC] 平台扫描未生效:运行时类缺少 toClass 方法")
        return emptyList()
    }
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
        // 容器获取失败(CONTAINER/EVALUATOR 类缺失,见 getRunningClassesInJar 同款漂移风险)必须显式
        // ERROR/println 可见——静默 return 会让整条注入链失效而无任何症状
        val container = runCatching { singleton(CONTAINER_CLASS) }.getOrElse {
            println("[ServerProbe][IoC] 平台扫描未生效:容器获取失败(${it.javaClass.simpleName}:${it.message}),@Inject 组件将全部未注册")
            return
        }
        val scanner = invoke(container, "getScanner\$taboolib_ioc_core")
            ?: return warnScanIncomplete("getScanner 方法缺失")
        val registry = invoke(container, "getRegistry\$taboolib_ioc_core")
            ?: return warnScanIncomplete("getRegistry 方法缺失")
        val conditionContext = invoke(container, "createConditionContext\$taboolib_ioc_core")
            ?: return warnScanIncomplete("createConditionContext 方法缺失")
        val conditions = runCatching { singleton(CONDITION_EVALUATOR_CLASS) }.getOrElse {
            return warnScanIncomplete("条件评估器缺失(${it.javaClass.simpleName})")
        }
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
        // 注册数为 0 且候选非空:装配大概率漂移,必须升级为显式可见告警(仅 debug 无法定位此故障)
        if (definitions.isEmpty() && classes.isNotEmpty()) {
            println("[ServerProbe][IoC] 警告:候选 ${classes.size} 个但注册 0 个组件——容器方法/打包 relocate 可能漂移,请检查")
        }
        debug("[IoC] 平台过滤扫描完成，注册 ${definitions.size} 个组件")
    }

    /** 扫描未完成时的统一显式告警(LOAD 期用 println,详见上方注释)。 */
    private fun warnScanIncomplete(reason: String) {
        println("[ServerProbe][IoC] 警告:平台扫描未完成($reason),@Inject 组件可能未全部注册")
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
            .getOrElse { return platformFromPackage(candidate.name, Platform.CURRENT) }
        return declaredPlatforms?.let { Platform.CURRENT in it } ?: true
    }

    /** 模块包名到运行平台的映射:`bukkit.`/`nms.` 归 Bukkit 系,`bungee.`/`velocity.` 各归其平台。 */
    private fun platformFromPackage(candidate: Class<*>): Boolean =
        platformFromPackage(candidate.name, Platform.CURRENT)

    /**
     * 包名 → 平台判定的纯函数(供单测;注解不可读时的兜底口径)。
     *
     * 注意:`integration.*` 包下的类带 `@PlatformSide(BUKKIT)` 却不在三前缀内——注解可读时无影响;
     * 注解解析失败(仅 JDK8 真机出现过)时这些类按"默认放行"处理,可能被非 Bukkit 平台多注册,
     * 由类自身 `@PostConstruct` 平台门二次兜底(两层防御,见 issues 审查记录)。
     */
    internal fun platformFromPackage(className: String, current: Platform): Boolean = when {
        className.startsWith(BUNGEE_PACKAGE_PREFIX) -> Platform.BUNGEE == current
        className.startsWith(VELOCITY_PACKAGE_PREFIX) -> Platform.VELOCITY == current
        className.startsWith(BUKKIT_PACKAGE_PREFIX) -> Platform.BUKKIT == current
        else -> true
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
