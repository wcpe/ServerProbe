plugins {
    `kotlin-dsl`
}

group = "top.wcpe.mc.plugin.serverprobe.buildlogic"

dependencies {
    // 外部插件以 marker 坐标引入（group = 插件 id，artifact = 插件 id + ".gradle.plugin"），
    // 使 precompiled script plugins 可以在编译期引用其类型与 DSL。
    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.1.0")
    implementation("io.izzel.taboolib:io.izzel.taboolib.gradle.plugin:2.0.37-fix")
    implementation("top.wcpe.taboolib.ioc:top.wcpe.taboolib.ioc.gradle.plugin:0.0.6")
    implementation("io.gitlab.arturbosch.detekt:io.gitlab.arturbosch.detekt.gradle.plugin:1.23.7")
    implementation("top.wcpe.mc-testkit:top.wcpe.mc-testkit.gradle.plugin:0.8.0")
}

// build-logic 自身的编译目标：21（mc-testkit 等插件构件以 Java 21 目标发布，consumer 必须 ≥21
// 才能 variant 匹配；产物运行于 Gradle daemon 的 JDK 21，兼容）
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
