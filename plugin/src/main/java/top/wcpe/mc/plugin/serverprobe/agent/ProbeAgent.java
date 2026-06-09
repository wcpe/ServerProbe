package top.wcpe.mc.plugin.serverprobe.agent;

import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ServerProbe 启动期 Java Agent 入口（纯 Java）。
 *
 * <p>本 jar 为<b>二合一形态</b>：既是 {@code plugins/} 下的 TabooLib 插件，又可作为
 * {@code -javaagent:plugins/ServerProbe.jar} 在 JVM 启动时挂载的 agent。命令行 {@code -javaagent}
 * 走 premain 路径，<b>不受 JEP 451 自挂载限制</b>，启动期零警告。
 *
 * <p>两个入口：
 * <ul>
 *   <li>{@link #premain(String, Instrumentation)}：JVM 命令行 {@code -javaagent} 启动期调用（首选）。</li>
 *   <li>{@link #agentmain(String, Instrumentation)}：运行期 attach 调用（保留兼容，本阶段不主动使用）。</li>
 * </ul>
 * 二者共用 {@link #bootstrap(Instrumentation)}，并以 {@link #BOOTSTRAPPED} 做<b>幂等保护</b>——
 * 即便 premain 与 agentmain 都触发，bootstrap 也只执行一次。
 *
 * <p>设计约束：<b>纯 Java</b>，由 system ClassLoader 加载，此时 kotlin stdlib / taboolib / bukkit
 * 均不可用，只能依赖 {@code java.*}、relocate 后的 ASM（{@link StartupProfilingTransformer} 内部）与本包。
 */
public final class ProbeAgent {

    /** 幂等开关：保证 bootstrap 仅执行一次（premain/agentmain 可能先后触发）。 */
    private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean(false);

    /**
     * 主线程栈采样器引用：bootstrap 启动后持有，供 A3 插件就绪时调用 {@link StartupStackSampler#stop()}。
     *
     * <p>{@code volatile}：bootstrap 线程写、（A3）插件线程读，保证可见性。
     */
    private static volatile StartupStackSampler stackSampler;

    /** 入口类，禁止实例化。 */
    private ProbeAgent() {
    }

    /**
     * JVM 启动期 agent 入口（{@code -javaagent} 命令行挂载）。
     *
     * @param agentArgs agent 参数（本阶段未使用）
     * @param inst      JVM 提供的字节码插桩接口
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        bootstrap(inst);
    }

    /**
     * 运行期 attach 入口（保留兼容，本阶段不主动使用）。
     *
     * @param agentArgs agent 参数（本阶段未使用）
     * @param inst      JVM 提供的字节码插桩接口
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        bootstrap(inst);
    }

    /**
     * 共用引导逻辑：先把数据桥注入 bootstrap，再记录启动基准并按需注册插件 enable 插桩转换器。
     *
     * <p>幂等：通过 {@link #BOOTSTRAPPED} 的 CAS 保证仅首次调用真正执行，后续调用直接返回。
     *
     * <p><b>步骤顺序是正确性的一部分</b>：
     * <ol>
     *   <li><b>先</b>把数据桥 {@link ProbeAgentBridge} 注入 bootstrap ClassLoader
     *       （{@link BootstrapBridgeInstaller#install}）。必须在<b>任何对 {@code ProbeAgentBridge} 的引用之前</b>，
     *       否则 app CL 会先定义自己的副本，导致后续插桩字节码（命中 bootstrap 副本）与 agent 自有写入
     *       （命中 app 副本）数据分裂。注入成功后，app CL 解析本类对 {@code ProbeAgentBridge} 的引用时
     *       会按双亲委派命中 bootstrap 那一份，与插桩字节码写入<b>同一份</b>。</li>
     *   <li>再写入启动基准（此时 {@code ProbeAgentBridge} 已在 bootstrap，写入落在 bootstrap 副本）。</li>
     *   <li><b>仅当 bootstrap 注入成功</b>才注册插桩转换器：注入失败时若仍插桩，服务器类执行注入字节码会
     *       因 bootstrap 无 {@code ProbeAgentBridge} 而抛 {@code NoClassDefFoundError}（即本次修复的事故）——
     *       故注入失败一律<b>跳过插桩</b>，仅保留栈采样这条不依赖插桩的旁路通道，绝不拖垮启动。</li>
     *   <li>最后启动主线程栈采样（不依赖插桩，注入成功与否都启动）。</li>
     * </ol>
     *
     * <p><b>顶层兜底（事故教训）</b>：premain <b>绝不允许</b>抛异常冒泡——一旦冒泡，JVM 会以
     * {@code java.lang.instrument ASSERTION FAILED: agent load/premain call failed} 直接退出（退出码 1），
     * 是严重事故源。故全部逻辑包在最外层 {@code try { ... } catch (Throwable t) { ... }} 内：即便注入失败、
     * 采样器启动失败、乃至任何意料之外的错误，也只向 {@code System.err} 记录中文并返回，<b>绝不</b>崩 JVM。
     *
     * @param inst JVM 提供的字节码插桩接口；为 {@code null} 时跳过 bootstrap 注入与插桩，仅记录启动基准并采样
     */
    private static void bootstrap(Instrumentation inst) {
        // 幂等保护：仅首次进入者执行，避免重复注册 transformer 与重复打点。
        if (!BOOTSTRAPPED.compareAndSet(false, true)) {
            return;
        }

        // 顶层兜底：premain 绝不允许抛异常冒泡（否则 JVM instrument ASSERTION FAILED 直接退出）。
        // 这里兜住全部引导逻辑——任何环节失败都只降级记录，绝不崩 JVM。
        try {
            // 1. 先注入 bootstrap：必须先于下方任何 ProbeAgentBridge 引用，确保数据落点统一到 bootstrap 那一份。
            boolean bootstrapReady = BootstrapBridgeInstaller.install(inst);

            // 2. 记录 premain 相对时基（nanoTime 仅用于求差，绝对值无意义）。
            ProbeAgentBridge.setPremainNanos(System.nanoTime());

            // 3. 从 RuntimeMXBean 读取 JVM 启动时刻与启动参数，作为"开服总耗时"基准与诊断上下文。
            RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
            ProbeAgentBridge.setJvmStartTimeMs(runtimeMXBean.getStartTime());
            ProbeAgentBridge.setJvmArgs(joinArgs(runtimeMXBean.getInputArguments()));

            // 4. 仅在 bootstrap 注入成功时注册插桩转换器（enable/load/library 三个 hook，canRetransform=true）。
            //    注入失败则跳过插桩：否则被插桩的服务器类执行时找不到 bootstrap 上的数据桥，反而引发 NoClassDefFoundError。
            if (bootstrapReady) {
                inst.addTransformer(new StartupProfilingTransformer(), true);
            }

            // 5. 启动 "Server thread" 主线程栈采样守护线程（daemon，不阻塞 JVM 退出）。
            //    采样不依赖插桩，故无论 bootstrap 注入成功与否都启动，保证至少有主线程热点这条通道可用。
            stackSampler = new StartupStackSampler();
            stackSampler.start();
        } catch (Throwable t) {
            // 绝不让异常冒泡到 premain/agentmain：仅降级记录，保证 JVM 正常启动。
            System.err.println("[ServerProbe] 启动期 agent 引导失败，已降级（不影响服务器启动）：" + t);
        }
    }

    /**
     * 停止主线程栈采样（由 A3 插件就绪后调用，避免采样持续到运行期）。
     *
     * <p>幂等且空安全：未启动或已停止时调用均无副作用。
     */
    public static void stopStackSampler() {
        StartupStackSampler sampler = stackSampler;
        if (sampler != null) {
            sampler.stop();
        }
    }

    /**
     * 将 JVM 启动参数列表以空格拼接成单串。
     *
     * @param args JVM 启动参数列表；可能为 {@code null} 或空
     * @return 空格拼接后的参数串；无参数时为空串
     */
    private static String joinArgs(List<String> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String arg : args) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(arg);
        }
        return sb.toString();
    }
}
