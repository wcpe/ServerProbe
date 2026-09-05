plugins {
    id("serverprobe.e2e-harness")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.1.1")
    annotationProcessor("com.velocitypowered:velocity-api:3.1.1")
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

tasks.jar {
    archiveBaseName.set("mc-testkit-velocity-matrix-harness")
}
