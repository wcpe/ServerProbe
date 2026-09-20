import io.izzel.taboolib.gradle.*

plugins {
    id("serverprobe.base")
}

taboolib {
    env {
        install(Bukkit)
        install(BukkitUtil)
        install(I18n)
    }
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":project:core"))
    compileOnly("top.wcpe.taboolib.ioc:taboolib-ioc-annotation:1.2.0-SNAPSHOT")
    compileOnly("ink.ptms.core:v12004:12004:universal")
    compileOnly("top.wcpe.mc.plugin.allininventorysync:allininventorysync-api:2.0.0")
    testImplementation(project(":project:core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    // JUnit 5.12+ 要求测试运行期显式具备 Platform launcher，否则 Gradle 报
    // "Could not start Gradle Test Executor / Failed to load JUnit Platform"。
    // 版本由 junit-jupiter 传递的 junit-bom 管理，无需在此写死。
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("top.wcpe.mc.plugin.allininventorysync:allininventorysync-api:2.0.0")
}

tasks.test {
    useJUnitPlatform()
}
