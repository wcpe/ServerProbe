package top.wcpe.mc.plugin.serverprobe.core.util

import taboolib.common.platform.function.info as platformInfo
import taboolib.common.platform.function.severe as platformSevere
import taboolib.common.platform.function.warning as platformWarning

/**
 * 探针统一日志门面。
 *
 * 无状态纯门面,仅在 TabooLib 平台日志函数之上做中文分级包装,不持有任何业务状态,
 * 故以 `object` 实现且**不纳入 IOC 容器**(无需注入、无生命周期)。
 *
 * 因本门面方法名与 TabooLib 顶层函数同名(info/warning),导入时以别名区分,避免递归调用歧义。
 * 分级映射:
 * - [info]/[warn] 直接对应 TabooLib 的 `info`/`warning`;
 * - [error] 对应 TabooLib 的 `severe`(ERROR 级),可携带异常打印堆栈;
 * - [debug] 默认关闭,经 [debugEnabled] 控制,开启后以 `info` 通道输出并加 `[DEBUG]` 前缀。
 */
object ProbeLogger {

    /**
     * 是否输出 DEBUG 级日志,默认关闭。
     *
     * P7 阶段由配置接入开关;在并发环境下被读写,故用 `@Volatile` 保证可见性。
     */
    @Volatile
    var debugEnabled: Boolean = false

    /**
     * 输出 INFO 级日志。
     *
     * @param msg 日志内容。
     */
    fun info(msg: String) {
        platformInfo(msg)
    }

    /**
     * 输出 WARN 级日志。
     *
     * @param msg 日志内容。
     */
    fun warn(msg: String) {
        platformWarning(msg)
    }

    /**
     * 输出 ERROR 级日志,可附带异常堆栈。
     *
     * @param msg 日志内容。
     * @param t 关联异常;为 null 时仅输出文本。
     */
    fun error(msg: String, t: Throwable? = null) {
        platformSevere(msg)
        if (t != null) {
            // 将异常堆栈格式化为字符串经日志门面输出,避免 printStackTrace 绕过日志通道(规范第 12 条)
            val writer = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(writer))
            platformSevere(writer.toString())
        }
    }

    /**
     * 输出 DEBUG 级日志;仅当 [debugEnabled] 为 true 时生效。
     *
     * @param msg 日志内容。
     */
    fun debug(msg: String) {
        if (debugEnabled) {
            platformInfo("[DEBUG] $msg")
        }
    }
}
