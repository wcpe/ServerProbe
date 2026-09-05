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
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("top.wcpe.mc.plugin.allininventorysync:allininventorysync-api:2.0.0")
}

tasks.test {
    useJUnitPlatform()
}
