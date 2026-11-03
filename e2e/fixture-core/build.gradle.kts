// E2E 夹具的平台无关核心：最小 RFC 6455 服务端、托管模式断言、结果文件写出。
//
// 抽成独立模块的原因：同一套“接受一条探针连接 + 握手 + 断言 + 交卷”的能力，
// Bukkit / Bungee / Velocity 三个 harness 插件都要用；各写一份会让协议细节漂移。
// 本模块不得依赖任何服务端 API（Paper / BungeeCord / Velocity 一律不许出现）。
plugins {
    id("serverprobe.e2e-harness")
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
}

java {
    // 与 Kotlin 的 jvmTarget 1.8 对齐：夹具会被打入 Bukkit harness，而矩阵要在 1.8.8 服务器上加载。
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}
