import io.izzel.taboolib.gradle.*

// BungeeCord 代理端采集器模块(Java 8,无 Bukkit/NMS,保持纯净)。
taboolib {
    env {
        install(BungeeCord)
    }
}

dependencies {
    compileOnly(project(":api"))
    // IOC 注解(仅编译期);运行期由 plugin 的 autoTakeover 统一扫描纳管
    compileOnly("top.wcpe.taboolib.ioc:taboolib-ioc-annotation:1.2.0-SNAPSHOT")
    // core:依赖 ProbeRegistry / ProbeLogger 完成服务发现自注册
    compileOnly(project(":project:core"))
    // BungeeCord 服务端 API(ProxyServer / ServerInfo 等),代理端采集 totalOnline 与各子服在线
    compileOnly("net.md-5:bungeecord-api:1.20-R0.2")
}
