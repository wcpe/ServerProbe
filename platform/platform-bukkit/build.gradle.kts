import io.izzel.taboolib.gradle.*

plugins {
    id("serverprobe.base")
}

// Bukkit/Paper/Folia 平台采集器模块(Java 8)。Bukkit 专属 env 与 NMS 仅在此模块声明,实现平台隔离。
taboolib {
    env {
        install(Bukkit)
        install(BukkitUtil)
        install(I18n)
        install(Incision)
    }
}

dependencies {
    compileOnly(project(":api"))
    // IOC 注解(仅编译期);运行期由 plugin 的 autoTakeover 统一扫描纳管
    compileOnly("top.wcpe.taboolib.ioc:taboolib-ioc-annotation:1.2.0-SNAPSHOT")
    // 启动画像兜底装配需容错读取 IOC 容器(BeanContainer);仅编译期,运行期由 plugin 合并后的重定位容器提供。
    compileOnly("top.wcpe.taboolib.ioc:taboolib-ioc:1.2.0-SNAPSHOT")
    // core:依赖 ProbeRegistry / ProbeLogger 完成服务发现自注册
    compileOnly(project(":project:core"))
    // NMS / Bukkit API:用于 P5 ServerTickSampler 的 nmsProxy 读取 recentTps、以及世界/实体采集
    compileOnly("ink.ptms.core:v12004:12004:mapped")
    compileOnly("ink.ptms.core:v12004:12004:universal")
    // MsptHistogram 等纯逻辑单测(不依赖 Bukkit/NMS)
    testImplementation(project(":api"))
    // Folia region 与 Incision 单测直接使用 core 的纯逻辑辅助类。
    testImplementation(project(":project:core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}
