package top.wcpe.mc.plugin.serverprobe.e2e.proxy;

import net.md_5.bungee.api.plugin.Plugin;
import top.wcpe.mc.plugin.serverprobe.e2e.ManagedProxyHarness;

/**
 * BungeeCord 侧的托管夹具：在 {@code onEnable} 里先监听，探针随后才建立反向连接。
 *
 * <p>夹具与探针同进程，端口与凭据都直接读身份文件（{@code agent-identity.json}），
 * 因此这里不需要任何命令行参数或外部进程。</p>
 */
public final class BungeeManagedHarness extends Plugin {

    /** 探针在 Bungee 侧的数据目录名（由 bungee.yml 的 name 派生，大写）。 */
    private static final String PROBE_DATA_DIR = "ServerProbe";

    /** mc-testkit 注入的场景名。 */
    private static final String SCENARIO_ENV = "MC_TESTKIT_E2E_SCENARIO";

    /** 场景缺省名（环境变量缺失时的兼容值，与结果文件同名）。 */
    private static final String DEFAULT_SCENARIO = "managed-config-bungee";

    /** 运行中的夹具；未启动时为 null。 */
    private ManagedProxyHarness harness;

    @Override
    public void onEnable() {
        String scenario = System.getenv(SCENARIO_ENV);
        harness = new ManagedProxyHarness(
            getDataFolder().getParentFile(),
            PROBE_DATA_DIR,
            scenario == null || scenario.isEmpty() ? DEFAULT_SCENARIO : scenario);
        harness.start();
    }

    @Override
    public void onDisable() {
        if (harness != null) {
            harness.close();
            harness = null;
        }
    }
}
