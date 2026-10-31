# 功能规格：MCP over Bridge（托管模式下的事故诊断通道）

> 状态：开发中（Paper 1.20.1 真机 E2E 通过）　·　关联 PRD：FR-47
> 探针侧实现：`McpBridgeService` / `McpBridgeEndpoint`（接到 `GovernanceRoute` 的 core 内置动作）
> 本文是**给控制面/上游的对接说明**：帧长什么样、什么时候能调、哪些坑必须处理。

## 1. 为什么有这条通道

托管模式为了兑现“本地零监听端口”，会把 `metrics` / `web` / `mcp` 三个本地监听面按模式强关。
前两个有替代（指标走 WS 推送、面板由控制面渲染），**事故诊断（MCP + Arthas）没有替代**——
而一台被托管的机器出事儿时，最需要的恰好就是它。于是把 MCP 工具面挂到**已有的桥连接**上。

## 2. 帧格式

下行（控制面 → 探针）：一条普通 `command` 帧，`payload` 是**完整 JSON-RPC 2.0 请求**。

```jsonc
{
  "type": "command",
  "domain": "core",
  "action": "mcp",
  "requestId": "<控制面自生成，用于对账>",
  "principal": { "operator": "alice", "operatorId": 7, "reason": "线上卡顿排查" },
  "payload": { "jsonrpc": "2.0", "id": 7, "method": "tools/call",
               "params": { "name": "server_status", "arguments": {} } }
}
```

- `payload` 可写**嵌套对象**（推荐）或 `payloadJson` 字符串两种形态，探针都收。
- `principal` 可选；填了就进审计（探针只当字符串标签，不解释控制面的账号体系）。

上行（探针 → 控制面）：普通 `command_result` 帧。

```jsonc
{ "type": "event", "event": "command_result",
  "data": { "requestId": "<原样>", "success": true,
            "output": "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{...}}" } }
```

- `output` 是 **JSON-RPC 响应文本**，控制面要**解析成对象**再回给客户端（不要二次字符串化）。
- 通知类请求（`notifications/initialized`，无 `id`）：`success=true` 且 `output` 为空串。

## 3. 错误分级（别把两者混了）

| 情形 | 桥回执 | 控制面应回给 MCP 客户端 |
|---|---|---|
| 空载荷 / 非 JSON / 超 2 MiB / 解码异常 | `success=false`，中文原因 | 合成 `-32603`（内部错误）+ 原因 |
| `jsonrpc` 不是 `2.0` | `success=true`，`output` 含 `-32600` | 原样透传 |
| 方法未实现 | `success=true`，`output` 含 `-32601` | 原样透传 |
| 工具不存在 / 参数错 | `success=true`，`output` 含 `-32602` | 原样透传 |
| 命令池饱和（并发过高） | `success=false`，“通道饱和,命令未执行” | `-32000` + 建议重试 |

## 4. 什么时候能调（时序与能力发现）

```
插件 onEnable
  └─ 后台线程判定模式（读身份文件 / 迁移 bridge 段）
       ├─ BridgeClient：按身份文件建反向 WS → 发 hello{managed:true, capabilities:[...]}
       └─ McpBridgeService：等同一判定 → 仅 MANAGED 且 mcp.enabled=true 才
            拿桥用分发器 → 注册 core.mcp 动作 → 声明 mcp_bridge 能力位
```

**关键点**：hello 是“连上那一刻”的快照，MCP 挂桥发生在判定之后，两者有毫秒级先后差。
所以**不要只看首个 hello**。正确做法：连上后调一次 `core.bridge_status`，读它回执里的
`capabilities`（查询时刻的活值）：

```jsonc
// 下行
{ "type": "command", "domain": "core", "action": "bridge_status", "requestId": "cap-1" }
// 回执 data.output（省略无关字段）
{ "capabilities": ["bridge_status", "chunk", "topic", "mcp_bridge"], "version": "2", ... }
```

没看到 `mcp_bridge` 的三种可能：老版本探针 / 独立模式 / 托管但 `mcp.enabled=false`。

## 5. 必须处理的三件事

### 5.1 帧分片重组

单帧 > `max-frame-bytes`（默认 262144）会被切为 `event=chunk` 帧（单组最多 64 片 ≈ 16 MiB）：

```jsonc
{ "type": "event", "event": "chunk", "groupId": "...", "seq": 0, "total": 3,
  "ofType": "command_result", "data": "<原始帧字节的 base64 分片>" }
```

按 `groupId` 收集 `seq`，齐了把 `data` 解 base64 拼回原始帧字符串，再当 `command_result` 处理。
**`tools/list` 很容易超 256 KiB，不做重组必然收不全。**

### 5.2 限流

单实例命令池：`cmd-pool-size: 4` + `cmd-queue-capacity: 64`。控制面对一个实例的并发建议 ≤ 4；
饱和时会收到“通道饱和”失败回执，请转成 `-32000` 并退避重试，**不要把它当成探针挂了**。

### 5.3 长任务与大产物

- **长任务**（Arthas `watch`/`trace` 等）：工具本身就返回 `taskId`，用对应的 `*_status` / `*_output`
  工具轮询；不要用长超时死等一条回执（会占住命令池线程）。
- **大产物**（堆 dump / GC JFR / 火焰图）：用 `artifact_read_binary`，按 `offset` + `maxBytes` 分页拿
  Base64，循环到 `truncated=false` 再拼成文件。

## 6. 参考壳子（Node，~40 行）

```js
// 一个最小的“MCP → 桥”转发：假设 bridge 已连、sendCommand(frame) 返回 Promise<command_result>
const PENDING = new Map();
let seq = 0;

bridge.on('command_result', (frame) => {
  const { requestId, success, output, error } = frame.data;
  const pending = PENDING.get(requestId);
  if (!pending) return;
  PENDING.delete(requestId);
  if (!success) return pending.reject(Object.assign(new Error(error), { code: -32603 }));
  pending.resolve(output ? JSON.parse(output) : undefined);
});

async function callProbe(instance, jsonRpcRequest) {
  const requestId = `mcp-${instance}-${++seq}`;
  const reply = new Promise((resolve, reject) => PENDING.set(requestId, { resolve, reject }));
  bridge.send(instance, {
    type: 'command', domain: 'core', action: 'mcp', requestId,
    principal: { operator: jsonRpcRequest._operator },   // 审计用，可省
    payload: jsonRpcRequest,                             // 嵌套对象形态
  });
  return reply;
}

// 提供给 MCP 客户端：tools/call → callProbe(instance, {jsonrpc:'2.0', id, method:'tools/call', params})
// tools/list → 对每台探针分别调用后按实例前缀合并（每台工具集不同，不要共用一份）
```

## 7. 断线与回退

桥断 = MCP 不可用（**没有本地端口可退**，这是零监听承诺的代价）。壳子对客户端返回
“probe offline / 稍后重试”而不是挂起；探针重连后重新查 `bridge_status.capabilities` 自动恢复。
独立模式的探针没有这条通道，仍走它自己的本地 `mcp.enabled` 控制的 HTTP 端点。
