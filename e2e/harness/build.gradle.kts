// ServerProbe 的 mc-testkit E2E harness；Paper API 仅在真实服务端提供。
// build script 里的 `java` 会解析成 Java 扩展，故 ZipFile 必须走顶部 import 而非全限定名。
import java.util.zip.ZipFile

plugins {
    id("serverprobe.e2e-harness")
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    // 运行期由真实 Paper 服务端提供，禁止打入插件 jar。
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    // 被测插件的对外契约：**必须 compileOnly**。
    // 若用 implementation，下面的 jar 任务会把它打进 harness jar，于是 harness 拥有自己的一份
    // ServerProbeAPI（静态字段 services 与探针那份不是同一实例）→ 注册永不生效且不报错。
    compileOnly(project(":api"))
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
    // harness 自带 Kotlin 标准库；Paper API 与 ServerProbe 契约（:api）保持 compileOnly。
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

// 防回归：harness jar 内**不得**含 ServerProbe 的 api 类。
// 一旦有人把 :api 误改为 implementation，上面的 from(runtimeClasspath) 会把它打进来，
// 于是 harness 持有独立的一份 ServerProbeAPI，静态字段与探针那份分裂 → 注册静默失效。
// 注意：此处不能用全限定 java.util.zip.ZipFile——build script 里的 `java` 会解析成 Java 扩展。
val verifyNoProbeApiInHarness = tasks.register("verifyNoProbeApiInHarness") {
    description = "校验 harness jar 未打包 ServerProbe 对外契约（否则门面静态状态会分裂）"
    group = "verification"
    dependsOn(tasks.named("jar"))
    doLast {
        val artifact = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val forbidden = ZipFile(artifact).use { archive ->
            archive.entries().asSequence().map { entry -> entry.name }
                .filter { name -> name.startsWith("top/wcpe/mc/plugin/serverprobe/api/") }
                .toList()
        }
        check(forbidden.isEmpty()) {
            "harness jar 不得包含 ServerProbe 契约类（:api 必须 compileOnly）：${forbidden.take(5)}"
        }
    }
}

tasks.named("check") { dependsOn(verifyNoProbeApiInHarness) }
