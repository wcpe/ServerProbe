/**
 * E2E 验收桩（harness）公共约定。
 *
 * 收编自 9 个原独立 Gradle 工程（e2e/harness 与 harness-*）的共性：
 * - Java 编译 UTF-8
 * - group/version 统一为 e2e 坐标
 * - 独立 settings 已删除，仓库走 root dependencyResolutionManagement
 *
 * 各 harness 的特有配置保留在其自身脚本：toolchain 覆盖（fr14-legacy=Java 8、其余 17）、
 * jar archiveBaseName（保持 mc-testkit-*-harness 原产物名，mcTestkit 路径引用依赖它）、
 * Kotlin 编译选项（harness 主工程）、paper-api/spigot-api 编译依赖。
 */

plugins {
    java
}

group = "top.wcpe.mc.plugin.serverprobe.e2e"
version = "1.0.0-SNAPSHOT"

// 被测发行 jar 的统一坐标：文件名由 root archiveBaseName(rootProject.name) + version 组成，禁止各 harness 硬编码版本号。
// 引用保持相对路径字符串（跨项目任务引用避免配置期跨项目 tasks.named 的时序坑）。
val serverProbeJarName = "${rootProject.name}-${rootProject.version}.jar"
val serverProbeJarPath = "../../plugin/build/libs/$serverProbeJarName"

dependencies {
    // 仅编译期链接被测插件公开 API；运行期由同一测试服内的 ServerProbe 提供。
    compileOnly(files(serverProbeJarPath))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // harness 的 compileOnly(files(...)) 引用 :plugin 发行 jar——显式声明任务依赖，满足 Gradle 隐式依赖校验
    dependsOn(":plugin:jar")
}
