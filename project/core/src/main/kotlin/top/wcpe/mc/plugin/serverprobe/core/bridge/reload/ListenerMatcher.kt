package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

/**
 * 监听器声明与请求路径的匹配（配置重载规格 §3.4）。
 *
 * ## 只有两种形态，且都是纯字符串前缀
 * - **精确**：声明与路径逐字符相等；
 * - **目录前缀**：声明以 `/` 结尾，路径以该声明开头，且剩余部分不含 `..`。
 *
 * 刻意**不做 glob、不做正则**：
 * - 正则会给监听器声明引入 ReDoS 面（声明来自第三方，路径来自 JM 下发）；
 * - glob 的 `*` 是否跨层级、`**` 是否必须存在，各库语义不同，AMB 级歧义会让"为什么没收到通知"
 *   变成不可复现问题。需要"这一层所有文件"就写目录前缀，需要"这一个文件"就写全路径。
 *
 * ## 声明必须先过 [ReloadPathGuard]
 * 否则声明 `../` 就能命中任意文件。校验在[注册时][top.wcpe.mc.plugin.serverprobe.core.bridge.reload.ConfigReloadRegistryImpl.register]
 * 与**每次派发取声明快照时**各做一次（监听器可在运行期改关注项）。
 */
object ListenerMatcher {

    /**
     * 判断一条声明是否命中给定路径。
     *
     * @param declared 监听器声明（已规范化）。
     * @param path 本次请求中的路径（已规范化）。
     * @return true 表示命中。
     */
    fun matches(declared: String, path: String): Boolean {
        if (declared.isEmpty() || path.isEmpty()) return false
        if (!declared.endsWith("/")) return declared == path
        if (!path.startsWith(declared)) return false
        // 两侧都已规范化，此判定只是双保险：剩余部分若含 `..`，说明某一侧没走规范化。
        return !path.substring(declared.length).contains("..")
    }
}
