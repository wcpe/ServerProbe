/**
 * ServerProbe 全部叶子子项目的公共构建约定。
 *
 * 从 root build.gradle.kts 的 subprojects{} 块抽取：
 * - Java + Kotlin(JVM) + TabooLib + TabooLib IoC + Detekt 五件套统一应用（脚本体 apply，
 *   版本由 build-logic 的 implementation classpath 固定，消费方 plugins 块不再声明版本）
 * - TabooLib 运行库版本与 wcpe 托管镜像
 * - Java 8 toolchain / Kotlin JVM_1_8（发行面向旧服务器）
 * - Detekt 规则与基线（存量问题由各模块 detekt-baseline.xml 冻结）
 *
 * 注意：插件走脚本体 apply() 后不生成类型化访问器（compileOnly/java{} 等），
 * 因此统一使用 configure<T>/the<T>/add(...) 显式 API。
 *
 * 容器项目（:platform、:project）不应用本插件，避免产出空 jar。
 */

import io.izzel.taboolib.gradle.*
import org.gradle.api.plugins.JavaPluginExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

apply(plugin = "java")
apply(plugin = "org.jetbrains.kotlin.jvm")
apply(plugin = "io.izzel.taboolib")
apply(plugin = "top.wcpe.taboolib.ioc")
apply(plugin = "io.gitlab.arturbosch.detekt")

configure<TabooLibExtension> {
    subproject = true
    env {
        // 仅装公共 Basic;平台/功能 install 由各子模块在自身 build.gradle.kts 按需追加,避免平台污染
        install(Basic)
        // 运行期模块从 wcpe 托管的 tabooproject release 镜像解析，避免 tabooproject 上对应版本缺失导致插件无法加载。
        repoTabooLib = "https://maven.wcpe.top/repository/maven-tabooproject-release"
    }
    version { taboolib = "6.3.0-wcpe.1" }
}

// 依赖仓库统一在 settings.gradle.kts 的 dependencyResolutionManagement(PREFER_SETTINGS)集中声明,
// 以排除 io.izzel.taboolib 插件硬编码注入的失效镜像 repo.spongepowered.org(详见 settings 注释)。
// 此处不再各工程声明 repositories(声明也会被 PREFER_SETTINGS 忽略)。

dependencies {
    add("compileOnly", "org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
    // NMS(ink.ptms.core)下沉到需要 Bukkit/NMS API 的模块(platform-bukkit、plugin)按需引入
}

// Java 编码
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

configure<JavaPluginExtension> {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

// detekt 静态检查:在默认规则集之上微调(见 config/detekt/detekt.yml);
// 存量问题由各模块 detekt-baseline.xml 冻结,仅对新增问题告警(detekt 已挂入 check/build)
configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
}
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "1.8"
}
tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = "1.8"
}
