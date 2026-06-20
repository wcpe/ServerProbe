# 验证门与质量底线（防质量漂移）

> 项目特定的测试与质量要求。通用反模式禁令（上帝类、长方法、复制粘贴、魔法值、吞异常、资源泄露、循环依赖、静态可变单例等）仍然适用。

## 1. 验证门（权威判据）
- **每个变更必须过验证门才算完成**，判据以**入库真源**为准：① `docs/PRD.md` 验收标准对应项满足；② 本文 §2 高风险区相关测试通过；③ 受影响模块全部测试绿。
- **测试 / 构建命令**（本项目）：
  - 全量构建（含测试）：`./gradlew build`
  - 仅测试：`./gradlew test`
  - 开发版构建（含 TabooLib 本体，不可运行）：`./gradlew taboolibBuildApi -PDeleteCode`
- **完成以证据为准、不轻信报告**：声称"做完 / 修好 / 通过"前，必须出示客观证据（命令 + 关键输出、失败用例从红转绿）。**涉及真机维度的（见 §2）须附该端真机复验结果**——编译 + 单测全绿**不替代**真机验收。拿不齐 = 未完成、继续做（含不轻信子代理的口头汇报）。
- **被推翻就回流继续修**：声称完成后被实测 / 真机 / 用户验收推翻，即视为该变更未完成，回到本任务对应技能的验证步骤继续修，不另开新流程、不重标状态。
- **提交受验证门门控**：验证门未全绿不得 `git commit`（门控见 `git-commit.md` §3）。
- `.tmp/` 下实施计划的勾选**仅作开发期辅助**，不入库、不作权威判据。
- **禁止**以注释、跳过、删除失败测试的方式让测试"通过"。
- 改功能代码前先跑相关测试确保通过；新增 / 改业务逻辑同步加测试。

### 1.1 测试分层（怎么分、在哪跑）
- **单元**：纯逻辑（采样分位、聚合差分、环形缓冲、原子写、日志解析、启动对比、告警状态机）——不连服务端，最快最多（现有 `project:core` / `platform-bukkit` 的 `src/test` 即此层）。
- **真机验收**：在目标服务端（Paper/Spigot/Folia/BungeeCord）实跑，验证采集真实数据、命令输出、Prometheus 端点、启动 agent 注入。**这是本项目不可省的维度**（见 §2）。

## 2. 必测的高风险区（逐项穷举判据）

1. **跨 ClassLoader 启动 agent 通道**（最高危，真机才暴露）：`ProbeAgentBridge` 只放 bootstrap CL 且为零反向依赖最小桥接类；premain 顶层 `catch(Throwable)` 绝不崩 JVM；插桩字节码经 ASM `CheckClassAdapter` 校验合法；`FrameSafeClassWriter` 覆盖 `getCommonSuperClass`（防帧计算 `ClassNotFoundException` 致 hook 静默失效）。**任一改动须 1.21.4 Paper 真机复验** `Done` 正常、无 `IllegalAccessError`/`NoClassDefFound`、hook 实际生效。
2. **并发与共享缓冲**：环形缓冲（`RingBuffer`/`MetricSnapshotBuffer`）、HTTP 外呼有界缓冲（`HttpCallStore`）、跨线程栈采样——穷举定容溢出、并发读写、启动窗口关闭后不再追加（防运行期内存泄漏）。
3. **多版本 / 多平台兼容**：TPS/MSPT 在 Paper / 低版本 NMS / 自采样 / Folia(全局 N/A) 各路径降级正确；`@PlatformSide` + `@PostConstruct` 平台门控（错误平台不得 collect，防 `NoClassDefFoundError`）；`com.sun.management` 在不同 JDK 的差异容错。
4. **原子落盘与滚动清理**：`AtomicJsonWriter`（临时文件 + rename）；JSONL 按日滚动 + 保留天数 + 体积上限双闸清理**绝不删当天文件**；`schemaVersion` 向后兼容读旧格式。
5. **对外端点鲁棒性**：Prometheus 端口被占用优雅降级（warn 不影响插件启用）；token + IP 鉴权（401/403）；告警防抖（持续 N 周期）与恢复状态机。
6. **主线程不阻塞**：落盘 / 聚合 / 采样 / 远程（Webhook）全异步或限频；MSPT 主线程仅取 `nanoTime`；严禁主线程磁盘 IO / 远程调用。

## 3. 质量底线
- 分层依赖单向、不反向穿透（见 `architecture-invariants.md` §1）。
- 锁 / 临界区职责单一、不嵌套，高开销 IO 一律在临界区外。
- 核心计算尽量做成无副作用纯函数（采样 / 聚合 / 解析），便于穷举测试。
- 落盘成功后才触发对外副作用（命令回显 / 告警 / 导出）。
- 中文分级日志（ERROR/WARN/INFO/DEBUG），不留 `println` / `System.out.println` 等临时调试输出。
- 不硬编码 token / 端口 / 超时 / 阈值，走 `config.yml` 或常量。
