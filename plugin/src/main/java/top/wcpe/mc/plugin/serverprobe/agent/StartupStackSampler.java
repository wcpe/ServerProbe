package top.wcpe.mc.plugin.serverprobe.agent;

/**
 * 启动期 "Server thread" 主线程栈采样器（纯 Java 守护线程）。
 *
 * <p>由 {@link ProbeAgent#bootstrap} 启动，周期性读取 Bukkit 主线程（线程名固定为 {@code "Server thread"}）
 * 的调用栈，把每一帧计入 {@link ProbeAgentBridge} 的热点表，形成"启动期主线程在哪卡住"的热点榜
 * （补足字节码 hook 看不到的非插件耗时，如世界生成、区块加载、原版初始化）。
 *
 * <p><b>安全第一——绝不成为事故源</b>：
 * <ul>
 *   <li><b>daemon 线程</b>：不阻塞 JVM 退出。</li>
 *   <li><b>只读栈、不持锁</b>：仅调用 {@link Thread#getStackTrace()}（只读快照），从不对主线程加锁或干预。</li>
 *   <li><b>异常全吞</b>：每个采样周期独立 try/catch，单次失败不影响后续，更不波及主线程。</li>
 *   <li><b>找不到/已消失即跳过</b>：主线程未出现时轮询等待；缓存到引用后若线程不再存活则自停。</li>
 *   <li><b>有限寿命</b>：即便 A3 未调用 {@link #stop()}，也会在 {@link #MAX_SAMPLING_MS} 上限后自停，
 *       绝不把采样泄漏到运行期长跑。</li>
 * </ul>
 *
 * <p>设计约束：<b>纯 JDK</b>，由 system ClassLoader 加载，只依赖 {@code java.*}，不触碰 kotlin/taboolib/bukkit。
 */
final class StartupStackSampler implements Runnable {

    /** 采样周期（毫秒）：20ms ≈ 50Hz，足以刻画启动期主线程热点，开销可忽略。 */
    private static final long SAMPLE_INTERVAL_MS = 20L;

    /** 采样寿命上限（毫秒）：兜底，避免 {@link #stop()} 从未被调用时采样线程长跑（5 分钟足够覆盖最慢的启动）。 */
    private static final long MAX_SAMPLING_MS = 5L * 60L * 1000L;

    /** 目标主线程名：Bukkit/Spigot/Paper 的主线程固定命名为 {@code "Server thread"}。 */
    private static final String TARGET_THREAD_NAME = "Server thread";

    /** 采样守护线程本体。 */
    private final Thread worker;

    /** 运行标志：{@code volatile} 保证 {@link #stop()} 的写对采样线程立即可见。 */
    private volatile boolean running = true;

    StartupStackSampler() {
        this.worker = new Thread(this, "ServerProbe-Startup-Sampler");
        // daemon：随主程序退出而退出，绝不拖住 JVM 关闭。
        this.worker.setDaemon(true);
        // 略低于默认优先级：采样是旁路观测，不与启动主逻辑抢 CPU。
        this.worker.setPriority(Thread.NORM_PRIORITY - 1);
    }

    /** 启动采样守护线程。 */
    void start() {
        worker.start();
    }

    /**
     * 请求停止采样（幂等）：置标志位并中断采样线程的 sleep，使其尽快退出。
     *
     * <p>供 A3 插件就绪后调用，把采样窗口收敛在"启动期"。
     */
    void stop() {
        running = false;
        worker.interrupt();
    }

    /**
     * 采样主循环：先定位并缓存主线程，再周期性累计其栈帧热点，直至停止/超时/主线程消失。
     */
    @Override
    public void run() {
        long deadlineNanos = System.nanoTime() + MAX_SAMPLING_MS * 1_000_000L;
        // 主线程引用一旦找到即缓存，避免每周期重复全量扫描 getAllStackTraces。
        Thread serverThread = null;

        while (running && System.nanoTime() < deadlineNanos) {
            try {
                if (serverThread == null) {
                    serverThread = findServerThread();
                    // 仍未出现：等待下一周期再找（启动早期主线程可能尚未命名）。
                    if (serverThread == null) {
                        sleepInterval();
                        continue;
                    }
                }
                // 缓存的主线程若已结束，采样使命完成，自停。
                if (!serverThread.isAlive()) {
                    break;
                }
                sampleOnce(serverThread);
            } catch (Throwable ignored) {
                // 任何异常都不允许逃逸：采样是旁路观测，绝不波及主线程或拖垮 JVM。
            }
            sleepInterval();
        }
    }

    /**
     * 在所有存活线程中查找名为 {@link #TARGET_THREAD_NAME} 的主线程。
     *
     * <p>用 {@link Thread#getAllStackTraces()} 的键集枚举线程；找不到返回 {@code null}（调用方下周期重试）。
     *
     * @return 主线程引用；未找到时为 {@code null}
     */
    private static Thread findServerThread() {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (TARGET_THREAD_NAME.equals(thread.getName())) {
                return thread;
            }
        }
        return null;
    }

    /**
     * 对主线程做一次栈快照并累计全部栈帧热点。
     *
     * <p>全栈帧计数（而非仅栈顶）：能同时反映"热点方法"与"热点调用路径上的方法"，热点榜信息更丰富；
     * 每帧标识取 {@code 类全名#方法名}（忽略行号，按方法聚合）。空栈（线程恰好无 Java 帧）自然跳过。
     *
     * @param serverThread 已确认存活的主线程
     */
    private static void sampleOnce(Thread serverThread) {
        StackTraceElement[] stack = serverThread.getStackTrace();
        if (stack == null || stack.length == 0) {
            return;
        }
        for (StackTraceElement frame : stack) {
            ProbeAgentBridge.recordStackSample(frame.getClassName() + '#' + frame.getMethodName());
        }
    }

    /**
     * 休眠一个采样周期；被 {@link #stop()} 中断时复位中断标志并让主循环凭 {@code running} 退出。
     */
    private void sleepInterval() {
        try {
            Thread.sleep(SAMPLE_INTERVAL_MS);
        } catch (InterruptedException e) {
            // 复位中断标志：由 running 标志统一控制退出，避免中断状态泄漏给后续逻辑。
            Thread.currentThread().interrupt();
        }
    }
}
