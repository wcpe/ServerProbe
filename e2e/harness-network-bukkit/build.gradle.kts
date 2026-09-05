plugins {
    id("serverprobe.e2e-harness")
}

dependencies {
    compileOnly("ink.ptms.core:v12004:12004:universal")
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))
// 矩阵需在 Java 8 服务器上加载；编译器用 17，字节码保持 Java 8。
tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

sourceSets.main {
    java.srcDir("../network-harness-common/src/main/java")
}

tasks.jar {
    archiveBaseName.set("mc-testkit-network-bukkit-harness")
}

// 服务端 API SNAPSHOT 须在 JVM 17 上解析；目标属性保持 17，仅让编译产物落到 Java 8 字节码。
configurations.compileClasspath {
    attributes {
        attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
    }
}
