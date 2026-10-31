# 功能规格：托管模式（厂商中立的外部控制面接入）

> 状态：开发中（Paper 1.20.1 真机 E2E 已通过）　·　关联 PRD：FR-42 / FR-43 / FR-44 / FR-45 / FR-46
> 协议权威定义：`JianManager docs/specs/probe-managed-mode/spec.md`（本文引用，不复制帧定义）

## 1. 背景与目标

ServerProbe 目前是一个**纯独立产品**：所有行为由本地 `config.yml` 决定，可选地经反向 WS 连入一个外部控制面（首个对接方是 JianManager）上报事件、接收治理指令。

问题：探针有 66 个配置项分散在多台服务器上，批量管理与一致性维护成本很高；且只要改动配置就必须重启宿主实例。

本规格给探针增加一个**可选的托管模式**：由外部控制面远程管理探针自身配置，运行期热生效，无需重启。

**核心设计约束：这是通用能力，不是 JianManager 专属。** ServerProbe 未来要兼容多种控制面产品，因此：

- 任何厂商标识都只能是**数据**，不得成为代码分支。
- 凭证对探针**不透明** —— 探针不解析其格式与内部字段。
- 托管能力本身属于探针的核心能力集，与"谁来驱动"无关。

## 2. 需求（要什么）

### 范围内

- 身份文件作为托管模式开关（存在即托管，删除即回退）。
- 托管配置经既有反向 WS 下行，运行期热应用。
- 配置键三档分级，禁止档结构性拒绝。
- 指标经 WS 推送，托管模式下零监听端口。
- 存量部署自动迁移。
- 状态上报（版本 / 生效值 / 连接态）。

### 不做（范围外）

- **不管理游戏插件配置**（BattlePass / IncentiveEngine 等）—— 那是控制面与插件之间的事，与探针无关。
- 不托管 bootstrap 键（`endpoint` / `instanceId` / `credential`）。
- 不实现自动回滚到更早历史版本（只回退到"上一版"，即应用失败时的事务性回退）。
- 不删除独立模式的 `/metrics` HTTP 监听（供非托管场景与兜底）。
- 不引入任何第三方依赖（JDK 8 + 零依赖红线不变）。

## 3. 设计

### 3.1 模块落位

```
project:core
├── control/                          ← 新增：控制面无关的托管能力
│   ├── ControlPlaneIdentity          身份文件模型 + 原子读写
│   ├── ManagedConfigStore            托管缓存 + 版本管理
│   ├── ManagedConfigResolver         配置源抽象（托管缓存 vs config.yml）
│   └── ConfigTier                    三档分级与校验
├── config/ProbeConfig                改造：改为经 resolver 取值（66 键）
├── bridge/BridgeClient               扩展：新帧路由
└── metrics/                          扩展：metric_snapshot 产出

integration:integration-<厂商>         ← 仅当出现厂商特有逻辑时新建
                                        （首个对接方当前无需此类模块）
```

**评审约定（不是构建门禁）**：`api` 与 `project:core` 模块中不得出现任何控制面厂商标识（大小写不敏感）。厂商名只允许出现在文档与 integration 模块。
该约束由代码评审保证——**不使用“扫描源码文本”的构建任务/CI 步骤来卡**（理由见 `.claude/rules/static-analysis.md` §4）。

### 3.2 身份文件

路径：`plugins/ServerProbe/agent-identity.json`

字段与语义见协议规格 §3.2。本文只重申三条实现约束：

1. **写入必须原子**：`tmp` → `fsync` → `rename`。
2. **读取失败视为不存在**：解析异常 → 独立模式 + WARN，绝不因此启动失败。
3. **`credential` 不透明**：代码里不得出现任何对其内部结构的解析。

### 3.3 配置源抽象

`ProbeConfig` 现有 66 个键全部改为经 `ManagedConfigResolver` 取值：

```
取值顺序（托管模式）：
  1. ManagedConfigStore 中的当前生效值
  2. 托管缓存中上一次的值
  3. config.yml
  4. 代码内默认值

取值顺序（独立模式）：
  1. config.yml
  2. 代码内默认值
```

