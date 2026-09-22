# 功能规格：代理端 MCP 日志检索（log_tail / log_search）

> 状态：开发中　·　关联 PRD：FR-28（增强 FR-16）　·　分支：feature/proxy-log-tail

## 1. 背景与目标

FR-16 的 MCP 日志检索在代理端恒返回"平台不支持"——而 MCP 是代理端**唯一**的诊断通道（无 TPS/世界等指标），日志恰恰是代理端最主要的排障线索。spec（log-tail.md §风险）自述"本 FR 先做 Bukkit 路径，代理端缺省返回平台不支持"，是登记在案的欠条。core 契约 `LogPathProvider` 与 `LogTailToolProvider` 全部现成，本 FR 只补两端平台实现。

## 2. 需求（要什么）

- BungeeCord：`LogPathProvider` 实现指向服务端根目录 `proxy.log`（BungeeCord 默认日志名，`logger.log` 兼容性实现时实测确认）。
- Velocity：`LogPathProvider` 实现指向 Velocity 工作目录 `logs/` 下最新日志（Velocity 日志轮转命名实现时实测确认，如 `latest.log`/带日期轮转）。
- core 侧零改动或最小改动：两端 provider 按 `@Service + @PlatformSide` 常规注册；`LogTailToolProvider` 已按 `logPathRegistry` 可空注入发现实现，无需改 core。
- 范围内：两个平台模块各一个 provider 类 + 单测（路径解析逻辑）+ spec/CHANGELOG 同步。
- 不做（范围外）：
  - 不改检索/分页/编码逻辑（core 已有，Windows GBK 兼容已由平台 `charset()` 处理，两端沿用同机制）。
  - 不做历史轮转日志检索（只做"最新日志"，与 Bukkit 路径口径一致）。
  - 不做 Velocity 3.1.1 以下的特殊兼容（共享源码一次覆盖 3.1.1–4.x，路径 API 若有差异按 `MinecraftVersion` 同款方式分流）。

## 3. 设计（怎么做）

- 参照 `BukkitLogPathProvider`：`latestLog()` 返回日志文件 Path（不存在返回 null → 工具结构化降级），`charset()` 返回平台字符集。
- 服务端根目录解析：代理端按各自平台的工作目录约定（BungeeCord 为启动目录、Velocity 为工作目录），实现时以真机实际落点为准并在类 KDoc 记录。
- 注册路径复用 FR-16 既有链路（`@Inject(required=false) logPathRegistry`），无新增配置键。

## 4. 任务拆分

- [ ] `platform-bungee`：`BungeeLogPathProvider`（`proxy.log` 解析 + 字符集）。
- [ ] `platform-velocity`：`VelocityLogPathProvider`（`logs/` 最新日志解析 + 字符集，兼容 3.1.1–4.x）。
- [ ] 单测：路径解析/不存在时 null/轮转文件名识别（纯逻辑部分）。
- [ ] 文档同步：`docs/specs/log-tail.md` 欠条段落改写为已覆盖、`CHANGELOG.md` 未发布段、PRD FR-28 状态流转。

## 5. 验收标准

- 单测全绿（纯路径逻辑）。
- 真机（BungeeCord）：MCP `log_tail`/`log_search` 返回真实日志尾部与命中行，行号/游标分页正确——**实测当前日志名为 `proxy.log.0`**（非明文 `proxy.log`，见 §6）。
- 真机（Velocity 3.1.1 与 4.1.0 各一）：同上，`logs/` 最新日志可读。
- 真机降级路径：日志文件不存在时返回结构化"平台未提供日志文件"，工具不报错。
- 归属验证维度归入 FR-16 口径：Windows（GBK）与 Linux（UTF-8）各验一次字符集正确性。

## 6. 风险 / 待定

- ~~代理端日志**轮转文件名/落点**需真机实测后才能定稿~~ **已定（真机实证回填）**：Velocity 日志落点为工作目录根下 `logs/latest.log`（本仓 e2e 实测运行目录 `run-proxy/logs/latest.log` 与 `run-paper-network-velocity/logs/latest.log` 实证；轮转 `latest.log.N.gz`）；**BungeeCord 实测为非明文名**——BungeeCord #2088 + JDK 21 真机（工作目录全新铺设）下不存在 `proxy.log`，当前日志为带代次后缀的 `proxy.log.0`（同目录另有 `proxy.log.0.lck` 锁文件，重启后沿用同一文件追加），故实现改为"优先明文 `proxy.log`，否则取按修改时间最新的 `proxy.log.<纯数字>`"（按时间而非代次编号排序，避开 JDK `FileHandler` 代次语义差异，并天然排除 `.lck`/`.gz`）。原 `proxy.log` 的假设来自 spec/log-tail 原文，真机验收推翻。
- Windows 下日志共享读（服务端持写锁）沿用 FR-16 已验证的降级路径。
