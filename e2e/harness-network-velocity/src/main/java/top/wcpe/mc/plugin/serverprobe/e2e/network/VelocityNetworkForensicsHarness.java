package top.wcpe.mc.plugin.serverprobe.e2e.network;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.concurrent.TimeUnit;

/** Velocity 3/4 API 兼容的 FR11 异步验收桩。 */
@Plugin(id = "serverprobe-network-velocity-harness", name = "ServerProbeNetworkVelocityHarness", version = "1.0.0-SNAPSHOT")
public final class VelocityNetworkForensicsHarness {

    private final ProxyServer server;

    @Inject
    public VelocityNetworkForensicsHarness(ProxyServer server) {
        this.server = server;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        server.getScheduler().buildTask(this, () ->
            NetworkForensicsE2eSupport.verifyIfRequested(
                "network-forensics-velocity", "velocity.", 9954, 9955, 75,
                "velocity.PluginMessagePacket", "serverprobe:test"
            ))
            .delay(5L, TimeUnit.SECONDS)
            .schedule();
    }
}
