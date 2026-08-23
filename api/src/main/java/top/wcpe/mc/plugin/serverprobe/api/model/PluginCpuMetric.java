package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * 单个插件的运行期 CPU 采样归因(FR2.6)。
 *
 * 描述一个插件在一次聚合窗口内的线程栈采样命中次数及其占比。
 * 采样计数为近似 CPU 占用(按栈帧归属的 ClassLoader 归并),供横向对比哪些插件
 * 运行期更"吃"主线程 / 其它线程。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public final class PluginCpuMetric {
    /** 插件名。 */
    String plugin;
    /** 窗口内命中该插件的栈帧样本数。 */
    long sampleCount;
    /** 该插件样本数占窗口总样本的百分比(0.0–100.0,保留一位小数)。 */
    double percent;
}
