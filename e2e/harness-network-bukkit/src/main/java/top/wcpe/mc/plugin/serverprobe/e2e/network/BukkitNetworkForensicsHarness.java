package top.wcpe.mc.plugin.serverprobe.e2e.network;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.util.function.Consumer;

/** Spigot/Paper/Folia 通用的 FR11 异步验收桩。 */
public final class BukkitNetworkForensicsHarness extends JavaPlugin {

    /** 入站自定义载荷包的类名随运行时映射而异：Spigot/Paper 1.20 为 Spigot 映射，Folia 1.21+ 为 Mojang 映射。 */
    private static final String SPIGOT_CUSTOM_PAYLOAD_TYPE = "bukkit.PacketPlayInCustomPayload";
    private static final String FOLIA_CUSTOM_PAYLOAD_TYPE = "bukkit.ServerboundCustomPayloadPacket";

    @Override
    public void onEnable() {
        String scenario = System.getenv("MC_TESTKIT_E2E_SCENARIO");
        if (!isNetworkForensicsScenario(scenario)) return;
        // 直连场景必须按白名单包类型与通道过滤：玩家加入时的高频区块包会把单页 100 条挤出查询结果。
        Runnable verification = () -> NetworkForensicsE2eSupport.verifyIfRequested(
            scenario, "bukkit.", 9950, 9951, 75, expectedCustomPayloadType(scenario), "serverprobe:test");
        if (isFolia()) {
            scheduleFoliaGlobal(verification);
            return;
        }
        Bukkit.getScheduler().runTaskLaterAsynchronously(this, verification, 100L);
    }

    private boolean isNetworkForensicsScenario(String scenario) {
        return "network-forensics-bukkit".equals(scenario)
            || "network-forensics-paper".equals(scenario)
            || "network-forensics-folia".equals(scenario);
    }

    private static String expectedCustomPayloadType(String scenario) {
        return "network-forensics-folia".equals(scenario)
            ? FOLIA_CUSTOM_PAYLOAD_TYPE
            : SPIGOT_CUSTOM_PAYLOAD_TYPE;
    }

    /** Folia 禁止 Bukkit 调度器：仅在全局区域调度器上启动独立验收线程；轮询若占用 region 线程会冻结全局 tick 并饿死探针调度任务。 */
    private void scheduleFoliaGlobal(Runnable verification) {
        try {
            Object scheduler = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
            scheduler.getClass().getMethod("runDelayed", org.bukkit.plugin.Plugin.class, Consumer.class, long.class)
                .invoke(scheduler, this, (Consumer<Object>) ignored -> startVerifierThread(verification), 100L);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Folia 全局区域调度器不可用", exception);
        }
    }

    private static void startVerifierThread(Runnable verification) {
        Thread verifier = new Thread(verification, "ServerProbeFr11E2eVerifier");
        verifier.setDaemon(true);
        verifier.start();
    }

    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
