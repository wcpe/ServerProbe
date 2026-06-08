import io.izzel.taboolib.gradle.*

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
    }

}
taboolibIoc {
    // 是否启用自动接管：自动注入 IoC 依赖并自动追加 relocate 规则。
    autoTakeover(true)
    // 静态诊断发现 error 时直接拦截构建。
    analysisFailOnError(true)
}

dependencies {
    taboo(project(":api"))
    taboo(project(":project:core"))
}

tasks {
    jar {
        archiveBaseName.set(rootProject.name)
        // 将所有 project:* 子模块和 api 的编译输出打包进最终 JAR
        from(project(":api").sourceSets["main"].output)
        from(project(":project:core").sourceSets["main"].output)
    }
}
