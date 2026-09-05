# 功能规格：开放接口与业务桥自动化验收（FR-08 / FR-09）

> 状态：已验收（2026-08-24）
> 关联 PRD：FR-08、FR-09
> 关联 ADR：ServerProbe ADR-15、ADR-17；JianManager ADR-026～029

## 目标

为两个当前缺乏可复现端到端证据的能力建立自动化验收：

1. **FR-08 开放接口**：真实第三方插件读取 ServerProbe 运行数据，并替换默认存储后端。
2. **FR-09 业务对接 agent**：在测试服内以协议等价的本地 Worker fixture 下发业务命令，验证 Provider 回执、事件上报与事故域隔离。

测试结果唯一真源为 mc-testkit harness 写入的 `build/mc-testkit/results/<scenario>.properties`；不得依据日志猜测成功。

## 范围与边界

### 纳入

- 接入发布版 `top.wcpe.mc-testkit:0.5.1`，仅作测试期依赖。
- 新增独立的 E2E harness 插件；它作为第三方消费者，不复用 ServerProbe 的内部包。
- 新增 FR-08 的读取 API 场景与存储 SPI 替换场景。
- 新增 FR-09 的真实 Paper 与本地 Worker fixture 场景；fixture 只模拟已冻结的插件桥 JSON/WebSocket 协议，不伪造 Provider 成功。
- 使用测试专用 `BusinessProvider` 驱动成功、业务失败、超时及事件上报；测试结束清理服务端与 fixture 线程。

### 不纳入

- 不测试 ADR-0017 已明确降级的 `writeInventory` / `writeEnderChest`。
- 不使用 JianManager 作为测试运行时依赖，不修改 JianManager。
- 不使用无条件 PASS；fixture 只在收到每条预期协议帧并完成真实 `BusinessHost` 派发后写 PASS。
- 不修改 mc-testkit 框架；现有 v0.5.1 已能编排 Paper、依赖 jar、服务端模板与机器人。只有发现框架阻塞时才单独开变更。

## 设计

### FR-08：第三方消费者与存储扩展

现状的 `MetricStore` 只有接口及内置 `@Service` 实现，缺少第三方替换实例的注册、选择和生命周期契约。因此先写失败的集成测试，再增加最小公开扩展点：

- `ProbeReadApi` 继续严格只读；不在其中暴露存储写入或控制。
- 新增独立的存储扩展注册门面，允许已加载的第三方插件安装一个 `MetricStore`；未安装时始终委派内置 `LocalFileMetricStore`。
- 所有内部注入点改为使用单一、可原子切换的委派存储，确保第三方在 `ServerProbe` 启用后加载仍可生效。
- 同一生命周期仅允许一个替换实例；重复安装必须明确失败，不得静默覆盖。卸载后回退内置存储。

E2E harness 等待 ServerProbe 运行期就绪后执行：

1. 等待至少一份指标快照与启动画像。
2. 经公开门面读取 `TPS/MSPT/启动画像`，断言非空且字段符合平台语义。
3. 安装记录型 `MetricStore`，等待新采样与启动画像写入，断言替换后端收到两类数据；卸载后断言恢复默认委派。

### FR-09：协议 Worker fixture

FR-09 自动化验收保留在 ServerProbe 仓内。mc-testkit 准备任务在 Paper 创建插件类加载器前，为探针写入随机回环地址与一次性测试凭据；E2E harness 随后在 `ServerProbe` 加载前启动仅用于测试的本地 RFC 6455 服务端。fixture 严格使用插件桥现有 JSON 形态：

```
fixture → command(domain/action/payloadJson/requestId) → BridgeClient
fixture ← command_result(requestId/success/output/error)  ← BusinessHost / Provider
fixture ← event(domain/dedupKey/data)                     ← BridgeClient
```

测试 harness 通过 ServerProbe 已公开的运行时容器注册 `e2e` 域测试 Provider；Provider 的成功、失败、阻塞及事件上报均在其真实 `dispatch` 中发生。fixture 必须按下列状态机判定：

1. 收到 `hello` 后回 `welcome`，收到 `ping` 后回 `pong`。
2. 下发 `jbis/manifest`，断言收到的 `command_result` 含已注册的 `e2e` 域。
3. 下发 `e2e.echo`，断言 `requestId`、`domain`、`payloadJson` 与成功输出完整往返。
4. 下发 `e2e.emit`，断言接到带 `domain`、`dedupKey` 和结构化数据的业务 `event` 帧。
5. 下发 `e2e.fail` 与 `e2e.slow`，断言各自得到失败回执；随后再次下发 `e2e.echo`，必须成功，证明失败/超时未中断桥读循环和后续业务派发。

该测试验证 FR-09 的业务 agent、桥协议、命令路由、回执、事件和事故域隔离。它**不**替代 MultiCurrencyEconomy / AllinInventorySync 的真实插件联调；两者继续由已有 Provider / Envelope 单元测试覆盖，后续如需真插件 E2E 应另立规格。

## 验收与验证

| 项目 | 命令 / 证据 | 通过条件 |
|---|---|---|
| FR-08 单元测试 | `./gradlew :project:core:test` | 安装、拒绝重复安装、卸载回退与陈旧句柄保护通过。 |
| FR-08 E2E | `./gradlew e2eReadApi`、`e2eStorageSpi` | 已通过：两个结果文件均为 `status=PASS`，真实 Paper 已干净退出。 |
| FR-09 协议 E2E | `./gradlew e2eBridgeFixture` | 已通过：fixture 收齐 hello/welcome、manifest、成功/失败/超时回执、业务事件和超时后的成功回执；结果文件为 `PASS`。 |
| 全量回归 | `./gradlew build` | 已通过：IoC / detekt 无错误。 |

## 依赖与前置条件

- JDK 17；mc-testkit 首次运行需要网络下载 Paper，或经环境变量提供本地 Paper jar。
- fixture 只绑定回环地址、使用运行期生成的测试凭据，不读取或写入任何生产数据。
- 不需要 JianManager、MySQL、Redis、MultiCurrencyEconomy 或 AllinInventorySync 作为此规格的运行前置条件。

## 任务

1. 接入 mc-testkit、复制 v0.5.1 模板、构建独立 harness 与 FR-08 失败场景。
2. 设计并实现 FR-08 存储扩展门面、委派存储及其单元/E2E 测试。
3. 实现 FR-09 协议 fixture、`e2e` 测试 Provider 与状态机断言。
4. 运行全部验证，回写 PRD / API / CHANGELOG 的验收状态与证据。已完成。
