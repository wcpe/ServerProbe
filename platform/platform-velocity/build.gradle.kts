import io.izzel.taboolib.gradle.*
import java.io.File
import org.gradle.api.JavaVersion
import org.gradle.api.GradleException
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("serverprobe.base")
}

val velocityApiVersion = providers.gradleProperty("velocityApiVersion").orElse("3.1.1")
val javaExecutableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    "java.exe"
} else {
    "java"
}
val javacExecutableName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    "javac.exe"
} else {
    "javac"
}

taboolib {
    env {
        install(Velocity)
    }
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(project(":project:core"))
    compileOnly("top.wcpe.taboolib.ioc:taboolib-ioc-annotation:1.2.0-SNAPSHOT")
    compileOnly("com.velocitypowered:velocity-api:${velocityApiVersion.get()}")

    testImplementation(project(":api"))
    testImplementation(project(":project:core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("com.velocitypowered:velocity-api:${velocityApiVersion.get()}")
}

tasks.test {
    useJUnitPlatform()
}

/** 验证显式提供的本机 Java 25 目录，绝不触发工具链自动下载。 */
fun localJava25Home(): File {
    val configured = providers.gradleProperty("velocityApi4JavaHome").orNull
        ?: providers.environmentVariable("VELOCITY_API4_JAVA_HOME").orNull
        ?: throw GradleException(
            "Velocity API 4.1.0 兼容编译需要本机 Java 25。请设置 -PvelocityApi4JavaHome=<Java25目录> " +
                "或 VELOCITY_API4_JAVA_HOME；本任务不会下载或安装 JDK。",
        )
    val home = file(configured)
    val java = File(home, "bin/$javaExecutableName")
    val javac = File(home, "bin/$javacExecutableName")
    val release = File(home, "release")
    val version = release.takeIf { it.isFile }
        ?.readLines()
        ?.firstOrNull { it.startsWith("JAVA_VERSION=") }
        ?.substringAfter('=')
        ?.trim('"')
    val isJava25 = version == "25" || version?.startsWith("25.") == true
    if (!java.isFile || !javac.isFile || !isJava25) {
        throw GradleException(
            "Velocity API 4.1.0 兼容编译要求有效的本机 Java 25 JDK；当前配置目录无效或版本不是 25：${home.absolutePath}。",
        )
    }
    return home
}

/** API 4 兼容编译仅让 Velocity Kotlin 源码使用本机 Java 25，产物目标仍由根工程固定为 Java 8。 */
if (providers.gradleProperty("velocityApiCompilerJava").orNull == "25") {
    configurations.named("compileClasspath") {
        attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
    tasks.withType<KotlinCompile>().configureEach {
        kotlinJavaToolchain.jdk.use(localJava25Home(), JavaVersion.VERSION_25)
    }
}

/** 运行 API 4 兼容编译前的中文 Java 25 前置门。 */
val verifyVelocityApi4Java25 by tasks.registering {
    group = "verification"
    description = "验证 Velocity API 4.1.0 兼容编译所需的本机 Java 25"
    doLast { localJava25Home() }
}

/** 使用 API 3.1.1 对当前 Velocity 源码做独立兼容编译。 */
val compileVelocityApi31Compatibility by tasks.registering(Exec::class) {
    group = "verification"
    description = "使用 Velocity API 3.1.1 独立编译 platform-velocity"
    workingDir(rootProject.projectDir)
    outputs.upToDateWhen { false }
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        commandLine("cmd", "/c", "gradlew.bat", ":platform:platform-velocity:compileKotlin", "-PvelocityApiVersion=3.1.1", "--no-daemon", "--console=plain")
    } else {
        commandLine("./gradlew", ":platform:platform-velocity:compileKotlin", "-PvelocityApiVersion=3.1.1", "--no-daemon", "--console=plain")
    }
}

/** 使用 Java 25 与 API 4.1.0 对当前 Velocity 源码做独立兼容编译。 */
val compileVelocityApi4Compatibility by tasks.registering(Exec::class) {
    group = "verification"
    description = "使用本机 Java 25 与 Velocity API 4.1.0 独立编译 platform-velocity"
    dependsOn(verifyVelocityApi4Java25)
    mustRunAfter(compileVelocityApi31Compatibility)
    workingDir(rootProject.projectDir)
    outputs.upToDateWhen { false }
    doFirst {
        val javaHome = localJava25Home().absolutePath
        val properties = listOf(
            "-PvelocityApiVersion=4.1.0",
            "-PvelocityApiCompilerJava=25",
            "-PvelocityApi4JavaHome=$javaHome",
            "-PvelocityApi4RepositoryFallback=true",
        )
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            commandLine("cmd", "/c", "gradlew.bat", *properties.toTypedArray(), ":platform:platform-velocity:compileKotlin", "--no-daemon", "--console=plain")
        } else {
            commandLine("./gradlew", *properties.toTypedArray(), ":platform:platform-velocity:compileKotlin", "--no-daemon", "--console=plain")
        }
    }
}

/** 聚合两套 Velocity API 兼容编译门，供本地和 CI 显式调用。 */
tasks.register("verifyVelocityApiCompatibility") {
    group = "verification"
    description = "验证 Velocity API 3.1.1 与 4.1.0 的独立兼容编译"
    dependsOn(compileVelocityApi31Compatibility, compileVelocityApi4Compatibility)
}
