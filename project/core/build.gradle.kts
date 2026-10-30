import io.izzel.taboolib.gradle.*

plugins {
    id("serverprobe.base")
}

// 通用核心模块(Java 8,平台无关):采集编排、JMX 采集、聚合、本地文件存储
taboolib {
    env {
        install(I18n)
    }
}

dependencies {
    compileOnly(project(":api"))
    compileOnly(fileTree("libs"))
    // SQLite 驱动由 plugin 模块随发行 Jar 内置；core 仅保留编译期类型可见性。
    compileOnly("org.xerial:sqlite-jdbc:${property("sqlite.jdbc.version")}")
    // IOC 注解(仅编译期);运行期由 plugin 的 autoTakeover 统一扫描纳管(A 策略)
    compileOnly("top.wcpe.taboolib.ioc:taboolib-ioc-annotation:1.2.0-SNAPSHOT")

    testImplementation(project(":api"))
    // 注解为 RUNTIME retention；测试反射校验字段 @Inject 时必须能解析注解类型（compileOnly 不进测试运行时）。
    testImplementation("top.wcpe.taboolib.ioc:taboolib-ioc-annotation:1.2.0-SNAPSHOT")
    // 仅用于验证反射 Netty 处理器的真实透传行为，不参与发行包。
    testImplementation("io.netty:netty-transport:4.2.18.Final")
    testImplementation("org.xerial:sqlite-jdbc:${property("sqlite.jdbc.version")}")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    // JUnit 5.12+ 要求测试运行期显式具备 Platform launcher，否则 Gradle 报
    // "Could not start Gradle Test Executor / Failed to load JUnit Platform"。
    // 版本由 junit-jupiter 传递的 junit-bom 管理，无需在此写死。
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

/**
 * 桥热路径微基准（手动运行，**不挂 check**）。
 *
 * 为什么不挂 check：这里测的是墙钟耗时与分配字节，在共享 CI runner 上必然抖动；
 * 本仓既有约定是"不用墙钟阈值断言性能"（见 FrameSenderTest 类注释）。
 * 基准的用途是"改动前后对比"，而不是"设一个阈值把 PR 卡住"。
 *
 * 必须用 JDK 21 运行：JDK 8 没有 `ThreadMXBean.getThreadAllocatedBytes` 之外的
 * 部分设施，且基准本身不面向旧服务器（发行物仍编译为 Java 8 字节码）。
 */
tasks.register<JavaExec>("benchmarkBridge") {
    group = "verification"
    description = "运行桥热路径微基准与并发压测（输出仅供人工对比，不参与 CI 判定）"
    dependsOn(tasks.named("testClasses"))
    // 本仓的 serverprobe.base 是脚本体 apply 的，不生成 sourceSets/java 的类型化访问器，
    // 连 JavaPluginExtension 在任务配置阶段都取不到；因此直接用标准配置名，绕开扩展查找。
    // `testClasses` 是生命周期任务、没有输出文件，直接 files() 它不会带上测试类目录；
    // 因此显式取主类与测试类的编译输出目录 + 测试运行期依赖。
    val mainClassesDir = layout.buildDirectory.dir("classes/kotlin/main")
    val testClassesDir = layout.buildDirectory.dir("classes/kotlin/test")
    classpath = files(mainClassesDir, testClassesDir) +
        configurations.getByName("testRuntimeClasspath")
    mainClass.set("top.wcpe.mc.plugin.serverprobe.core.benchmark.BenchmarkMainKt")
    // 参数透传：-PbenchArgs="8 5" 表示 8 线程压测 5 秒。
    (project.findProperty("benchArgs") as String?)
        ?.split(' ')
        ?.filter { it.isNotBlank() }
        ?.let { args(it) }
    jvmArgs("-Xmx1g")
    // 基准必须跑在新 JDK 上：分配统计（ThreadMXBean.getThreadAllocatedBytes）在 JDK 8 上
    // 不可靠，且 JavaExec 默认继承项目的 Java 8 toolchain（发行物面向旧服务器）。
    // 这里显式指定 17+，与"发行物编译为 Java 8 字节码"的承诺互不冲突——只影响基准任务。
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        },
    )
}
