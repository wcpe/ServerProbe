# ADR-1：探针主体用纯 API + JMX + 采样，不引入 Java Agent

## 状态
已接受

## 背景
现状追认：本决策早于本次 SDD 逆向即已成立，依据 docs/ARCHITECTURE.md §11 补记为独立 ADR。

ServerProbe 的首要场景是"开服慢剖析"与运维指标采集，必须覆盖 Bukkit 系 1.8–1.21.11 全版本（含 Folia）+ BungeeCord，单 jar 多端运行，且核心原则之一是"只读优先，绝不成为事故源"。在选择探针实现路线时面临三条路：裸 ASM + 运行时 self-attach Agent、TabooLib Incision、纯 API + JMX + 采样。运行时 self-attach 在 Paper/JDK21+ 默认失效（`-XX:-EnableDynamicAgentLoading` 拦截、JEP 451 对自挂载发警告并将逐步拒绝），脆弱且高危；而探针绝大多数指标本就有现成稳定 API 可取。

## 决策
探针主体采用纯 API + JMX（`java.lang.management`）+ 平台原生 API + 采样实现，主体不引入 Java Agent、不裸写 ASM。

## 理由
- self-attach（运行时自挂载）在 Paper/JDK21+ 默认失效，不可作为主体方案依赖。
- 90%+ 的指标有现成、稳定的 API（Bukkit API + JMX）即可获取，连 spark 都不需要 Java Agent。
- 主体只读采集，零崩服风险，符合"绝不成为事故源"的稳定性要求。
- 纯 API + JMX 方案实现成本最低、运行开销极低，在全版本 + 多平台上兼容性最佳（见 PRD §5.1 三方案对比表）。

## 后果
- 正面：主体稳定、轻量、全版本通用，丢进 `plugins/` 即用；不依赖任何被 JEP 451 限制的能力。
- 负面/约束：纯 API 拿不到 ServerProbe 自身加载前的盲区数据（如本插件 onEnable 之前的逐插件精确耗时、库下载、无日志卡顿）。这些盲区由后续可选的启动期命令行 premain agent 增强补上（见 ADR-11），而该增强走标准 `premain` 入口、不属于本 ADR 否决的运行时 self-attach。

## 备选方案
- **裸 ASM + 运行时 self-attach Agent**：脆弱且高危——self-attach 在 Paper/JDK21+ 默认失效，各端各 JDK 行为不一，崩服风险最高、实现成本最高。否决。
