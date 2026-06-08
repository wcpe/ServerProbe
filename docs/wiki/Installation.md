# 安装与构建

> ⚠️ 本页为设计阶段文档,描述目标行为,功能尚未实现。

本页面向两类读者:**服主**(只想把插件装上)和**开发者**(需要从源码构建)。

> ⚠️ 项目当前为空骨架、尚无可用产物,以下安装步骤为目标流程占位,标注 🚧 规划中。

---

## 一、服主安装(🚧 规划中)

ServerProbe 设计为**单 jar 多端**:同一个 jar 既能放进 Bukkit 系服务端,也能放进 BungeeCord 代理端。

1. 获取 `ServerProbe-x.y.z.jar`(🚧 规划中,目前无发行产物)。
2. 将该 jar 放入对应服务端的插件目录:
   - Bukkit/Spigot/Paper/Folia:`<服务端根目录>/plugins/`
   - BungeeCord:`<代理端根目录>/plugins/`
3. 重启服务端(探针涉及启动剖析,**不建议热加载**;启动总时长依赖完整的启动过程)。
4. 首次启动会生成默认配置(🚧 规划中,具体配置项见 [数据呈现与对接](Data-Output.md))。
5. 用 `/probe health` 验证是否正常采集(见 [命令与权限](Commands.md))。

> 提示:若代理端 + 后端均安装,二者各自独立采集;代理端只采网络/子服健康类数据,详见 [版本与平台兼容](Compatibility.md)。

---

## 二、运行环境要求

探针核心为 **Java 8 字节码**,可被 1.8–1.21.x 全部 JRE 加载。实际所需 JRE **由服务端版本决定**,而非探针本身:

| MC 版本范围 | 服务端要求的 JRE | 说明 |
|---|---|---|
| 1.8 – 1.16.x | Java 8+ | 探针核心即 Java 8,天然兼容 |
| 1.17 – 1.20.4 | Java 17+ | 服务端强制 17;探针仍以 Java 8 字节码运行 |
| 1.20.5 及以上 | Java 21+ | 服务端强制 21;探针仍以 Java 8 字节码运行 |

要点:
- 探针**不会**抬高你的 JRE 要求;装哪个 MC 版本就按该版本的官方 JRE 要求准备。
- 极少数"必须直接引用高版本 NMS 类型"的可选胶水模块会用高 toolchain 编译,但通过**按需加载**保证低版本 JVM 永不触碰这些 class,不会出现 `UnsupportedClassVersionError`。详见 [版本与平台兼容](Compatibility.md)。

---

## 三、开发者构建

技术栈:Kotlin 2.1.0 / TabooLib 6.3.0 / taboolib-ioc 0.0.5 / 本地文件存储 + 开放接口,`groupId = top.wcpe.mc.plugin.serverprobe`。

### 构建命令

| 命令 | 用途 |
|---|---|
| `./gradlew build` | **发行构建**,产出可分发 jar,**不含 TabooLib 本体** |
| `./gradlew taboolibBuildApi -PDeleteCode` | **开发构建**,产出**含 TabooLib 本体**的 jar(用于本地起服调试) |

Windows PowerShell 下相应使用 `.\gradlew.bat build` 等。

### 模块结构(摘要)

构建产物由多模块合并进单 jar(详见 [架构文档](../ARCHITECTURE.md)):

| 模块 | 职责 |
|---|---|
| `api` | 契约层:采集器接口、指标/启动画像模型(零平台 API) |
| `core` | 通用核心:采集编排/调度、JMX 采集、聚合、告警、呈现、本地文件存储 + 开放接口 |
| `platform-bukkit` | Bukkit/Paper/Folia 采集(在线/世界/区块/实体/TPS/MSPT) |
| `platform-bungee` | 代理端采集(子服在线/ping/路由/JVM) |
| `nms-vXXX` | 可选,仅当某指标必须直接引用 NMS 专有类型时才建 |
| `plugin` | 壳 + env install + 单 jar 打包 |

### 打包说明
- 单 jar 沿用 `plugin/build.gradle.kts` 的 `taboo(project(...))` + `from(sourceSets.output)` 合并模式。
- 不额外 `shadowJar relocate`(TabooLib 插件已接管 relocate / IOC-takeover)。
- 不同 major version 的 class 可共存于同一 jar,按需加载避免低版本 JVM 触碰高版本 class。

> ⚠️ 构建脚本(`build.gradle.kts`、`settings.gradle.kts` 等)属关键文件,改动需经确认。本页仅说明用法,不代表已有可构建源码。

---

> 返回 [Wiki 首页](Home.md)
