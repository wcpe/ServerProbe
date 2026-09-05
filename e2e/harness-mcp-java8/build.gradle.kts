plugins {
    id("serverprobe.e2e-harness")
}

dependencies {
    compileOnly("ink.ptms.core:v12004:12004:universal")
}

java {
    // Java 8 服务器（Paper 1.16.5）直连场景：继承 root 默认 toolchain 8，字节码同为 Java 8。
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

sourceSets.main {
    java.srcDir("../mcp-harness-common/src/main/java")
}

tasks.jar {
    archiveBaseName.set("mc-testkit-mcp-java8-harness")
}

// 服务端 API SNAPSHOT 须在 JVM 17 上解析；目标属性保持 17，仅让编译产物落到 Java 8 字节码。
configurations.compileClasspath {
    attributes {
        attribute(org.gradle.api.attributes.java.TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
    }
}
