plugins {
    id("serverprobe.e2e-harness")
}

dependencies {
    compileOnly("ink.ptms.core:v12004:12004:universal")
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

sourceSets.main {
    java.srcDir("../mcp-harness-common/src/main/java")
}

tasks.jar {
    archiveBaseName.set("mc-testkit-mcp-bukkit-harness")
}

// 服务端 API SNAPSHOT 须在 JVM 17 上解析；目标属性保持 17，仅让编译产物落到 Java 8 字节码。
configurations.compileClasspath {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
    }
}
