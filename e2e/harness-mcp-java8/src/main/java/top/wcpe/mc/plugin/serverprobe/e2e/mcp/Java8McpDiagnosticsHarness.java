package top.wcpe.mc.plugin.serverprobe.e2e.mcp;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Java8 运行时的 Arthas 3.1.1 类搜索验收桩。 */
public final class Java8McpDiagnosticsHarness extends JavaPlugin {

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, () ->
            McpDiagnosticsE2eSupport.verifyJava8ClassSearch(
                "mcp-diagnostics-java8",
                new java.io.File(getDataFolder().getParentFile(), "ServerProbe"),
                Java8McpDiagnosticsHarness.class.getName()
            ), 100L);
    }
}
