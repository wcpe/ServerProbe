import io.izzel.taboolib.gradle.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    id("io.izzel.taboolib") version "2.0.28" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
    id("top.wcpe.taboolib.ioc") version "0.0.6" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
}

subprojects {
    // 跳过纯容器项目(:platform、:project):它们无源码,不应应用插件,避免产出空 jar 与无意义的 IOC 诊断
    if (childProjects.isNotEmpty()) return@subprojects

    apply<JavaPlugin>()
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.izzel.taboolib")
    apply(plugin = "top.wcpe.taboolib.ioc")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    configure<TabooLibExtension> {
        subproject = true
        env {
            // 仅装公共 Basic;平台/功能 install 由各子模块在自身 build.gradle.kts 按需追加,避免平台污染
            install(Basic)
        }
        version { taboolib = "6.3.0-afd75a7" }
    }

    // 依赖仓库统一在 settings.gradle.kts 的 dependencyResolutionManagement(PREFER_SETTINGS)集中声明,
    // 以排除 io.izzel.taboolib 插件硬编码注入的失效镜像 repo.spongepowered.org(详见 settings 注释)。
    // 此处不再各工程声明 repositories(声明也会被 PREFER_SETTINGS 忽略)。

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

    // detekt 静态检查:在默认规则集之上微调(见 config/detekt/detekt.yml);
    // 存量问题由各模块 detekt-baseline.xml 冻结,仅对新增问题告警(detekt 已挂入 check/build)
    configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        parallel = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        baseline = file("detekt-baseline.xml")
    }
    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "1.8"
    }
    tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
        jvmTarget = "1.8"
    }

}
