package top.wcpe.mc.plugin.serverprobe

import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi
import top.wcpe.taboolib.ioc.bean.BeanContainer

/**
 * 跨插件只读 API 静态门面(FR8.1)。
 *
 * 第三方插件经此一处即可获取 ServerProbe 的只读开放接口 [ProbeReadApi],**无需依赖 IOC 容器内部细节**
 * (无需自行了解 `BeanContainer` 的取 bean 方式与就绪时机)。门面只做"容错取 bean":
 * 容器未初始化、bean 尚未注册或解析过程抛任何异常时,统一返回 null,由调用方决定降级处理。
 *
 * ## 用法
 * ```kotlin
 * val api = ServerProbeApi.read() ?: return // 容器未就绪或未安装 ServerProbe
 * val snapshot = api.latestSnapshot()
 * ```
 * 调用方应在 ServerProbe 容器就绪后调用(如自身 `ENABLE`/`ACTIVE` 之后);过早调用得到 null 属正常,稍后重试即可。
 *
 * 放在 **plugin**(运行期壳)模块而非 api/core:取 IOC bean 依赖运行期容器([BeanContainer]),
 * 属运行期能力;api 为纯契约、core 为平台无关逻辑,均不应引入运行期 IOC 取 bean 的耦合。
 * 以无状态 `object` 实现(仅静态委派,不持有任何可变状态)。
 */
object ServerProbeApi {

    /**
     * 容错获取只读开放接口实例。
     *
     * 经 IOC [BeanContainer] 按接口类型解析 [ProbeReadApi](其实现 `ProbeReadApiImpl` 标注 `@Service`);
     * 容器未初始化时 [BeanContainer.getBean] 自身即返回 null,此外再以 `runCatching` 兜底解析期任何异常,
     * 任何异常路径一律收敛为 null,绝不向调用方抛出。
     *
     * @return 只读开放接口实例;容器未就绪、未安装 ServerProbe 或解析异常时为 null。
     */
    fun read(): ProbeReadApi? =
        runCatching { BeanContainer.getBean(ProbeReadApi::class.java) }.getOrNull()
}
