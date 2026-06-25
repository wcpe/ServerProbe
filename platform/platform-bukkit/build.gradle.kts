import io.izzel.taboolib.gradle.*

// Bukkit/Paper/Folia 平台采集器模块(Java 8)。Bukkit 专属 env 与 NMS 仅在此模块声明,实现平台隔离。
taboolib {
    env {
        install(Bukkit)
        install(BukkitUtil)
        install(I18n)
    }
}

dependencies {
    compileOnly(project(":api"))
    // IOC 注解(仅编译期);运行期由 plugin 的 autoTakeover 统一扫描纳管
    compileOnly("top.wcpe.taboolib.ioc:taboolib-ioc-annotation:1.2.0-SNAPSHOT")
    // core:依赖 ProbeRegistry / ProbeLogger 完成服务发现自注册
    compileOnly(project(":project:core"))
    // NMS / Bukkit API:用于 P5 ServerTickSampler 的 nmsProxy 读取 recentTps、以及世界/实体采集
    compileOnly("ink.ptms.core:v12004:12004:mapped")
    compileOnly("ink.ptms.core:v12004:12004:universal")
    // 业务对接(JBIS,ADR-0015):经济 Provider 编译期依赖 MultiCurrencyEconomy 公开 api;
    // 运行期由目标服务端的 MultiCurrencyEconomy 插件提供(compileOnly,经 ServicesManager 发现 + 降级)。
    compileOnly("top.wcpe.mc.plugin.multicurrencyeconomy:multicurrencyeconomy-api:1.2.0")
    // 业务对接(JBIS,ADR-0016):背包 Provider / 追踪事件监听器编译期依赖 AllinInventorySync 公开 api(自包含单制品,
    // 含 api.model 全部对外 DTO);运行期由目标服务端的 AllinInventorySync 插件提供(compileOnly,经 Provider 发现 + 降级)。
    compileOnly("top.wcpe.mc.plugin.allininventorysync:allininventorysync-api:1.2.0")

    // MsptHistogram 等纯逻辑单测(不依赖 Bukkit/NMS)
    testImplementation(project(":api"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    // EconomyEnvelope 纯逻辑单测:需 core(BridgeCommandResult 信封)与 mce-api(IdempotencyMode 等类型)在测试 classpath
    testImplementation(project(":project:core"))
    testImplementation("top.wcpe.mc.plugin.multicurrencyeconomy:multicurrencyeconomy-api:1.2.0")
    // InventoryEnvelope / InventoryEventEnvelope 纯逻辑单测:需 AllinInventorySync api 的 DTO 类型(ItemDto/
    // InventoryViewDto/WriteResult/BasicAttrsDto,均 api.model)在测试 classpath
    testImplementation("top.wcpe.mc.plugin.allininventorysync:allininventorysync-api:1.2.0")
}

tasks.test {
    useJUnitPlatform()
}
