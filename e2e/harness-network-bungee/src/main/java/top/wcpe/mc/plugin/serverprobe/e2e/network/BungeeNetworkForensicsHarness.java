package top.wcpe.mc.plugin.serverprobe.e2e.network;

import net.md_5.bungee.api.plugin.Plugin;
import java.util.concurrent.TimeUnit;

/** BungeeCord 的 FR11 异步验收桩。 */
public final class BungeeNetworkForensicsHarness extends Plugin {

    @Override
    public void onEnable() {
        getProxy().getScheduler().schedule(this, () ->
            NetworkForensicsE2eSupport.verifyIfRequested("network-forensics-bungee", "bungee.", 9952, 9953, 75), 5L, TimeUnit.SECONDS);
    }
}
