package top.wcpe.mc.plugin.serverprobe.e2e.mcp;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Paper 与 Folia 共用的 FR14 MCP 验收桩；诊断目标不访问平台状态。 */
public final class BukkitMcpDiagnosticsHarness extends JavaPlugin {

    private static final String PAPER_SCENARIO = "mcp-diagnostics-paper-java21";
    private static final String FOLIA_SCENARIO = "mcp-diagnostics-folia";
    private static final String SPIGOT_SCENARIO = "mcp-diagnostics-spigot";
    private static final String TARGET_VALUE = "mcp-arthas-target";

    @Override
    public void onEnable() {
        if (PAPER_SCENARIO.equals(System.getenv("MC_TESTKIT_E2E_SCENARIO"))) {
            startVerification(() -> McpDiagnosticsE2eSupport.verifyPlatformAndModernArthas(PAPER_SCENARIO, "list", this::verifyModernArthas));
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
        String watchTask = client.task("arthas_watch", "{\"className\":\"" + className + "\",\"methodName\":\"watchTarget\",\"expression\":\"{params,returnObj}\",\"maxMatches\":1,\"timeoutMillis\":5000}");
        invokeTargetUntilComplete(client, watchTask);
        String watch = client.awaitSuccess(watchTask, "watch");
        require(watch.contains(TARGET_VALUE), "结构化 watch 未记录目标调用");

        String traceTask = client.task("arthas_trace", "{\"className\":\"" + className + "\",\"methodName\":\"watchTarget\",\"maxMatches\":1,\"timeoutMillis\":5000}");
        invokeTargetUntilComplete(client, traceTask);
        client.awaitSuccess(traceTask, "trace");

        String artifactName = copyOriginalClassArtifact();
        client.awaitSuccess(client.task("arthas_redefine", "{\"artifactName\":\"" + artifactName + "\",\"timeoutMillis\":5000}"), "redefine");
        String retransformOutput = client.awaitSuccess(client.task("arthas_retransform", "{\"artifactName\":\"" + artifactName + "\",\"timeoutMillis\":5000}"), "retransform");
        String retransformList = client.awaitSuccess(client.task("arthas_execute", "{\"command\":\"retransform -l\",\"timeoutMillis\":5000}"), "retransform 列表");
        client.awaitSuccess(client.task("arthas_revert", "{\"entryId\":" + retransformEntryId(retransformOutput, retransformList, className) + ",\"timeoutMillis\":5000}"), "revert");
    }

    private void invokeTargetUntilComplete(McpDiagnosticsE2eSupport.Client client, String taskId) throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            Thread.sleep(100L);
            watchTarget(TARGET_VALUE);
            String status = client.call("arthas_task_status", "{\"taskId\":\"" + taskId + "\"}");
            if (status.contains("SUCCEEDED")) return;
            if (status.contains("FAILED") || status.contains("TIMED_OUT")) {
                throw new IllegalStateException("Arthas 任务启动失败：" + status);
            }
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
