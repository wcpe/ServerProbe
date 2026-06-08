import io.izzel.taboolib.gradle.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    id("io.izzel.taboolib") version "2.0.28" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    id("top.wcpe.taboolib.ioc") version "0.0.5" apply false
}

subprojects {
    // 跳过纯容器项目(:platform、:project):它们无源码,不应应用插件,避免产出空 jar 与无意义的 IOC 诊断
    if (childProjects.isNotEmpty()) return@subprojects

    apply<JavaPlugin>()
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.izzel.taboolib")
    apply(plugin = "top.wcpe.taboolib.ioc")

    configure<TabooLibExtension> {
        subproject = true
        env {
            // 仅装公共 Basic;平台/功能 install 由各子模块在自身 build.gradle.kts 按需追加,避免平台污染
            install(Basic)
        }
        version { taboolib = "6.3.0-afd75a7" }
    }

    repositories {
        mavenLocal()
        maven("https://maven.wcpe.top/repository/maven-public/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://jitpack.io")
        mavenCentral()
    }

    dependencies {
        compileOnly(kotlin("stdlib"))
        // NMS(ink.ptms.core)下沉到需要 Bukkit/NMS API 的模块(platform-bukkit、plugin)按需引入
    }


    // Java 编码
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }

}