**关键**：独立模式下的行为必须与改造前**逐字节一致**。这是回归断言，不是"差不多就行"。

### 3.4 热应用与自检

```
config_push 到达
  ├─ 版本校验（单调 / baseVersion 乐观并发）
  ├─ 档位校验（FORBIDDEN 整包拒绝 / GUARDED 越界拒绝）
  ├─ 应用：后台线程原子替换内存值
  ├─ 自检三项：采集器在跑 / 指标可产 / 桥可发帧
  ├─ 全通过 → 原子写托管缓存 → 回 config_applied(applied, effective)
  └─ 任一失败 → 内存回退上一版 → 回 config_applied(failed, error)
```

**线程约束**：整个流程在桥的后台线程完成，**绝不触碰 MC 主线程**。这是既有红线（`BridgeClient` 线程模型），新代码必须遵守。

### 3.5 指标推送

- 周期推送 `metric_snapshot`，间隔由 SAFE 档键控制（默认 5s，下限见协议规格 §3.3）。
- 收到 `metrics_now` 立即推一帧 + 回 `command_result`。
- 推送内容直接从**现有指标采集管线**取，不新建采集逻辑，保证与 `/metrics` 逐字段一致。
- 托管模式下不启动 HTTP 监听；独立模式维持现状。
- **只在托管模式启用**：独立模式的桥只承载业务转发，不推指标也不声明 `metric_push` 能力位；旧 Worker 因此回退到 HTTP 拉取 `/metrics`（独立模式下该端点情绪不变）。装配先等模式判定，判定完成前不起推送线程。

### 3.6 自动迁移

触发条件：**无身份文件** 且 `config.yml` 含 `bridge` 段。

```
1. 读 bridge 段（url / instance / token）
2. 任一缺失 → 不迁移，保持独立模式 + WARN（不猜、不兜底）
3. 原子写 agent-identity.json
4. 备份 config.yml → config.yml.bak.<yyyyMMddHHmmss>
5. 原子写回 config.yml（移除 bridge 段）
6. 上报迁移事件
```

幂等性：步骤 3 内容相同则跳过写；步骤 4 同名备份已存在则跳过；步骤 5 无 bridge 段则跳过。任意时刻崩溃后重跑结果一致。

### 3.7 状态上报

周期上行 `config_state`，字段见协议规格 §3.4.3。

**两种失败语义必须区分**：

| 情况 | `connection` | 行为 |
|---|---|---|
| 网络不可达 | `degraded` | 用托管缓存继续跑 |
| 控制面拒绝凭据（401/403） | `unauthorized` | **不降级到文件配置**，停止重试并本地告警 |

理由：已被认定为"托管中"的探针若偷偷按旧文件跑，会造成认知分裂 —— 控制面以为托管着，实际行为却是另一套。

### 3.8 MCP 经桥（托管模式下的事故诊断通道）

零监听承诺关断了 metrics / web / mcp 三个本地监听面。前两者有替代（指标走推送、面板由控制面渲染），
**事故诊断（MCP + Arthas）此前没有替代** —— 一台被托管的机器出事时，最需要的恰好是它。

因此托管模式把 MCP 工具面挂到**已有的桥连接**上：

```
控制面 ── command(domain=core, action=mcp, payload=<JSON-RPC 2.0 请求>) ──▶ 探针
控制面 ◀── command_result(requestId 原样, output=<JSON-RPC 响应>) ───────── 探针
```

- **整包透传 JSON-RPC**：`initialize` / `ping` / `tools/list` / `tools/call` 均由现有分发器处理，
  新增工具不需要改桥协议，也不会出现“桥的命令集”与“MCP 工具集”两张能力表各自漂移。
- **错误分级**：传输/格式问题（载荷为空、非 JSON、超 2 MiB、解码异常）→ 桥 `fail(中文原因)`；
  协议层问题（版本不符、方法未实现、工具不存在）→ 桥 `ok` + JSON-RPC `error` 对象保留 `id` 与 `error.code`。
