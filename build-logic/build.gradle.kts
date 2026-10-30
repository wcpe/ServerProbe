plugins {
    `kotlin-dsl`
}

group = "top.wcpe.mc.plugin.serverprobe.buildlogic"

dependencies {
    // 外部插件以 marker 坐标引入（group = 插件 id，artifact = 插件 id + ".gradle.plugin"），
    // 使 precompiled script plugins 可以在编译期引用其类型与 DSL。
    implementation("org.jetbrains.kotlin.jvm:org.jetbrains.kotlin.jvm.gradle.plugin:2.1.0")
    // 2.0.38-wcpe.3 起修复了「运行期模块（taboolib/module、taboolib/library）被整包重定位」
    // 导致动态下载的依赖永远匹配不上的问题（RelocateRemapper.skipRuntimeModulePrefixes）。
    // 未升到它之前，@Surgeon/@Lead/@Trail 会被改写到插件命名空间而运行期不存在，
    // Incision 全程静默失效（incisionEnabled 永远 false）。
    implementation("io.izzel.taboolib:io.izzel.taboolib.gradle.plugin:2.0.38-wcpe.3")
    implementation("top.wcpe.taboolib.ioc:top.wcpe.taboolib.ioc.gradle.plugin:0.0.6")
    implementation("io.gitlab.arturbosch.detekt:io.gitlab.arturbosch.detekt.gradle.plugin:1.23.7")
    // 0.10.0 起 `ServerLauncher` 用前缀 `io.papermc.paperclip.` 识别 paperclip 入口（旧版是精确匹配
    // `...paperclip.Main`）。Paper 1.8.8–1.17.1 的主类是 `...paperclip.Paperclip`，精确匹配识别不到，
    // 会被误当成 thin jar 走 `-cp` 启动，旧版 paperclip 在 `-cp` 下拿不到 Instrumentation API，
    // 报“Unable to retrieve Instrumentation API”后退出，矩阵最老版本场景永远跑不通。
    implementation("top.wcpe.mc-testkit:top.wcpe.mc-testkit.gradle.plugin:0.16.0")

    // Gradle 官方推荐的构建逻辑测试方式：TestKit 在真实 Gradle 运行中执行任务图。
    // 用它替代原先“读 build.gradle.kts 文本 + contains()”的脆弱断言——
    // 那些断言只能证明“文件里写了这行字”，而 TestKit 验证的是“任务真的被创建、依赖真的挂上”。
    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // TestKit 会起真实 Gradle 进程，给宽一点的超时与独立的测试目录。
    maxHeapSize = "512m"
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// build-logic 自身的编译目标：21（mc-testkit 等插件构件以 Java 21 目标发布，consumer 必须 ≥21
// 才能 variant 匹配；产物运行于 Gradle daemon 的 JDK 21，兼容）
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
