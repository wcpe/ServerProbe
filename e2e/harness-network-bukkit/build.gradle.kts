plugins {
    id("serverprobe.e2e-harness")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.20.1-R0.1-SNAPSHOT")
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
