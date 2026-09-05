package top.wcpe.mc.plugin.serverprobe.e2e.mcp;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.concurrent.TimeUnit;

/** Velocity 3.x 的 FR14 HTTP MCP 验收桩。 */
@Plugin(id = "serverprobe-mcp-velocity-harness", name = "ServerProbeMcpVelocityHarness", version = "1.0.0-SNAPSHOT")
public final class VelocityMcpDiagnosticsHarness {

    private static final String VELOCITY_SCENARIO = "mcp-diagnostics-velocity";
    private static final String JAVA25_SCENARIO = "mcp-diagnostics-velocity-java25";

    private final ProxyServer server;

    @Inject
    public VelocityMcpDiagnosticsHarness(ProxyServer server) {
        this.server = server;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        server.getScheduler().buildTask(this, this::verify)
            .delay(5L, TimeUnit.SECONDS)
            .schedule();
    }

    /** Java25 场景还需由实际 Arthas 命令证明选择并运行现代 4.3.2 闭包。 */
    private void verify() {
        if (JAVA25_SCENARIO.equals(System.getenv("MC_TESTKIT_E2E_SCENARIO"))) {
            McpDiagnosticsE2eSupport.verifyPlatformAndModernArthas(JAVA25_SCENARIO, "velocity version", this::verifyModernArthas);
            return;
        }
        McpDiagnosticsE2eSupport.verifyPlatform(VELOCITY_SCENARIO, "velocity version");
    }

    private void verifyModernArthas(McpDiagnosticsE2eSupport.Client client) throws Exception {
        String version = client.awaitSuccess(client.task("arthas_execute", "{\"command\":\"version\",\"timeoutMillis\":5000}"), "version");
        require(version.contains("4.3.2"), "Java25 未选择 Arthas 4.3.2：" + version);
        String className = VelocityMcpDiagnosticsHarness.class.getName();
        String output = client.awaitSuccess(client.task("arthas_execute", "{\"command\":\"sc " + className + "\",\"timeoutMillis\":5000}"), "sc");
        require(output.contains(className), "Arthas 4.3.2 未搜索到 Velocity 验收类");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
