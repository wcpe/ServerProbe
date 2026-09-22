import io.izzel.taboolib.gradle.*

plugins {
    id("serverprobe.base")
}

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
    testImplementation("net.md-5:bungeecord-api:1.20-R0.2")
    // 测试需解析 LogPathProvider 父接口(FR-28 日志路径适配器单测),与 velocity 同款
    testImplementation(project(":project:core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    // JUnit 5.12+ 要求测试运行期显式具备 Platform launcher，否则 Gradle 报
    // "Could not start Gradle Test Executor / Failed to load JUnit Platform"。
    // 版本由 junit-jupiter 传递的 junit-bom 管理，无需在此写死。
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
