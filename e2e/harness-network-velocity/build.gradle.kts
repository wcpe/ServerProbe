plugins {
    id("serverprobe.e2e-harness")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.1.1")
    annotationProcessor("com.velocitypowered:velocity-api:3.1.1")
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

sourceSets.main {
    java.srcDir("../network-harness-common/src/main/java")
}

tasks.jar {
    archiveBaseName.set("mc-testkit-network-velocity-harness")
}
