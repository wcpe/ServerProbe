package top.wcpe.mc.plugin.serverprobe.api;

/**
 * 通用注册句柄，供业务域与配置重载监听器共用。
 *
 * <p>{@link #close()} 幂等；陈旧句柄 close **不得**影响后来注册的同键实现
 * （插件热重载时旧实例的卸载回调不应该误删新实例）。</p>
 *
 * <p>所有方法线程安全，均不抛异常。</p>
 */
public interface Registration extends AutoCloseable {

    /**
     * 本句柄当前是否有效。
     *
     * <p>以下任一情况为 false：调用方已 close、同键被更新的注册顶替、探针已卸载、
     * 注册参数非法。布尔访问器用 {@code get} 前缀（ADR-13：Kotlin 消费方据此读成属性）。</p>
     */
    boolean getActive();

    /** 失效原因；有效时为 {@link InactiveReason#NONE}，永不为 null。 */
    InactiveReason getInactiveReason();

    /**
     * 失效的人可读细节（如"探针未就绪"）；无则为空串，永不为 null。
     *
     * <p>程序判断请用 {@link #getInactiveReason()}，不要解析本字段。</p>
     */
    String getInactiveDetail();

    /** 注销并释放。幂等，不抛异常。 */
    @Override
    void close();

    /** 句柄失效原因。 */
    enum InactiveReason {

        /** 有效（未失效）。 */
        NONE,

        /** ServerProbe 尚未就绪或已卸载。 */
        PROBE_UNAVAILABLE,

        /** 调用方主动 close。 */
        CLOSED,

        /** 同键被更新的注册顶替。 */
        REPLACED,

        /** 注册参数非法（如 domain 为空、路径越界）。 */
        INVALID
    }
}
