package top.wcpe.mc.plugin.serverprobe.e2e.mcp;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender.Spigot;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Paper 与 Folia 共用的 FR14 MCP 验收桩；诊断目标不访问平台状态。 */
public final class BukkitMcpDiagnosticsHarness extends JavaPlugin {

    private static final String PAPER_SCENARIO = "mcp-diagnostics-paper-java21";
    private static final String FOLIA_SCENARIO = "mcp-diagnostics-folia";
    private static final String SPIGOT_SCENARIO = "mcp-diagnostics-spigot";
    private static final String TARGET_VALUE = "mcp-arthas-target";

    /** 非控制台发送者冒充标识，出现于失败信息便于定位。 */
    private static final String NON_CONSOLE_SENDER_NAME = "e2e-command-block";

    /** 被验证的"仅控制台"命令：status 为纯内存读取，不产生副作用。 */
    private static final String NON_CONSOLE_COMMAND = "probe mcp status";

    /** 主线程派发命令的等待上限：主线程被长任务占用时以失败暴露，而不是无限挂住验收。 */
    private static final long DISPATCH_TIMEOUT_SECONDS = 30L;

    /** `command-mcp-status-title` 的可识别片段（zh / en）：出现即说明命令被执行、控制面状态已回执。 */
    private static final List<String> STATUS_TITLE_MARKERS =
        Collections.unmodifiableList(Arrays.asList("MCP 控制面状态", "MCP control plane status"));

    /** `command-mcp-console-only` 的可识别片段（zh / en）：出现即说明"仅控制台"门拦下了发送者。 */
    private static final List<String> CONSOLE_ONLY_MARKERS =
        Collections.unmodifiableList(Arrays.asList("仅允许在服务器控制台执行", "console-only"));

    /**
     * watch / trace 任务的超时：须**明显大于**触发窗口，否则任务会在持续触发期间先行超时
     * （窗口见 [ARTHAS_TRIGGER_ATTEMPTS] × [ARTHAS_TRIGGER_INTERVAL_MILLIS]）。
     */
    private static final int ARTHAS_OBSERVE_TIMEOUT_MILLIS = 20_000;

    /** 触发窗口 ≈ 50 × 200ms = 10s，覆盖 Arthas 懒初始化 + 类增强完成较慢的情形。 */
    private static final int ARTHAS_TRIGGER_ATTEMPTS = 50;
    private static final long ARTHAS_TRIGGER_INTERVAL_MILLIS = 200L;

    @Override
    public void onEnable() {
        if (PAPER_SCENARIO.equals(System.getenv("MC_TESTKIT_E2E_SCENARIO"))) {
            startVerification(() -> McpDiagnosticsE2eSupport.verifyPlatformAndModernArthas(PAPER_SCENARIO, "list", client -> {
                verifyModernArthas(client);
                verifyNonConsoleSenderRejected();
            }));
            return;
        }
        if (SPIGOT_SCENARIO.equals(System.getenv("MC_TESTKIT_E2E_SCENARIO"))) {
            startVerification(() -> McpDiagnosticsE2eSupport.verifyPlatform(SPIGOT_SCENARIO, "list"));
            return;
        }
        startVerification(() -> McpDiagnosticsE2eSupport.verifyPlatform(FOLIA_SCENARIO, "list"));
    }

