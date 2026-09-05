import io.izzel.taboolib.gradle.*

plugins {
    id("serverprobe.base")
}

taboolib {
    env {
        install(Basic)
        install(I18n)
    }
}

dependencies {
    compileOnly(project(":project:core"))
    compileOnly("top.wcpe.taboolib.ioc:taboolib-ioc-annotation:1.2.0-SNAPSHOT")

    testImplementation(project(":project:core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}