- **与本地端点同装配**：工具面、工件工作区（堆 dump / GC JFR 等大产物）与审计轨迹共用一份，
  所以“经桥调”与“经本地 HTTP 调”行为一致；大回执由既有帧分片承载。
- **开关语义不变、只换传输**：`mcp.enabled` 仍决定“是否启用 MCP 控制面”，传输方式才按模式决定（独立=本地 HTTP，托管=插件桥）。两个方向都不新增绕过口：
  独立模式下不开 `mcp.enabled` 就没通道；托管模式下同样要 `mcp.enabled=true` 才挂桥，且该键属禁止档、不能远程开启。
- **只在托管模式挂载**：独立模式仍只保留 `mcp.enabled` 控制的本地端点。挂载成功后 hello 才声明 `mcp_bridge` 能力位。
- **能力位只在挂载成功后声明**，且以 `core.bridge_status` 的 `capabilities` 为准：`hello` 是连接瞬时的快照，
  而 MCP 挂桥发生在模式判定之后，可能与 hello 存在毫秒级先后差；控制面查一次 `bridge_status` 就能拿到
  当前活值（包含 `mcp_bridge`），不必重连或猜时机。
- **权限语义**：桥的持有凭据方本来就已被授权管理这台探针（可下发配置、执行平台治理指令），
  MCP 不引入新的信任边界；审计轨迹记录来源为 `bridge`，下行帧带 `principal` 时记为 `bridge:<operator>`（探针只当字符串标签，不解释控制面账号体系）。

## 4. 任务拆分

- [x] FR-42 `ControlPlaneIdentity` + 状态机 + 自动迁移
- [x] FR-43 `ManagedConfigResolver` + `ConfigTier` + `ProbeConfig` 66 键改造
- [x] FR-44 `config_push` 处理 + 自检 + 回退 + `ManagedConfigStore`
- [x] FR-45 `config_state` 周期上报
- [x] FR-46 `metric_snapshot` 推送 + `metrics_now` + 托管模式停用 HTTP 监听
- [x] FR-47 MCP 经桥（`core.mcp` 透传 JSON-RPC，零监听下保住事故诊断）
- [x] 真机 E2E：`managed-config` 场景（hello 托管位 / 配置下发 / 指标推送 / 状态上报 / 按需推送 / 零监听）
- [ ] 文档同步：ARCHITECTURE、CHANGELOG、PRD 状态
- [ ] 真机矩阵：Paper 1.8/1.21 × Windows/Linux + BungeeCord + Velocity

## 5. 验收标准

1. **独立模式零回归**：无身份文件时全部 66 键取值与改造前一致（逐键断言）。
2. **厂商标识隔离**：`api` / `project:core` 中不出现控制面厂商名（**评审保证**；不用源码扫描做门禁，见 `.claude/rules/static-analysis.md` §4）。
3. 有身份文件 → 用托管缓存秒起，控制面不在场可启动。
4. 自动迁移幂等：中途 kill 后重跑结果一致，备份文件名带时间戳。
5. 截断的身份文件 → 独立模式 + WARN，不崩。
6. 托管模式**无任何监听端口**（`ss -ltnp` / `netstat -ano` 核验）。
7. 推送指标与原 `/metrics` 解析**逐字段一致**。
8. 主线程零阻塞：应用配置期间 TPS 无可见抖动。
9. 自检失败自动回退，且缓存不被污染。
10. **真机矩阵**：Paper 1.8 / 1.21 × Windows / Linux + BungeeCord + Velocity。

## 6. 风险 / 待定

| 项 | 说明 |
|---|---|
| 零依赖红线 | 新帧解析必须手写，不得引入 JSON 库 |
| 66 键改造面大 | 用逐键回归断言兜底；独立模式行为不变是硬门槛 |
| 托管模式无兜底 | 桥断即无指标（已与对端确认接受）；独立模式保留 HTTP |
| 待定 | `credential` 轮换是热切换还是重启生效（当前按重启生效设计） |
| 待定 | 是否需要在托管模式下也保留 HTTP 监听作为可选兜底（当前：不保留） |
