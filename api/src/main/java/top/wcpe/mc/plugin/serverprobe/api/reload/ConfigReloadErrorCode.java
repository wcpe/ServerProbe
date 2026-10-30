package top.wcpe.mc.plugin.serverprobe.api.reload;

/**
 * 重载失败错误码。
 *
 * <p>注意：{@code BEST_EFFORT} <b>不是</b>错误码——无人认领的路径计入回执的
 * {@code bestEffort} 列表（算成功），不占 {@code failed}。</p>
 *
 * <p><b>只有 {@link #TIMEOUT} 触发熔断</b>：其余码都是确定性结果，重试有意义
 * （{@code FILE_NOT_FOUND} 可能补文件、{@code PARSE_ERROR} 可能修语法、
 * {@code IN_FLIGHT} 只是撞上在途、{@code STUCK} 会随时间自愈）。</p>
 */
public enum ConfigReloadErrorCode {

    /** 路径不存在。 */
    FILE_NOT_FOUND,

    /** 文件存在但解析失败（如 YAML 语法错误）。 */
    PARSE_ERROR,

    /** 插件明确拒绝了本次重载。 */
    PLUGIN_REJECTED,

    /** 监听器抛出了异常（已兜住）。 */
    PLUGIN_THREW,

    /** 超时未完成（可能仍在执行，ServerProbe 不强杀）。 */
    TIMEOUT,

    /** 路径越界（不在 plugins/ 子树内，或含 ../ 逃逸）。 */
    PATH_ESCAPE,

    /** 同路径已有重载在途，本次未执行。 */
    IN_FLIGHT,

    /** 上次在途已超时且任务未返回，疑似卡死（本次未执行）。 */
    STUCK,

    /** 探针内部错误（提交被拒、池已关闭等，本次未执行）。 */
    INTERNAL_ERROR
}
