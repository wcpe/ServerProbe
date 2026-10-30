package top.wcpe.mc.plugin.serverprobe.api.reload;

import top.wcpe.mc.plugin.serverprobe.api.Registration;

/**
 * 配置重载监听器注册表。
 *
 * <p>由 {@code ServerProbeAPI.configReloads()} 取得，稳定代理、永不 null：
 * 即使探针尚未就绪也可以缓存引用，就绪后同一引用即可正常工作。</p>
 *
 * <p><b>线程安全</b>：可被任意线程调用，均不抛异常。</p>
 */
public interface ConfigReloadRegistry {

    /**
     * 注册监听器。
     *
     * <p>注册时立即校验 {@link ConfigReloadListener#watchedPaths()}：非法条目（越界、含 {@code NUL}、
     * 空串）被丢弃并 WARN（带监听器类名与非法值）；全部非法或为空集合时返回失效句柄
     * （原因为 {@code INVALID}）——<b>不静默成功</b>。</p>
     *
     * <p><b>不会自动注销</b>：句柄的 {@code close()} 需调用方自行执行（通常在插件 {@code onDisable}）。
     * 同实例重复注册以最新一次为准，旧句柄失效（{@code REPLACED}）。</p>
     *
     * @param listener 监听器；null 返回失效句柄，不抛异常
     * @return 注册句柄；探针未就绪时返回失效句柄（{@code PROBE_UNAVAILABLE}），不抛异常
     */
    Registration register(ConfigReloadListener listener);
}
