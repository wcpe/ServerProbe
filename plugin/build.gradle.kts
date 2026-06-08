import io.izzel.taboolib.gradle.*
import xyz.jpenilla.runpaper.task.RunServer

plugins {
    // 真机验收:run-paper 自动拉起 Paper 测试服务器并装入本插件
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

taboolib {
    subproject = false
    description {
        name(rootProject.name)
        desc("服务器探针")
        contributors {
            name("WCPE")
        }
        dependencies {

        }
    }
    env {
        install(Basic)
        install(Bukkit)
        install(BukkitUtil)
        install(BukkitUI)
        install(CommandHelper)
        install(I18n)
        // 代理端平台,生成 bungee.yml 描述符,实现单 jar 多端
        install(BungeeCord)
    }

}
taboolibIoc {
    // 是否启用自动接管：自动注入 IoC 依赖并自动追加 relocate 规则。
    autoTakeover(true)
    // 静态诊断发现 error 时直接拦截构建。
    analysisFailOnError(true)
}

dependencies {
    // 壳模块需触碰 Bukkit API(主类/事件),引入 universal
    compileOnly("ink.ptms.core:v12004:12004:universal")
    taboo(project(":api"))
    taboo(project(":project:core"))
    taboo(project(":platform:platform-bukkit"))
    taboo(project(":platform:platform-bungee"))
}

tasks {
    jar {
        archiveBaseName.set(rootProject.name)
        // 将所有 project:* 子模块和 api 的编译输出打包进最终 JAR
        from(project(":api").sourceSets["main"].output)
        from(project(":project:core").sourceSets["main"].output)
        from(project(":platform:platform-bukkit").sourceSets["main"].output)
        from(project(":platform:platform-bungee").sourceSets["main"].output)
    }
}

// run-paper:拉起 Paper 1.21.4 测试服(run-paper 自动装入发行 jar)。
// Paper 1.21.4 需 Java 21,而项目核心 toolchain=Java 8;故单独为测试服指定 Java 21 启动,不影响核心编译。
tasks.withType<RunServer>().configureEach {
    minecraftVersion("1.21.4")
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
}
