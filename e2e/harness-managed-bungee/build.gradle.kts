// 代理端托管 E2E：BungeeCord 侧的夹具插件。
// 它在代理进程内扮“模拟控制面”（最小 WS 服务端），探针反向连上来后逐项验收托管链路。
plugins {
    id("serverprobe.e2e-harness")
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    // 运行期由真实 BungeeCord 提供，禁止打入插件 jar。
    compileOnly("net.md-5:bungeecord-api:1.20-R0.2")
    // 夹具核心与 Kotlin 运行库必须**打入** jar：代理只加载插件 jar，没有别处提供它们。
    implementation(project(":e2e:fixture-core"))
    implementation(kotlin("stdlib"))
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

tasks.jar {
    archiveBaseName.set("mc-testkit-managed-bungee-harness")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
