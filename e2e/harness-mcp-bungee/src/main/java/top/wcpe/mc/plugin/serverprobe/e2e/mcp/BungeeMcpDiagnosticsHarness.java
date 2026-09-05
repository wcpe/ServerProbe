package top.wcpe.mc.plugin.serverprobe.e2e.mcp;

import net.md_5.bungee.api.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/** BungeeCord 的 FR14 HTTP MCP 验收桩。 */
public final class BungeeMcpDiagnosticsHarness extends Plugin {

    @Override
    public void onEnable() {
        getProxy().getScheduler().schedule(this, () ->
            McpDiagnosticsE2eSupport.verifyPlatform("mcp-diagnostics-bungee", "glist"), 5L, TimeUnit.SECONDS);
    }
}
