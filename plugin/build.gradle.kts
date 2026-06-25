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
            // 业务对接(JBIS FR-122):软依赖 MultiCurrencyEconomy,使探针在 mce 之后加载,
            // 经济事件监听器(BukkitEconomyEventListener)的 @SubscribeEvent 注册时 mce 事件类已就绪
            // (否则探针先于 mce enable 时事件类未加载,TabooLib 报"事件未能找到"致监听器漏注册);
            // 无 mce 的服上软依赖为空操作,不影响探针独立运行。
            name("MultiCurrencyEconomy").optional(true)
            // 业务对接(JBIS FR-125):软依赖 AllinInventorySync,同理使背包追踪事件监听器(BukkitInventoryEventListener)
            // 的 @SubscribeEvent(bind=...) 在其事件类就绪后注册;无 AllinInventorySync 的服上为空操作,不影响独立运行。
            name("AllinInventorySync").optional(true)
        }
    }
    // 将随 agent 打进二合一 jar 的 ASM 重定向到 agent 专属影子包，避免与服务器/其它插件自带的 ASM 冲突。
    // TabooLib 打包阶段（taboolibMainTask）会用 ASM ClassRemapper 按此前缀改写全部 .class：
    // 既改写 ASM 自身的包名，也同步改写 agent 类对 ASM 的全部引用，二者保持一致。
    relocate("org.objectweb.asm", "top.wcpe.mc.plugin.serverprobe.agent.shadow.asm")
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
    // 启动期 agent 的字节码插桩依赖 ASM。用 taboo(...) 而非 compileOnly:
    // taboo 既加入编译类路径(agent 纯 Java 需编译期可见 ASM),又把 ASM 的 class 实打实合并进二合一 jar,
    // 随后由上方 relocate 规则改写到 agent 影子包,确保 system ClassLoader 能加载到 relocate 后的 ASM。
    taboo("org.ow2.asm:asm:9.7.1")
    taboo("org.ow2.asm:asm-commons:9.7.1")
}

tasks {
    jar {
        archiveBaseName.set(rootProject.name)
        // 声明二合一 jar 的 agent 入口。这些属性经 TabooLib 打包(taboolibMainTask)原样保留(实测):
        // MANIFEST.MF 作为非 class 资源直接透传,不被 ASM 改写。
        manifest {
            attributes(
                "Premain-Class" to "top.wcpe.mc.plugin.serverprobe.agent.ProbeAgent",
                "Agent-Class" to "top.wcpe.mc.plugin.serverprobe.agent.ProbeAgent",
                "Can-Retransform-Classes" to "true",
                "Can-Redefine-Classes" to "true"
            )
        }
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
    // 真机验证 agent:二合一 jar 既是 plugins/ 插件又作 -javaagent 自挂载(premain 注入服务器启动流程);
    // -javaagent 指向 jar 任务产物(runServer 依赖 jar,启动时该文件已就绪)。-Xmx2G 限堆避免本机内存紧张时 OOM。
    jvmArgs("-Xmx2G", "-javaagent:${tasks.jar.get().archiveFile.get().asFile.absolutePath}")
}
