package top.wcpe.mc.plugin.serverprobe.api.reload;

import java.util.Collection;

/**
 * 配置重载监听器。
 *
 * <p>插件通过 {@code ServerProbeAPI.configReloads().register(listener)} 注册自己关心的配置路径。
 * 派发时<b>只调用匹配到路径的监听器</b>；没被匹配到的监听器不会被调用，也就不会产生结果。</p>
 *
 * <h2>线程约定</h2>
 * <p>回调在 ServerProbe 的配置重载 worker 池（daemon 线程，线程名前缀
 * {@code serverprobe-reload-worker}）上触发，<b>不在</b>服务器主线程、也<b>不在</b>桥读线程。</p>
 * <p>若需主线程操作（如 Bukkit 插件重载配置），请自行切回主线程，并保证总耗时不超过本次重载预算
 * （{@code bridge.reload.timeout-ms}，默认 5000ms）；超时后探针只标记 {@code TIMEOUT}，
 * 不会强杀你的任务（Java 无法强杀线程），该次重载自生自灭。</p>
 *
 * <h2>绝不抛异常</h2>
 * <p>失败请返回 {@link ConfigReloadResult#failed}。抛出异常会被兜住并转为失败
 * （{@code PLUGIN_THREW}），但会丢失可读原因。返回 {@code null} 同样视为失败，
 * <b>缺结果不得当作成功</b>。</p>
 *
 * <h2>一次调用可能覆盖多个路径</h2>
 * <p>若你的声明命中了同一次请求里的多个路径，{@link ConfigReloadEvent#getMatchedPaths()}
 * 会一次性给出全部命中路径，<b>只调用你一次</b>；结果按这些路径整体记账
 * （成功则全部计入 {@code reloaded}，失败则全部计入 {@code failed}）。</p>
 */
public interface ConfigReloadListener {

    /**
     * 声明本监听器关心的路径。
     *
     * <p>支持两种形态：</p>
     * <ul>
     *   <li>精确路径：{@code plugins/Foo/config.yml}</li>
     *   <li>目录前缀：{@code plugins/Foo/}（<b>末尾斜杠</b>表示目录），匹配该目录下任意层级的文件</li>
     * </ul>
     *
     * <p>路径一律使用 {@code /} 分隔，相对服务器工作目录，且必须位于 {@code plugins/} 下。
     * 越界、含 {@code NUL}、空串的条目在注册时被丢弃并 WARN；全部非法时注册返回失效句柄
     * （{@code INVALID}），<b>不静默成功</b>。</p>
     *
     * <p><b>会被高频调用</b>（每次派发都取一次快照，以便运行期改变关注项），
     * 因此实现必须轻量：直接返回缓存好的集合，不要每次现算或做 IO。</p>
     *
     * @return 路径集合；不得返回 null（无关注项返回空集合，等价于不注册）
     */
    Collection<String> watchedPaths();

    /**
     * 配置路径发生变更，请重载并返回结果。
     *
     * @param event 本次重载事件（含命中的路径集合）
     * @return 重载结果；返回 null 视为失败（缺结果不得当作成功）
     */
    ConfigReloadResult onConfigReload(ConfigReloadEvent event);
}
