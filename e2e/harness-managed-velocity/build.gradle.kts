// 代理端托管 E2E：Velocity 侧的夹具插件。
// 与 Bungee 侧同一套夹具核心，差别仅在于探针数据目录大小写（Velocity 由插件 id 派生，小写）。
plugins {
    id("serverprobe.e2e-harness")
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    // 运行期由真实 Velocity 提供，禁止打入插件 jar。
    compileOnly("com.velocitypowered:velocity-api:3.1.1")
    annotationProcessor("com.velocitypowered:velocity-api:3.1.1")
    implementation(project(":e2e:fixture-core"))
    implementation(kotlin("stdlib"))
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

tasks.jar {
    archiveBaseName.set("mc-testkit-managed-velocity-harness")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
