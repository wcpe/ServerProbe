import io.izzel.taboolib.gradle.*

// 通用核心模块(Java 8,平台无关):采集编排、JMX 采集、聚合、本地文件存储
taboolib {
    env {
        install(I18n)
    }
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(fileTree("libs"))
    // IOC 注解(仅编译期);运行期由 plugin 的 autoTakeover 统一扫描纳管(A 策略)
    compileOnly("top.wcpe.taboolib.ioc:taboolib-ioc-annotation:1.2.0-SNAPSHOT")

    testImplementation(project(":api"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}
