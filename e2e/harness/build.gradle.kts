// ServerProbe 的 mc-testkit E2E harness；Paper API 仅在真实服务端提供。
plugins {
    id("serverprobe.e2e-harness")
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    // 运行期由真实 Paper 服务端提供，禁止打入插件 jar。
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    // 仅编译期链接被测插件公开 API；运行期由同一测试服内的 ServerProbe 提供。
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
}

java {
    // 与 Kotlin 的 jvmTarget 1.8 对齐，避免 compileJava/compileKotlin 目标不一致。
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// paper-api 1.20.1 须在 JVM 17 上解析；目标属性保持 17，仅让编译产物落到 Java 8 字节码。
configurations.compileClasspath {
    attributes {
        attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
    }
}

kotlin {
    // 编译器可用新版 JDK，但矩阵需在 Java 8 服务器（1.8.8/1.12.2/1.16.5）上加载，必须产出 Java 8 字节码。
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

tasks.jar {
    archiveBaseName.set("mc-testkit-e2e-harness")
    // harness 自带 Kotlin 标准库；Paper API 保持 compileOnly。
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
