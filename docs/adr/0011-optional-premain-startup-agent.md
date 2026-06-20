# ADR-11：为补加载前盲区做可选的 premain Java Agent 增强（默认不启用）

## 状态
已接受

## 背景
现状追认：本决策早于本次 SDD 逆向即已成立，依据 docs/ARCHITECTURE.md §11（及 §13）补记为独立 ADR。

ServerProbe 的首要场景是"开服慢剖析"，但纯 API 主体只能看到本插件 onEnable 之后的世界。本插件自身加载前的盲区——早于本插件加载的插件计时、库下载耗时、启动期"无日志卡顿"的卡顿热点——纯 API 不可见。同时 ADR-1 已明确"探针主体不引入 Java Agent"，否决的是**裸 ASM + 运行时 self-attach**（Paper/JDK21+ 默认禁用、受 JEP 451 约束、脆弱高危）。需要在不触碰被否决方案的前提下，给出补盲区的手段。

## 决策
为补 ServerProbe 加载前盲区，做一个**可选的 premain Java Agent** 增强：通过命令行 `-javaagent:plugins/ServerProbe.jar` 启用，默认不启用；采集逐插件 load/enable 精确耗时、库下载耗时、世界/配置/事件/命令耗时 + 多线程折叠栈，并据此导出启动火焰图 + 嵌套时间线，以及 HTTP/TCP 外呼监控（外呼监控细节见 ADR-12）。

## 理由
- **premain ≠ self-attach**：本 agent 是命令行 premain agent，由 JVM 在启动期（`main` 之前）经标准 `premain` 入口加载，不是被 ADR-1 否决的运行时 self-attach，不受 JEP 451 限制，在 Paper + JDK21/24 上零警告。主体仍是纯 API——不加 `-javaagent` 时 ServerProbe 就是普通插件，既有能力照常工作；agent 只是叠加其上的可选增强。
- **唯一能拿到本插件 onEnable 之前的数据**：逐插件精确耗时（纳秒级，优于日志解析的秒级，且覆盖本插件之前加载的插件）、库下载耗时、启动期"无日志卡顿"的主线程栈采样热点，纯 API 都拿不到。
- **折叠栈保留调用层级**：按线程名周期抓**完整调用栈**并折叠聚合，得以还原真正的多层火焰图，而非逐帧词频那种丢层级的统计。
- **二合一 jar**：`ServerProbe.jar` 同一产物既是 `plugins/` 下的插件，又是 `-javaagent` 的 agent（清单含 `Premain-Class`），无需第二个 jar、无需改部署位置。
- **唯一新依赖 ASM 并 relocate 隔离**：premain 期需字节码插桩而引入 ASM，relocate 到 `...agent.shadow.asm` 与服务端/其他插件隔离，避免版本冲突；这是本增强带来的唯一一个新第三方依赖。
- **ProbeAgentBridge 放 bootstrap CL**：插桩字节码运行在被插桩类的 CL，插件逻辑在 TabooLib 插件 CL，天然隔离。为让"插桩字节码 + 插件反射读取 + 栈采样"共享同一份计时数据，premain 经 `Instrumentation#appendToBootstrapClassLoaderSearch` 把一个极薄的 `ProbeAgentBridge` 放到 bootstrap CL，对所有 CL 全局可见，三方引用同一份。
- **不成事故源**：采集收敛在启动窗口、`premain` 全程被顶层 `catch(Throwable)` 包裹，任何异常只静默降级为"agent 未生效"，绝不向上抛、绝不崩 JVM。

## 后果
- 正面：补齐了纯 API 看不见的加载前盲区，启动慢可量化到纳秒级与调用栈级；作为可选项不影响主体的稳定与简单。
- 负面/约束：需用户手动在启动命令加 `-javaagent` 参数，不加则纯插件模式正常工作（降级路径明确）。引入了对 ASM 的依赖（已 relocate 隔离）。
- **bootstrap CL 真机坑教训（§13.4，作为硬约束）**：
  1. 插桩字节码直接引用 Bridge 会因 Bridge 尚未对那个 CL 可见而 `NoClassDefFoundError`。
  2. 为"解决"上一坑而把整个 agent 子树 append 到 bootstrap，会导致 bootstrap CL 的类反向访问仍在 app CL 的类（双向跨界），触发 `IllegalAccessError` 直接崩 JVM。
  共同教训与终解：**bootstrap CL 上只放"零反向依赖、对所有 CL 安全可见"的最小桥接类**（仅 `ProbeAgentBridge`），插桩字节码只与它交互，再叠加 premain 顶层 `catch(Throwable)` 兜底；多放一寸都可能把单向隔离破坏成双向越界。
- **范围与降级**：M5 先 Bukkit 端；Folia 无单一主线程，主线程栈采样降级标 N/A（引导用 spark），插件计时复用 Bukkit 路径；BungeeCord 推迟，本阶段不做。
- **真机边界（诚实记录）**：当前**仅 1.21.4 Paper 单端真机验证**全通（`Done` 正常、逐插件精确耗时正确、栈采样正常、无 `IllegalAccessError`/`NoClassDefFound`、不破坏启动）；其他端（1.8 / Folia / BungeeCord）未逐一真机。

## 备选方案
- **仅靠日志解析**：被否决，秒级粒度、且拿不到"无日志卡顿"的热点。
- **运行时 self-attach**：被否决（已被 ADR-1 否决），Paper/JDK21+ 默认禁用、受 JEP 451 约束。
- **逐帧词频**：被否决，丢失调用层级，无法还原真正的火焰图。
