package top.wcpe.mc.plugin.serverprobe.agent;

import java.util.Map;

/**
 * 启动期全线程栈采样器（纯 Java 守护线程）。
 *
 * <p>由 {@link ProbeAgent#bootstrap} 启动，周期性读取 **JVM 内全部存活线程**的<b>完整调用栈</b>,
 * 自栈底到栈顶折叠成一行（{@code 栈底帧;...;栈顶帧}）后计入 {@link ProbeAgentBridge#recordFoldedStack}。
 * 折叠栈保留帧间父→子关系,是还原真正多层火焰图的数据源。</p>
 *
 * <p><b>为何采全部线程(而非仅主线程/Netty/Folia 几个)</b>:启动期的隐形大头常发生在<b>非主线程</b>——
 * 典型如 TabooLib / 各插件用线程池<b>并行下载依赖</b>(可卡数分钟),其线程名各异(如 {@code pool-N-thread-M}、
 * {@code ForkJoinPool.commonPool-worker-N})。只采固定几个线程名会彻底漏掉这类瓶颈。改为每周期一次
 * {@link Thread#getAllStackTraces()}(单次安全点取全部线程栈,比逐线程 {@code getStackTrace} 的多次安全点更省),
 * 把每个活动线程都纳入折叠栈,确保"无论卡在哪个线程"都能被火焰图/报告捕获。</p>
 *
 * <p><b>安全第一——绝不成为事故源</b>:
 * <ul>
 *   <li><b>daemon 线程</b>:不阻塞 JVM 退出。</li>
 *   <li><b>只读栈、不持锁</b>:仅 {@link Thread#getAllStackTraces()}(只读快照),从不干预其它线程。</li>
 *   <li><b>异常全吞</b>:每个采样周期独立 try/catch,单次失败不影响后续。</li>
 *   <li><b>有限寿命</b>:即便未调用 {@link #stop()},也在 {@link #MAX_SAMPLING_MS} 上限后自停。</li>
 *   <li><b>限频 + 限深</b>:周期 {@link #SAMPLE_INTERVAL_MS},单栈最多折叠 {@link #MAX_FRAMES} 帧,
 *       控制全线程采样的 CPU 与内存开销。</li>
 * </ul>
 *
 * <p>设计约束:<b>纯 JDK</b>,由 system ClassLoader 加载,只依赖 {@code java.*},不触碰 kotlin/taboolib/bukkit。
 */
final class StartupStackSampler implements Runnable {

    /** 采样周期（毫秒）：10ms ≈ 100Hz。全线程采样下兼顾覆盖与开销,捕捉数分钟级卡顿绰绰有余。 */
    private static final long SAMPLE_INTERVAL_MS = 10L;

    /** 采样寿命上限（毫秒）：兜底,避免 {@link #stop()} 从未被调用时采样线程长跑（5 分钟足够覆盖最慢的启动）。 */
    private static final long MAX_SAMPLING_MS = 5L * 60L * 1000L;

    /** 单条折叠栈最多保留的帧数:超深栈(异常递归等)只保留栈顶侧 {@code MAX_FRAMES} 帧,限制串长与内存。 */
    private static final int MAX_FRAMES = 160;

    /** 采样守护线程本体。 */
    private final Thread worker;

    /** 运行标志：{@code volatile} 保证 {@link #stop()} 的写对采样线程立即可见。 */
    private volatile boolean running = true;

    StartupStackSampler() {
        this.worker = new Thread(this, "ServerProbe-Startup-Sampler");
        // daemon：随主程序退出而退出,绝不拖住 JVM 关闭。
        this.worker.setDaemon(true);
        // 略低于默认优先级：采样是旁路观测,不与启动主逻辑抢 CPU。
        this.worker.setPriority(Thread.NORM_PRIORITY - 1);
    }

    /** 启动采样守护线程,并把采样周期写入数据桥(供画像/报告做"采样数 × 周期"的耗时估算)。 */
    void start() {
        ProbeAgentBridge.setSampleIntervalMs(SAMPLE_INTERVAL_MS);
        worker.start();
    }

    /**
     * 请求停止采样（幂等）：置标志位并中断采样线程的 sleep,使其尽快退出。
     *
     * <p>供插件就绪后调用,把采样窗口收敛在"启动期"。
     */
    void stop() {
        running = false;
        worker.interrupt();
    }

    /**
     * 采样主循环：周期性对全部存活线程做一次折叠栈快照,直至停止或超时。
     */
    @Override
    public void run() {
        long deadlineNanos = System.nanoTime() + MAX_SAMPLING_MS * 1_000_000L;
        while (running && System.nanoTime() < deadlineNanos) {
            try {
                sampleAllThreads();
            } catch (Throwable ignored) {
                // 任何异常都不允许逃逸：采样是旁路观测,绝不波及其它线程或拖垮 JVM。
            }
            sleepInterval();
        }
    }

    /**
     * 对当前全部存活线程做一次折叠栈快照。
     *
     * <p>用 {@link Thread#getAllStackTraces()} 单次取回所有线程栈(一个安全点);逐线程把其栈自栈底到栈顶
     * 折叠为一行(每帧 {@code 类全名#方法名},忽略行号)计入 {@link ProbeAgentBridge#recordFoldedStack}。
     * 跳过采样线程自身与空栈线程。
     */
    private void sampleAllThreads() {
        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        Thread self = Thread.currentThread();
        for (Map.Entry<Thread, StackTraceElement[]> entry : all.entrySet()) {
            Thread thread = entry.getKey();
            if (thread == self) continue; // 不采样自己,避免把采样线程计入热点
            StackTraceElement[] stack = entry.getValue();
            if (stack == null || stack.length == 0) continue;
            String folded = foldStack(stack);
            if (folded.isEmpty()) continue;
            ProbeAgentBridge.recordFoldedStack(thread.getName(), folded);
        }
    }

    /**
     * 把一条栈快照折叠成 {@code 栈底帧;...;栈顶帧} 单行。
     *
     * <p>{@link Thread#getStackTrace()} / {@code getAllStackTraces} 返回的数组<b>栈顶在前</b>(索引 0 为当前
     * 执行帧),而火焰图约定自栈底(root)到栈顶(叶)排列,故此处<b>逆序</b>拼接。超过 {@link #MAX_FRAMES} 的
     * 深栈只保留栈顶侧 {@code MAX_FRAMES} 帧(当前在执行的部分最具诊断价值)。
     *
     * @param stack 栈快照(非空)
     * @return 折叠栈串
     */
    private static String foldStack(StackTraceElement[] stack) {
        int top = Math.min(stack.length, MAX_FRAMES);
        StringBuilder sb = new StringBuilder(top * 32);
        for (int i = top - 1; i >= 0; i--) {
            StackTraceElement frame = stack[i];
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(frame.getClassName()).append('#').append(frame.getMethodName());
        }
        return sb.toString();
    }

    /**
     * 休眠一个采样周期；被 {@link #stop()} 中断时复位中断标志并让主循环凭 {@code running} 退出。
     */
    private void sleepInterval() {
        try {
            Thread.sleep(SAMPLE_INTERVAL_MS);
        } catch (InterruptedException e) {
            // 复位中断标志：由 running 标志统一控制退出,避免中断状态泄漏给后续逻辑。
            Thread.currentThread().interrupt();
        }
    }
}
