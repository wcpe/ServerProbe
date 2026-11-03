// 桥链路压测桩：外置插件，不改 ServerProbe 内部实现。
//
// 为什么外置：压测器如果要改被测物本身，就失去验收意义（容易"把尺子做成弹弓"）。
// 本模块只依赖 ServerProbe 的**公开 API**（api 模块，compileOnly），
// 以第三方插件的身份向桥注流量，测的是真实端到端链路。
plugins {
    id("serverprobe.e2e-harness")
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    // 运行期由真实 Paper 服务端提供，禁止打入插件 jar。
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    // 被测插件的对外契约：**必须 compileOnly**。
    // 若用 implementation，harness jar 会带上自己的一份 ServerProbeAPI，
    // 静态字段与探针那份不是同一实例 → 通过它发的事件永远到不了真实的桥。
    compileOnly(project(":api"))
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// paper-api 1.20.1 需在 JVM 17 上解析；目标属性保持 17，仅让产物落到 Java 8 字节码。
configurations.compileClasspath {
    attributes {
        attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

tasks.jar {
    archiveBaseName.set("mc-testkit-bridge-load-harness")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    // 只打包 Kotlin 标准库；Paper API 与 ServerProbe 契约保持 compileOnly。
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

tasks.test {
    useJUnitPlatform()
}
