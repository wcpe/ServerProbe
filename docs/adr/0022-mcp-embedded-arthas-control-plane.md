# ADR-0022：ServerProbe MCP 控制面内嵌最小 Arthas 运行闭包

## 状态

已被 [ADR-0024](0024-arthas-311-runtime-closure.md) 取代（原决策历史保留）

## 背景

事故排查需要比常规指标更深的线程、类、方法和字节码能力。外置 Arthas 可能无法连接受限 JDK，而默认运行时 self-attach 又已被 ADR-0001 否决为主体机制。用户要求手动开启、跨平台、可被外部 agent 调用的完整诊断控制面。

## 决策

新增 `diagnostics-arthas` 模块：ServerProbe 自己提供默认关闭的 Streamable HTTP MCP 端点，构建期嵌入 Arthas `4.3.4` 官方发行包的最小运行闭包；优先复用 premain Instrumentation，未提供时仅尽力动态 attach 一次。

## 理由

- 发行包随单 jar 交付、运行期不联网，减少事故时依赖外部下载和“Arthas 连不上”的问题。
- MCP 网关由 ServerProbe 统一实现，可结合平台命令、现有指标、任务、产物和脱敏审计；上游 Arthas MCP 不暴露。
- 默认关闭、默认回环、可选 Bearer 保留最小安全姿态，同时尊重用户允许的明文完整控制模式并显式告警。

## 后果

- 只有配置开启时才监听端口；非回环、无密钥或明文模式必须中文 WARN，但不强制拦截。
- Arthas Core/Spy/Agent/Boot、许可证与哈希 manifest 原样嵌入，运行时原子提取到插件数据目录并使用隔离 ClassLoader；client、math-game、文档和脚本不打包。
- 无 Instrumentation 时原生线程/状态诊断仍可用；完整 Arthas 工具会报告 attach 降级状态，用户可手动重试。
- MCP 不提供第一方 OS Shell；OGNL/字节码能力不是安全沙箱，运维者对其完全控制后果负责。

## 备选方案

- **运行期下载/外置 Arthas**：事故时受网络与 JDK attach 限制，部署不确定，否决。
- **复制 Arthas 源码进项目**：难以升级、许可证与维护成本高，否决。
- **实现自研 JVM 诊断器**：无法在合理成本下覆盖 Arthas 能力，否决。
