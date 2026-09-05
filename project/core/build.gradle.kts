import io.izzel.taboolib.gradle.*

plugins {
    id("serverprobe.base")
}

// 通用核心模块(Java 8,平台无关):采集编排、JMX 采集、聚合、本地文件存储
taboolib {
    env {
        install(I18n)
    }
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(fileTree("libs"))
    // SQLite 驱动由 plugin 模块随发行 Jar 内置；core 仅保留编译期类型可见性。
    compileOnly("org.xerial:sqlite-jdbc:3.53.2.1")
    // IOC 注解(仅编译期);运行期由 plugin 的 autoTakeover 统一扫描纳管(A 策略)
    compileOnly("top.wcpe.taboolib.ioc:taboolib-ioc-annotation:1.2.0-SNAPSHOT")

    testImplementation(project(":api"))
    // 注解为 RUNTIME retention；测试反射校验字段 @Inject 时必须能解析注解类型（compileOnly 不进测试运行时）。
    testImplementation("top.wcpe.taboolib.ioc:taboolib-ioc-annotation:1.2.0-SNAPSHOT")
    // 仅用于验证反射 Netty 处理器的真实透传行为，不参与发行包。
    testImplementation("io.netty:netty-transport:4.2.17.Final")
    testImplementation("org.xerial:sqlite-jdbc:3.53.2.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}
