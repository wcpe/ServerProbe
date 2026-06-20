# 架构不变量（防架构漂移）

> 以下是本项目锁定的架构约束（依据 `docs/ARCHITECTURE.md` 与 `docs/adr/`）。**违反任一条即为架构漂移。**
> 确需改变某条 → 先写新 ADR 取代旧决策、经确认后再改；**禁止在代码里静默违背**。

## 1. 分层与依赖方向（核心边界）

- 模块依赖**单向向下**：`plugin → platform-* / nms-* → core → api`。**严禁反向或环形依赖。**
- `api`（契约：采集器接口 + 指标/启动画像模型）与 `core`（通用采集编排/JMX/聚合/告警/呈现/本地存储+开放接口）**不依赖任何平台 API**（无 Bukkit/BungeeCord 符号）。
- 平台实现（`platform-bukkit` / `platform-bungee`）实现 `api` 的接口，并标 `@PlatformSide(Platform.BUKKIT|BUNGEE)`；`core` 经运行时装配拿到实现，**编译期不依赖具体实现**。
- 跨 toolchain 方向：高版本 NMS 胶水模块可 `compileOnly` Java 8 模块；**Java 8 核心绝不反向依赖高版本模块产物**，只经接口 + 运行时反射装配。
- IOC 宿主约定：`@SubscribeEvent` 事件监听与命令宿主必须是 `object` + `@Inject`，不可用 `@Service class`（taboolib 取静态 INSTANCE 失败会抛 `not a static method`）。

## 2. 简单优先（禁用的重型件）

- **不引入数据库 / EasyQuery**：启动画像与指标历史一律本地文件落盘（JSON/JSONL，原子写入），经存储 SPI，默认且唯一内置=本地文件实现（ADR-7）。
- **探针主体不引入 Java Agent、不裸写 ASM**（ADR-1）：主体只读，90%+ 指标用现成 API + JMX。被否决的是**运行时 self-attach**。
- **运行期 CPU 火焰图不自研**，引导并用 spark（ADR-8）；本项目专注指标采集 + 启动剖析。
- **新增第三方依赖须逐个确认、优先轻量**（ADR-10）：JSON 优先用 TabooLib 自带 gson，Prometheus 用 JDK 内置 `HttpServer` 零依赖手写。
- **例外（已走 ADR）**：可选启动期 premain agent 引入唯一新依赖 ASM（ADR-11），relocate 到 `...agent.shadow.asm` 隔离，默认不启用。

## 3. 真源 / 一致性约束

- 启动画像 / 指标历史的真源 = **本地文件**（经 `MetricStore` SPI 写入，默认唯一本地实现）；对外只读一律经 `ProbeReadApi`（只读、不暴露写入/控制）。
- 落盘根对象均含 `schemaVersion`，作为格式演进与向后兼容的唯一依据；改落盘结构必须同步演进 schemaVersion，不得静默改格式。
- 启动期 agent 与插件共享计时数据，只经 bootstrap ClassLoader 上**唯一的、极薄的 `ProbeAgentBridge`**（零反向依赖、对所有 CL 安全可见）；**严禁把整个 agent 子树 append 到 bootstrap CL**（真机已证会触发 app↔bootstrap `IllegalAccessError` 崩 JVM，ADR-11）。

## 4. 技术栈锁定

- 语言/框架：**Kotlin 2.1.0 / TabooLib 6.3.0 / taboolib-ioc 0.0.6**；核心编译 **Java 8 target**（覆盖 1.8–1.21.x 全 JRE）。**`api` 模块为纯 Java + Lombok**（对外公开契约、零 Kotlin metadata，ADR-13）；core/platform/plugin 仍 Kotlin。
- 调度一律走 TabooLib `submit()/submitAsync()`（已原生适配 Folia），**严禁直接 `Bukkit.getScheduler()`**（ADR-4）。
- TPS/MSPT 经自研 `ServerTickSampler` 抽象多实现（ADR-5）；NMS 访问优先反射（`nmsClass`/`getProperty`），避免直接 `extends` 高版本类型而被迫抬 toolchain（ADR-3）。
- 分发形态：**单 jar 多端**（ADR-6）；启动 agent 与插件为**二合一 jar**（ADR-11）。
- 换语言 / 换框架 / 换存储形态 = 架构决策 → 走新 ADR，不擅自更换。

## 红线（出现即停止并先确认）

引入数据库 / 重型中间件 · `api`/`core` 出现平台 API 符号或反向依赖高版本模块产物 · 直接调用 `Bukkit.getScheduler()` · 探针主体引入运行时 self-attach Agent / 裸 ASM · 主线程阻塞磁盘 IO / 远程调用 · 往 bootstrap CL append 非最小桥接类 · 改落盘结构不演进 schemaVersion · 静默违背任一已接受 ADR。
