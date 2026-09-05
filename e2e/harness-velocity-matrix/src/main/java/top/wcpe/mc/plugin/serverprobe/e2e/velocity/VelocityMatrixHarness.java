package top.wcpe.mc.plugin.serverprobe.e2e.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import top.wcpe.mc.plugin.serverprobe.ServerProbeApi;
import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi;
import top.wcpe.mc.plugin.serverprobe.api.model.BackendServer;
import top.wcpe.mc.plugin.serverprobe.api.model.MetricSnapshot;
import top.wcpe.mc.plugin.serverprobe.api.model.PlayerPing;
import top.wcpe.mc.plugin.serverprobe.api.model.PlayerRoute;
import top.wcpe.mc.plugin.serverprobe.api.model.ProxyMetrics;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Velocity 3/4 共用的 FR13 真实双后端矩阵验收桩。 */
@Plugin(id = "serverprobe-velocity-matrix-harness", name = "ServerProbeVelocityMatrixHarness", version = "1.0.0-SNAPSHOT")
public final class VelocityMatrixHarness {

    private static final String SCENARIO_ENV = "MC_TESTKIT_E2E_SCENARIO";
    private static final String RESULT_ENV = "MC_TESTKIT_E2E_RESULT_FILE";
    private static final int MAX_ATTEMPTS = 75;

    private final ProxyServer server;

    @Inject
    public VelocityMatrixHarness(ProxyServer server) {
        this.server = server;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        server.getScheduler().buildTask(this, this::verifyIfRequested)
            .delay(5L, TimeUnit.SECONDS)
            .schedule();
    }

    private void verifyIfRequested() {
        MatrixCase matrix = MatrixCase.forScenario(System.getenv(SCENARIO_ENV));
        String resultPath = System.getenv(RESULT_ENV);
        if (matrix == null || resultPath == null || resultPath.trim().isEmpty()) return;
        try {
            verifyEventually(matrix);
            writeResult(new File(resultPath), true, "FR13 Velocity 真实矩阵验收通过");
        } catch (Exception exception) {
            writeResult(new File(resultPath), false, exception.getMessage());
        }
    }

    private void verifyEventually(MatrixCase matrix) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                verifySnapshot(matrix);
                return;
            } catch (Exception exception) {
                lastFailure = exception;
                Thread.sleep(1_000L);
            }
        }
        throw new IllegalStateException("等待 FR13 Velocity 指标超时：" + lastFailure.getMessage(), lastFailure);
    }

    private void verifySnapshot(MatrixCase matrix) {
        ProbeReadApi api = ServerProbeApi.INSTANCE.read();
        require(api != null, "ServerProbe FR8 API 尚未就绪");
        MetricSnapshot snapshot = api.latestSnapshot();
        require(snapshot != null && snapshot.getProxy() != null, "尚未采集到代理指标");
        ProxyMetrics proxy = snapshot.getProxy();
        require(proxy.getTotalOnline() >= 2, "代理总在线不足两人");
        requireBackend(proxy.getBackends(), matrix.firstBackend);
        requireBackend(proxy.getBackends(), matrix.secondBackend);
        requireRoute(proxy.getPlayerRoutes(), matrix.observer, matrix.firstBackend);
        requireRoute(proxy.getPlayerRoutes(), matrix.switcher, matrix.secondBackend);
        requirePlayerPing(proxy.getPlayerPings(), matrix.observer);
        requirePlayerPing(proxy.getPlayerPings(), matrix.switcher);
    }

    private void requireBackend(List<BackendServer> backends, String name) {
        BackendServer backend = backends.stream().filter(candidate -> name.equals(candidate.getName())).findFirst()
            .orElseThrow(() -> new IllegalStateException("缺少后端指标：" + name));
        require(backend.isReachable() && backend.getPingMs() >= 0, "后端 RTT 或可达性未就绪：" + name);
    }

    private void requireRoute(List<PlayerRoute> routes, String player, String backend) {
        boolean matched = routes.stream().anyMatch(route ->
            player.equals(route.getName()) && backend.equals(route.getServer())
        );
        require(matched, "未观察到玩家路由：" + player + " -> " + backend);
    }

    private void requirePlayerPing(List<PlayerPing> pings, String player) {
        boolean matched = pings.stream().anyMatch(ping -> player.equals(ping.getName()) && ping.getPingMs() >= 0);
        require(matched, "未观察到玩家延迟：" + player);
    }

    private void writeResult(File result, boolean passed, String message) {
        try {
            File parent = result.getParentFile();
            if (parent != null) parent.mkdirs();
            Properties properties = new Properties();
            properties.setProperty("status", passed ? "PASS" : "FAIL");
            properties.setProperty("message", message == null ? "未知错误" : message);
            File temporary = new File(result.getPath() + ".tmp");
            try (java.io.OutputStream output = Files.newOutputStream(temporary.toPath())) {
                properties.store(output, "FR13 Velocity 真实矩阵验收结果");
            }
            Files.move(temporary.toPath(), result.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            // 结果文件无法落盘时，由 mc-testkit 的超时机制判定失败。
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    /** 每个代表版本的固定后端、机器人名称与目标路由。 */
    private static final class MatrixCase {

        private final String firstBackend;
        private final String secondBackend;
        private final String observer;
        private final String switcher;

        private MatrixCase(String suffix) {
            firstBackend = "paper-velocity-matrix-" + suffix + "-a";
            secondBackend = "paper-velocity-matrix-" + suffix + "-b";
            observer = "V" + suffix + "Observer";
            switcher = "V" + suffix + "Switcher";
        }

        private static MatrixCase forScenario(String scenario) {
            if ("velocity-matrix-v31".equals(scenario)) return new MatrixCase("v31");
            if ("velocity-matrix-v35".equals(scenario)) return new MatrixCase("v35");
            if ("velocity-matrix-v41".equals(scenario)) return new MatrixCase("v41");
            return null;
        }
    }
}
