package top.wcpe.mc.plugin.serverprobe.api.model;

/**
 * 单条折叠调用栈(M5,启动 agent 增强数据)。
 *
 * 折叠栈(folded stack)是火焰图的标准数据形态:一次栈采样的**完整有序调用路径**(自栈底到栈顶)
 * 连同其命中次数。与 {@code StackHotspot} 的"逐帧词频"不同,折叠栈保留了帧间的父→子调用关系,
 * 因此可据此还原出真正的多层火焰图(而非单层热点榜)。
 *
 * 仅当挂载启动 agent({@code -javaagent:plugins/ServerProbe.jar})时才有此数据
 * (详见 {@code ThreadStackProfile} 与 {@code StartupProfile.threadStacks})。
 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public class FoldedStack {
    /** 调用栈帧序列,**自栈底(root)到栈顶(叶)**有序;每帧标识为 {@code 类全名#方法名}。 */
    java.util.List<String> frames;
    /** 该完整调用路径被采样命中的次数。 */
    long sampleCount;
}
