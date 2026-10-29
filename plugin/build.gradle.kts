import io.izzel.taboolib.gradle.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.Manifest
import java.util.zip.ZipFile

plugins {
    id("serverprobe.base")
    id("serverprobe.bundle")
}

taboolib {
    subproject = false
    description {
        name(rootProject.name)
        desc("服务器探针")
        contributors {
            name("WCPE")
        }
    }
    // 将随 agent 打进二合一 jar 的 ASM 重定向到 agent 专属影子包，避免与服务器/其它插件自带的 ASM 冲突。
    // TabooLib 打包阶段（taboolibMainTask）会用 ASM ClassRemapper 按此前缀改写全部 .class：
    // 既改写 ASM 自身的包名，也同步改写 agent 类对 ASM 的全部引用，二者保持一致。
    relocate("org.objectweb.asm", "top.wcpe.mc.plugin.serverprobe.agent.shadow.asm")
    // 以项目内的同名实现替换 IoC 的扫描器：先按平台过滤，再反射方法，避免 Bungee 解析 Bukkit 方法签名。
    exclude("top/wcpe/taboolib/ioc/scan/ComponentVisitor")
    env {
        install(Basic)
        install(Bukkit)
        install(BukkitUtil)
        install(CommandHelper)
        install(I18n)
        install(Incision)
        repoTabooLib = "https://maven.wcpe.top/repository/maven-public/"
        // 代理端平台,生成 bungee.yml 描述符,实现单 jar 多端
        install(BungeeCord)
        // Velocity 平台，生成 velocity-plugin.json 描述符并打包对应运行时实现。
        install(Velocity)
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
    taboo(project(":platform:platform-velocity"))
    taboo(project(":diagnostics:diagnostics-arthas"))
    // SQLite 驱动随发行 Jar 内置，禁止运行期联网下载。
    taboo("org.xerial:sqlite-jdbc:${property("sqlite.jdbc.version")}")
    // 启动期 agent 的字节码插桩依赖 ASM。用 taboo(...) 而非 compileOnly:
    // taboo 既加入编译类路径(agent 纯 Java 需编译期可见 ASM),又把 ASM 的 class 实打实合并进二合一 jar,
    // 随后由上方 relocate 规则改写到 agent 影子包,确保 system ClassLoader 能加载到 relocate 后的 ASM。
    taboo("org.ow2.asm:asm:9.10.1")
    taboo("org.ow2.asm:asm-commons:9.10.1")
    // plugin 模块的测试覆盖命令层纯函数（ProbeFormat 的单位换算与区域无关性）与 IoC 扫描契约。
    // TabooLib 传递的 junit 5.8.1 与 5.14.4 冲突，故引擎与 launcher 版本配对显式锁死，
    // 保证 JUnit Platform 正常加载。
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.4")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }
}