    /** MCP 客户端只访问回环 HTTP，不调用 Bukkit API，故可由独立线程兼容 Folia。 */
    private void startVerification(Runnable verification) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(5_000L);
                verification.run();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }, "serverprobe-mcp-diagnostics-e2e");
        thread.setDaemon(true);
        thread.start();
    }

    /** 调用纯函数触发有限次数的 Arthas watch 与 trace，不改变服务器状态。 */
    private void verifyModernArthas(McpDiagnosticsE2eSupport.Client client) throws Exception {
        String className = BukkitMcpDiagnosticsHarness.class.getName();
        String watchTask = client.task("arthas_watch", "{\"className\":\"" + className + "\",\"methodName\":\"watchTarget\",\"expression\":\"{params,returnObj}\",\"maxMatches\":1,\"timeoutMillis\":" + ARTHAS_OBSERVE_TIMEOUT_MILLIS + "}");
        invokeTargetUntilComplete(client, watchTask);
        String watch = client.awaitSuccess(watchTask, "watch");
        require(watch.contains(TARGET_VALUE), "结构化 watch 未记录目标调用");

        String traceTask = client.task("arthas_trace", "{\"className\":\"" + className + "\",\"methodName\":\"watchTarget\",\"maxMatches\":1,\"timeoutMillis\":" + ARTHAS_OBSERVE_TIMEOUT_MILLIS + "}");
        invokeTargetUntilComplete(client, traceTask);
        client.awaitSuccess(traceTask, "trace");

        String artifactName = copyOriginalClassArtifact();
        client.awaitSuccess(client.task("arthas_redefine", "{\"artifactName\":\"" + artifactName + "\",\"timeoutMillis\":5000}"), "redefine");
        String retransformOutput = client.awaitSuccess(client.task("arthas_retransform", "{\"artifactName\":\"" + artifactName + "\",\"timeoutMillis\":5000}"), "retransform");
        String retransformList = client.awaitSuccess(client.task("arthas_execute", "{\"command\":\"retransform -l\",\"timeoutMillis\":5000}"), "retransform 列表");
        client.awaitSuccess(client.task("arthas_revert", "{\"entryId\":" + retransformEntryId(retransformOutput, retransformList, className) + ",\"timeoutMillis\":5000}"), "revert");
    }

    /**
     * 持续触发观察目标直到任务进入终态。
     *
     * 触发窗口必须**显著长于** Arthas 的懒初始化 + 类增强耗时：本 harness 的第一个 Arthas 调用就是
     * watch/trace，任务提交后 Arthas 才 bootstrap（实测 ~0.6s）再增强（~0.3s）；若把窗口写成与之
     * 相当的一小段（如 10×100ms），窗口内调用会全部落在增强完成之前，任务只能等到自身超时。
     * 实测该写法在 java21 后端约 1/3 概率假红（同输入时好时坏），放宽到数十次 × 百毫秒即稳定。
     */
    private void invokeTargetUntilComplete(McpDiagnosticsE2eSupport.Client client, String taskId) throws Exception {
        long startedAt = System.currentTimeMillis();
        for (int attempt = 0; attempt < ARTHAS_TRIGGER_ATTEMPTS; attempt++) {
            watchTarget(TARGET_VALUE);
            String status = client.call("arthas_task_status", "{\"taskId\":\"" + taskId + "\"}");
            if (status.contains("SUCCEEDED")) return;
            if (status.contains("FAILED") || status.contains("CANCELLED") || status.contains("TIMED_OUT")) {
                throw new IllegalStateException(
                    "Arthas 任务启动失败：第 " + (attempt + 1) + " 次触发，" + (System.currentTimeMillis() - startedAt) + "ms，" + status
                        + " 任务输出：" + client.call("arthas_task_output", "{\"taskId\":\"" + taskId + "\"}")
                );
            }
            Thread.sleep(ARTHAS_TRIGGER_INTERVAL_MILLIS);
        }
    }

    /** 仅作为一次性 Arthas 观察目标。 */
    public static String watchTarget(String value) {
        return value;
    }

    /** 只写入当前已加载类的原始字节码，redefine 与 retransform 均不会改变其行为。 */
    private String copyOriginalClassArtifact() throws Exception {
        String resource = "/" + BukkitMcpDiagnosticsHarness.class.getName().replace('.', '/') + ".class";
        File target = new File(new File(getDataFolder().getParentFile(), "ServerProbe/mcp-workspace/artifacts"), "mcp-bukkit-original.class");
        target.getParentFile().mkdirs();
        InputStream source = BukkitMcpDiagnosticsHarness.class.getResourceAsStream(resource);
        if (source == null) throw new IllegalStateException("未找到 FR14 原始类字节码资源");
        try {
            Files.copy(source, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            source.close();
        }
        return target.getName();
    }

    private int retransformEntryId(String output, String listOutput, String className) {
        String plain = output.replace("\\", "");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:entry\\s*)?id\\s*[:=]\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(plain);
        if (matcher.find()) return Integer.parseInt(matcher.group(1));
        String listed = listOutput.replace("\\", "");
        matcher = java.util.regex.Pattern.compile("(?:^|[^0-9])(\\d+)\\s+" + java.util.regex.Pattern.quote(className)).matcher(listed);
        if (matcher.find()) return Integer.parseInt(matcher.group(1));
        matcher = java.util.regex.Pattern.compile("ClassName.*?n(\\d+)\\s+", java.util.regex.Pattern.DOTALL).matcher(listed);
        if (matcher.find()) return Integer.parseInt(matcher.group(1));
        throw new IllegalStateException("retransform 未返回可恢复条目编号：" + listed);
    }

    /**
     * 非控制台发送者（命令方块）执行 `/probe mcp` 必须被"仅控制台"门拒绝。
     *
     * 判定只用回执文案：捕获到 `command-mcp-status-title` 说明命令确实被执行、控制面状态已回执，
     * 即"仅控制台"门未拦住该发送者；捕获到 `command-mcp-console-only` 才算门按预期工作。
     * 两条都不出现同样判失败，避免"静默放行 / 文案缺失"被当成通过。
     */
    private void verifyNonConsoleSenderRejected() throws Exception {
        NonConsoleCommandSender sender = new NonConsoleCommandSender();
        CountDownLatch dispatched = new CountDownLatch(1);
        Bukkit.getScheduler().runTask(this, () -> {
            Bukkit.dispatchCommand(sender, NON_CONSOLE_COMMAND);
            dispatched.countDown();
        });
        require(dispatched.await(DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS), "主线程派发 " + NON_CONSOLE_COMMAND + " 超时");
        List<String> messages = sender.messages();
        require(!containsMarker(messages, STATUS_TITLE_MARKERS), "非控制台发送者未被拒绝，捕获=" + messages);
        require(containsMarker(messages, CONSOLE_ONLY_MARKERS), "未捕获到仅控制台提示，捕获=" + messages);
    }

    /** 捕获消息中是否含任一标记片段。 */
    private static boolean containsMarker(List<String> messages, List<String> markers) {
        for (String message : messages) {
            for (String marker : markers) {
                if (message.contains(marker)) return true;
            }
        }
        return false;
    }

    /** 命令方块发送者替身：权限恒真，仅收集回执文案。 */
    private static final class NonConsoleCommandSender implements BlockCommandSender {

        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());

        List<String> messages() {
            synchronized (messages) {
                return new ArrayList<>(messages);
            }
        }

        @Override
        public void sendMessage(String message) {
            messages.add(message);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void sendMessage(String... messages) {
            this.messages.addAll(Arrays.asList(messages));
        }

        @Override
        @SuppressWarnings("deprecation")
        public void sendMessage(UUID sender, String message) {
            messages.add(message);
        }

        @Override
        @SuppressWarnings("deprecation")
        public void sendMessage(UUID sender, String... messages) {
            this.messages.addAll(Arrays.asList(messages));
        }

        @Override
        public String getName() {
            return NON_CONSOLE_SENDER_NAME;
        }

        @Override
        public Server getServer() {
            return Bukkit.getServer();
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean isOp() {
            return true;
        }

        @Override
        @SuppressWarnings("deprecation")
        public void setOp(boolean value) {
        }

        @Override
        public boolean isPermissionSet(String name) {
            return true;
        }

        @Override
        public boolean isPermissionSet(Permission perm) {
            return true;
        }

        @Override
        public boolean hasPermission(String name) {
            return true;
        }

        @Override
        public boolean hasPermission(Permission perm) {
            return true;
        }

        @Override
        @SuppressWarnings("deprecation")
        public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
            return null;
        }

        @Override
        public PermissionAttachment addAttachment(Plugin plugin) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) {
            return null;
        }

        @Override
        @SuppressWarnings("deprecation")
        public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
            return null;
        }

        @Override
        public void removeAttachment(PermissionAttachment attachment) {
        }

        @Override
        @SuppressWarnings("deprecation")
        public Spigot spigot() {
            // 桩不承载 Spigot 专有回执通道；回执一律经 sendMessage 收集。
            return null;
        }

        @Override
        public void recalculatePermissions() {
        }

        @Override
        public Set<PermissionAttachmentInfo> getEffectivePermissions() {
            return Collections.emptySet();
        }

        @Override
        public Block getBlock() {
            return Bukkit.getWorlds().get(0).getBlockAt(0, 100, 0);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
