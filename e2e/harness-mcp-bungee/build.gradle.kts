plugins {
    id("serverprobe.e2e-harness")
}

dependencies {
    compileOnly("net.md-5:bungeecord-api:1.20-R0.2")
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

sourceSets.main {
    java.srcDir("../mcp-harness-common/src/main/java")
}

tasks.jar {
    archiveBaseName.set("mc-testkit-mcp-bungee-harness")
}
