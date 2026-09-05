# 功能规格：Velocity 3.1.1–4.x 平台支持

> 状态：已完成验收，待下次正式版本登记　·　关联 PRD：FR-13　·　分支：当前分支

## 1. 背景与目标

当前单 jar 已支持 Bukkit 系与 BungeeCord，但 Velocity 只有 mc-testkit 的编排预留。Velocity 4 又提高到 Java 25，不能让它的字节码要求污染 Java 8 核心。本功能增加完整命名模块 `platform-velocity`，使同一 `ServerProbe.jar` 覆盖 Velocity 3.1.1 到 4.x。

## 2. 需求（要什么）

- 同一发行 jar 支持 Velocity 3.1.1、最新 3.x（固定 3.5.1）与 4.1.0 代表矩阵；4.1.0 使用 Java 25。
- 能力与 BungeeCord 对齐：JVM、代理总在线、后端在线/RTT/可达性、玩家路由、玩家 ping，以及 FR-11 双向流量与取证。
- 对外命令、Prometheus、Web、FR-08 与配置语义保持一致；无世界/TPS/MSPT，不做跨服务器聚合。
- 构建期分别以 Velocity API 3.1.1 和 4.1.0 做 `compileOnly` 兼容编译；外部 API 不打包。
- 核心与入口保持 Java 8 字节码；Java 25 只用于编译/运行 Velocity 4 兼容门禁。
- 范围内：平台生命周期、代理采集、命令、网络管线、单 jar 描述与测试矩阵。
- 不做：Velocity 与 Bungee/Paper 间的跨实例聚合、Velocity 自有 MCP、为 Velocity 3 强制 Java 25。

## 3. 设计（怎么做）

- 新建 `platform:platform-velocity`。Java 8 入口与平台实现共用一套仅依赖稳定最小 API 的源码；仅在出现真实版本专有差异时再新增最小适配层。
- 当前共同源码仅使用 3.1.1 与 4.1.0 的稳定交集，分别通过 API 3.1.1 与 API 4.1.0 独立子 Gradle 编译门验证；API 4 门用 Java 25 toolchain 和 JVM 25 依赖变体，发行类仍保持 Java 8。真实 4.x 专有类型出现时才新增按运行期版本延迟加载的最小适配层，见 ADR-0026。
- 平台入口通过构造器注入 `ProxyServer`、Logger 与数据目录，生命周期中注册/注销命令、事件、采集器、FR-11 handler 和 FR-14 适配器。
- 后端探测使用 Velocity 注册服务器与异步 ping API，按周期并发但有超时/上限；路由和玩家 ping 从平台事件/公开连接信息采集。
- 单 jar 同时保留 Bukkit/Bungee/Velocity 的平台描述与入口，环境探测保证每个运行时只激活一个平台实现。
- 架构决策见 ADR-0026。

## 4. 任务拆分

- [x] 先补版本选择、生命周期、后端探测、路由、命令与缺失 API 测试。
- [x] 新建 `platform-velocity` 与 API 3.1.1 / API 4.1.0 双实际兼容编译门；共同源码不预置空版本适配层。
- [x] 接入指标、FR-08/Prometheus/Web、FR-11 与 FR-14 平台能力。
- [x] 完成单 jar 平台描述、类版本与外部 API 未打包检查。
- [x] 使用 mc-testkit FR-22 运行 3.1.1、最新 3.x（固定 3.5.1）、4.1.0 三组代理真机矩阵。
- [x] 文档同步：PRD 状态、ARCHITECTURE、API、配置、README、CHANGELOG。

## 5. 验收标准

- 同一个 `ServerProbe.jar` 在三个代表版本上启用成功，无 UnsupportedClassVersionError、NoClassDefFoundError 或平台误激活。
- 两个真实 Paper 后端、一名以上代理玩家场景可验证总在线、后端 RTT/可达性、路由切换与玩家 ping。
- FR-11 在三个 Velocity 版本上得到真实 ingress/egress 包与玩家/IP 关联记录；卸载/断线后 handler 清理。
- FR-14 在三个 Velocity 版本上能读取 JVM/代理状态并执行代理控制台命令；默认关闭时不监听端口。
- 构建检查确认 Velocity API 类未进入发行 jar，核心/入口保持 Java 8 字节码。
- mc-testkit 每组结果文件 PASS，旧 Bukkit/Bungee 单 jar矩阵无回归。

## 6. 风险 / 待定

- Velocity 4 API 或事件签名仍可能变化；只在适配层使用版本专有类型，公共模型不得泄漏平台 API。
- Java 25 toolchain 在开发机/CI 必须显式提供；缺失时应给出中文可操作错误，不得静默跳过 4.1.0 门禁。
- 无剩余功能项；待完成的是发布登记。

## 7. 验收记录（2026-08-28）

- 三组真机矩阵全部 PASS（`build/mc-testkit/results/`）：`velocity-matrix-v31`（3.1.1，legacy forwarding，Paper 1.18.1——唯一允许 legacy 的版本）、`velocity-matrix-v35`（3.5.1，modern forwarding，Paper 1.20.1）、`velocity-matrix-v41`（4.1.0，modern forwarding，JDK25）。
- 同一发布 jar 注入代理，双真实 Paper 后端 + 两名协议玩家；切服路由、后端 RTT/可达性、玩家 ping 与 FR-11 网络取证均经真实协议验证。
- 逐条证据见 `.tmp/acceptance-phase-M5-2026-08-28.md`。
