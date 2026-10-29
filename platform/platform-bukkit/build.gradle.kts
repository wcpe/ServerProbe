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
    // 平台事件监听的测试要构造真实 Bukkit 事件对象交给监听器（不用反射碰私有字段）。
    testImplementation("ink.ptms.core:v12004:12004:universal")
    // universal 里的 org.bukkit 类在初始化时依赖 guava（NamespacedKey/Preconditions），
    // compileOnly 不进测试运行期，因此测试侧显式补齐。
    testImplementation("com.google.guava:guava:33.4.0-jre")
    // Folia region 与 Incision 单测直接使用 core 的纯逻辑辅助类。
    testImplementation(project(":project:core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    // JUnit 5.12+ 要求测试运行期显式具备 Platform launcher，否则 Gradle 报
    // "Could not start Gradle Test Executor / Failed to load JUnit Platform"。
    // 版本由 junit-jupiter 传递的 junit-bom 管理，无需在此写死。
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // 测试跑在 JDK 21 上（发行物本身仍是 Java 8 字节码，由 bytecode-gate 钉住）。
    // 原因：平台事件监听的测试要构造真实的 Bukkit 事件对象（PlayerJoinEvent 等）——
    //
    // 这是验证“事件里的那个玩家真的被交给取证生命周期”的唯一途径，无需反射摸私有字段。
    // 而 JDK 8 的 `Proxy.newProxyInstance` 会**强制初始化**被代理的接口，
    // 一初始化 `org.bukkit.entity.Player` 就会跑到 `Bukkit.Registry` 的静态块，
    // 那里需要真实的服务器实例（无服务器时 `Bukkit.getRegistry` 抛 NPE）；
    // JDK 9+ 不再初始化接口，代理才能作为纯数据载体使用。
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
}
