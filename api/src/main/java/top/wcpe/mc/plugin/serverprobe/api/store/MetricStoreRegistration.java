package top.wcpe.mc.plugin.serverprobe.api.store;

/**
 * 第三方存储替换的生命周期句柄(FR8.2)。
 *
 * 调用 {@code close()} 后，ServerProbe 会恢复默认本地文件存储。关闭同一句柄多次安全，
 * 且不会影响后来安装的其它存储。
 */
public interface MetricStoreRegistration extends AutoCloseable {

    /**
     * 卸载本句柄安装的第三方存储并恢复默认实现。
     */
    @Override
    void close();
}
