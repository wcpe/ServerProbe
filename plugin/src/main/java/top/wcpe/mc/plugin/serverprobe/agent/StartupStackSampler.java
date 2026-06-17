package top.wcpe.mc.plugin.serverprobe.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动期多线程栈采样器（纯 Java 守护线程）。
 *
 * <p>由 {@link ProbeAgent#bootstrap} 启动，周期性读取多个关键线程（主线程、Netty IO、Folia ServerMain 等）
 * 的<b>完整调用栈</b>，自栈底到栈顶折叠成一行（{@code 栈底帧;...;栈顶帧}）后计入
 * {@link ProbeAgentBridge#recordFoldedStack}。折叠栈保留帧间父→子关系，是还原真正多层火焰图的数据源
 * （区别于逐帧词频——后者无法还原调用层级）。</p>
 *
 * <p><b>安全第一——绝不成为事故源</b>：
 * <ul>
 *   <li><b>daemon 线程</b>：不阻塞 JVM 退出。</li>
 *   <li><b>只读栈、不持锁</b>：仅调用 {@link Thread#getStackTrace()}（只读快照），从不对主线程加锁或干预。</li>
 *   <li><b>异常全吞</b>：每个采样周期独立 try/catch，单次失败不影响后续，更不波及主线程。</li>
 *   <li><b>找不到/已消失即跳过</b>：目标线程未出现时轮询等待；缓存到引用后若线程不再存活则移除。</li>
 *   <li><b>有限寿命</b>：即便未调用 {@link #stop()}，也会在 {@link #MAX_SAMPLING_MS} 上限后自停，
 *       绝不把采样泄漏到运行期长跑。</li>
 * </ul>
 *
 * <p>设计约束：<b>纯 JDK</b>，由 system ClassLoader 加载，只依赖 {@code java.*}，不触碰 kotlin/taboolib/bukkit。
 */
final class StartupStackSampler implements Runnable {

    /** 采样周期（毫秒）：5ms ≈ 200Hz，足以刻画启动期关键线程热点，开销可忽略。 */
    private static final long SAMPLE_INTERVAL_MS = 5L;

    /** 采样寿命上限（毫秒）：兜底，避免 {@link #stop()} 从未被调用时采样线程长跑（5 分钟足够覆盖最慢的启动）。 */
    private static final long MAX_SAMPLING_MS = 5L * 60L * 1000L;

    /** 目标线程名前缀：Bukkit 主线程、Netty IO 线程、Folia ServerMain。 */
    private static final String[] TARGET_THREAD_PREFIXES = {
        "Server thread",
        "Netty Server IO",
        "ServerMain"
    };

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
     * 采样主循环：先定位并缓存目标线程列表，再周期性累计各线程栈帧热点，直至停止/超时/所有线程消失。
     */
    @Override
    public void run() {
        long deadlineNanos = System.nanoTime() + MAX_SAMPLING_MS * 1_000_000L;
        // 目标线程列表一旦找到即缓存，避免每周期重复全量扫描 getAllStackTraces。
        List<Thread> targetThreads = null;

        while (running && System.nanoTime() < deadlineNanos) {
            try {
                if (targetThreads == null || targetThreads.isEmpty()) {
                    targetThreads = findTargetThreads();
                    // 仍未出现：等待下一周期再找（启动早期线程可能尚未命名）。
                    if (targetThreads.isEmpty()) {
                        sleepInterval();
                        continue;
                    }
                }
                // 移除已退出的线程。
                targetThreads.removeIf(t -> !t.isAlive());
                // 所有目标线程均已退出，采样使命完成，自停。
                if (targetThreads.isEmpty()) {
                    break;
                }
                sampleAllThreads(targetThreads);
            } catch (Throwable ignored) {
                // 任何异常都不允许逃逸：采样是旁路观测，绝不波及主线程或拖垮 JVM。
            }
            sleepInterval();
        }
    }

    /**
     * 在所有存活线程中查找名称以 {@link #TARGET_THREAD_PREFIXES} 任一前缀开头的目标线程。
     *
     * <p>用 {@link Thread#getAllStackTraces()} 的键集枚举线程；返回匹配的线程列表（可能为空）。
     *
     * @return 匹配的目标线程列表；未找到时为空列表
     */
    private static List<Thread> findTargetThreads() {
        List<Thread> result = new ArrayList<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (!thread.isAlive()) continue;
            String name = thread.getName();
            for (String prefix : TARGET_THREAD_PREFIXES) {
                if (name.startsWith(prefix)) {
                    result.add(thread);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * 对所有目标线程做一次栈快照并以折叠栈记录。
     *
     * <p>每条栈自栈底到栈顶折叠成一行（{@code 栈底帧;...;栈顶帧}，每帧 {@code 类全名#方法名}，忽略行号），
     * 整行连同线程名一并计入 {@link ProbeAgentBridge#recordFoldedStack}；保留完整调用路径供下游还原火焰图层级。
     * 空栈（线程恰好无 Java 帧）自然跳过。
     *
     * @param threads 已确认存活的目标线程列表
     */
    private static void sampleAllThreads(List<Thread> threads) {
        for (Thread thread : threads) {
            if (!thread.isAlive()) continue;
            StackTraceElement[] stack = thread.getStackTrace();
            if (stack == null || stack.length == 0) continue;
            String folded = foldStack(stack);
            if (folded.isEmpty()) continue;
            ProbeAgentBridge.recordFoldedStack(thread.getName(), folded);
        }
    }

    /**
     * 把一条栈快照折叠成 {@code 栈底帧;...;栈顶帧} 单行。
     *
     * <p>{@link Thread#getStackTrace()} 返回的数组<b>栈顶在前</b>（索引 0 为当前执行帧），而火焰图约定
     * 自栈底（root）到栈顶（叶）排列，故此处<b>逆序</b>拼接。
     *
     * @param stack 栈快照（非空）
     * @return 折叠栈串
     */
    private static String foldStack(StackTraceElement[] stack) {
        StringBuilder sb = new StringBuilder();
        for (int i = stack.length - 1; i >= 0; i--) {
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
            // 复位中断标志：由 running 标志统一控制退出，避免中断状态泄漏给后续逻辑。
            Thread.currentThread().interrupt();
        }
    }
}
