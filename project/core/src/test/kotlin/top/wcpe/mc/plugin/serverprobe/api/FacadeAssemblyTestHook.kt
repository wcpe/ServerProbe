package top.wcpe.mc.plugin.serverprobe.api

/**
 * 门面装配入口的**同包**访问通道（仅供测试）。
 *
 * ## 为什么需要它
 * `ServerProbeAPI.install/uninstall` 已收窄为包内可见（package-private），
 * `project/core` 的测试若要断言"已安装"路径，就必须有一个位于
 * `top.wcpe.mc.plugin.serverprobe.api` 包内的类型代为调用——即本对象。
 *
 * ## 它同时是"可见性收窄不是安全边界"的活证据
 * 任何能在本包内声明类型的同 JVM 代码，都能照本文件的做法拿到这两个入口。
 * 测试侧这样做是自觉且可审计的（本文件位于 test source set，不进发行 Jar）；
 * **生产代码不得**借用同样的手法去调用装配入口。
 * 探针不提供进程内沙箱，详见 [ServerProbeAPI] 的类注释。
 */
object FacadeAssemblyTestHook {

    /** 等价于包内可见的 `ServerProbeAPI.install(services)`。 */
    fun install(services: BridgeServices) {
        ServerProbeAPI.install(services)
    }

    /** 等价于包内可见的 `ServerProbeAPI.uninstall()`。 */
    fun uninstall() {
        ServerProbeAPI.uninstall()
    }
}
