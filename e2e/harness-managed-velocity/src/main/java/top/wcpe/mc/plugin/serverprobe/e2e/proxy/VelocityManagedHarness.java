package top.wcpe.mc.plugin.serverprobe.e2e.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import top.wcpe.mc.plugin.serverprobe.e2e.ManagedProxyHarness;

import java.nio.file.Path;

/**
 * Velocity 侧的托管夹具：在代理初始化事件里先监听，探针随后才建立反向连接。
 *
 * <p>与 Bungee 侧同一套夹具核心；唯一差异是探针的数据目录名——Velocity 由插件 id 派生，
 * 实际为小写 {@code serverprobe}（Linux 大小写敏感，写错就读不到身份文件）。</p>
 */
@Plugin(
    id = "serverprobe-managed-velocity-harness",
    name = "ServerProbeManagedVelocityHarness",
    version = "1.0.0-SNAPSHOT"
)
public final class VelocityManagedHarness {

    /** 探针在 Velocity 侧的数据目录名（由 velocity-plugin.json 的 id 派生，小写）。 */
    private static final String PROBE_DATA_DIR = "serverprobe";

    /** mc-testkit 注入的场景名。 */
    private static final String SCENARIO_ENV = "MC_TESTKIT_E2E_SCENARIO";

    /** 场景缺省名（环境变量缺失时的兼容值，与结果文件同名）。 */
    private static final String DEFAULT_SCENARIO = "managed-config-velocity";

    /** 本插件的数据目录（由 Velocity 注入，形如 plugins/serverprobe-managed-velocity-harness）。 */
    private final Path dataDirectory;

    /** 运行中的夹具；未启动时为 null。 */
    private ManagedProxyHarness harness;

    @Inject
    public VelocityManagedHarness(@DataDirectory Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        String scenario = System.getenv(SCENARIO_ENV);
        harness = new ManagedProxyHarness(
            dataDirectory.toFile().getParentFile(),
            PROBE_DATA_DIR,
            scenario == null || scenario.isEmpty() ? DEFAULT_SCENARIO : scenario);
        harness.start();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (harness != null) {
            harness.close();
            harness = null;
        }
    }
}
