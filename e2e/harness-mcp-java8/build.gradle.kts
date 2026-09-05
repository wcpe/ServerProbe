plugins {
    id("serverprobe.e2e-harness")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.16.5-R0.1-SNAPSHOT")
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
