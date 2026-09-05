plugins {
    id("serverprobe.e2e-harness")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.20.1-R0.1-SNAPSHOT")
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

sourceSets.main {
    java.srcDir("../mcp-harness-common/src/main/java")
}

tasks.jar {
    archiveBaseName.set("mc-testkit-mcp-bukkit-harness")
}
