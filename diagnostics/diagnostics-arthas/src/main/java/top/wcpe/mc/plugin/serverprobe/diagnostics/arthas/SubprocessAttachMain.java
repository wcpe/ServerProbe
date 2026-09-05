package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas;

/**
 * 一次性外置注入进程（由探针 spawn，对目标 JVM attach 并加载 agent，随后立即退出）。
 *
 * 必须保持纯 Java、零第三方依赖：helper 以发行 Jar 为 classpath 独立启动，
 * 此时 Kotlin 运行时（重定位包）尚未在 helper JVM 中提供，任何 Kotlin 类引用都会
 * NoClassDefFoundError。全部 attach 调用走反射，仅依赖 JDK 的 jdk.attach 模块。
 */
public final class SubprocessAttachMain {

    private SubprocessAttachMain() {
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("用法:SubprocessAttachMain <目标PID> <agentJar 绝对路径>");
            System.exit(2);
        }
        // helper 进程是 JVM 外部边界:任何异常(含 Error)都只能以非零码退出并回传原因,故 catch(Throwable) 有意为之。
        try {
            Class<?> type = Class.forName("com.sun.tools.attach.VirtualMachine");
            Object vm = type.getMethod("attach", String.class).invoke(null, args[0]);
            try {
                type.getMethod("loadAgent", String.class).invoke(vm, args[1]);
            } finally {
                type.getMethod("detach").invoke(vm);
            }
            System.out.println("[ServerProbe] helper 注入完成:pid=" + args[0] + " agent=" + args[1]);
        } catch (Throwable failure) {
            System.err.println("[ServerProbe] helper 注入失败:" + failure.getClass().getName() + ":" + failure.getMessage());
            System.exit(1);
        }
    }
}
