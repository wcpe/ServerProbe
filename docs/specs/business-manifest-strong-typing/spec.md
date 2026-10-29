# ServerProbe FR-38 / FR-39 manifest 强类型化与分发契约

> 状态：**已实现，待发版登记**（实现与单测完成，真机 E2E 已通过） · 优先级：P1 · 依赖：FR-37（业务自注册已交付）

> 关联：JM FR-534（插件面板）、FR-535（风险分级）

## 1. 背景

当前契约过于粗糙：

```java
String domain();
Map<String, Object> manifest();                    // 裸 Map，无规格约束
BridgeCommandResult dispatch(String action, String payloadJson);  // 字符串进出
```

问题：

- `manifest()` 返回裸 `Map`，下游无法判断内容是否合法，也无法据此渲染表单
- 没有动作级参数规格，业务台只能硬编码每个动作的入参
- 没有风险声明，JM 无法判断哪些操作该强制二次确认
- `BridgeCommandResult` 只有 `success/output/error`，业务台无法按错误类型分流

## 2. 目标

- manifest 强类型化，可被下游可靠消费
- 动作规格化：名称、风险、幂等性、入参/出参 schema、错误码
- 分发入参与结果强类型化
- **保持向后兼容**：旧 Provider（返回裸 Map）仍能注册

## 3. 非目标

- 不引入具体业务域语义（api 包只放跨域通用词汇）
- 不做 schema 的运行时强制校验（由 Provider 自行决定是否校验）
- 不改变现有桥帧格式

## 4. 类型定义

### 4.1 核心接口

```java
package top.wcpe.mc.plugin.serverprobe.api.bridge;

public interface BusinessProvider {

    /** 业务域标识：小写字母/数字/连字符，1-32 字符，全局唯一。 */
    String domain();

    /** 能力清单。 */
    DomainManifest manifest();

    /** 执行动作。 */
    ActionResult dispatch(ActionRequest request);

    /** 健康状态（默认未知）。 */
    default ProviderHealth health() { return ProviderHealth.unknown(); }
}
```

### 4.2 DomainManifest

```java
public record DomainManifest(
    int apiVersion,                 // manifest 协议版本，当前 = 1
    String domain,
    String displayName,             // 供下游展示
    String description,
    List<ActionSpec> actions,
    Map<String, String> attributes  // 扩展位：平台不解释，原样透传
) {}
```

**`attributes` 是刻意的逃生舱**：业务特有信息放这里，探针与 JM 都不解释。

### 4.3 ActionSpec

```java
public record ActionSpec(
    String name,                  // 动作名
    String description,
    RiskLevel risk,               // READ / WRITE / DESTRUCTIVE
    boolean idempotent,           // 是否支持 requestId 幂等
    String inputSchema,           // JSON Schema 文本，可为 null
    String outputSchema,          // JSON Schema 文本，可为 null
    Set<String> errorCodes        // 该动作可能返回的业务错误码
) {}

public enum RiskLevel {
    READ,          // 只读，无副作用
    WRITE,         // 有副作用，可重复执行
    DESTRUCTIVE    // 有副作用且不可逆（如删除、清空、结算发放）
}
```

**`risk` 的用途**：JM 据此决定是否强制二次确认（见 JM FR-535）。**Provider 必须诚实声明**——标 `READ` 却写数据会让风险控制失效。

### 4.4 ActionRequest

```java
public record ActionRequest(
    String domain,
    String action,
    String payloadJson,
    String requestId,     // 平台保证非空，Provider 据此去重
    Principal principal   // 操作者上下文
) {}

public record Principal(
    String operator,      // 用户名
    long   operatorId,    // 用户 ID
    String nodeId,        // 节点 UUID
    String reason         // 操作原因（高风险操作必填）
) {}
```

**`requestId` 恒非空**：平台在下发前保证。Provider 若声明 `idempotent=true`，必须用它做本地去重。

### 4.5 ActionResult

```java
public record ActionResult(
    boolean success,
    String outputJson,        // 成功时的输出（JSON 文本）
    String errorCode,         // 机器可读错误码，如 PLAYER_NOT_FOUND
    String errorMessage,      // 人可读消息
    Map<String, String> metadata  // 诊断信息：耗时、影响行数等
) {
    public static ActionResult ok(String outputJson) { ... }
    public static ActionResult fail(String code, String message) { ... }
}
```

**`errorCode` 必须在 manifest 声明的 `errorCodes` 范围内**，否则一致性校验会报警。

## 5. 向后兼容

### 5.1 旧 Provider 的处理

现有 Provider 可能仍实现旧签名。迁移策略：

1. 提供 `LegacyBusinessProviderAdapter`，把旧 `Map<String,Object> manifest()` 适配为 `DomainManifest`
2. 适配规则：动作名从 `manifest().get("actions")` 提取，`risk` 默认 `WRITE`，`idempotent` 默认 `false`，schema 为 `null`
3. 聚合 manifest 时，旧 Provider 的域标记 `specDeclared=false`

**为什么默认 `WRITE` 而不是 `READ`**：安全优先。未声明风险的动作用户必须确认。

### 5.2 版本协商

- `DomainManifest.apiVersion` = 1
- 探针在聚合 manifest 时检查版本，不匹配则**降级为 legacy 适配**并记 WARN，不静默失败
- 未来 apiVersion 升级时，探针同时支持 N 和 N-1

## 6. Manifest 聚合

`BusinessHost.manifest()` 返回：

```json
{
  "apiVersion": 1,
  "domains": {
    "activity": {
      "displayName": "活动系统",
      "description": "...",
      "specDeclared": true,
      "actions": [
        {
          "name": "start",
          "risk": "WRITE",
          "idempotent": true,
          "inputSchema": "{...}",
          "errorCodes": ["ACTIVITY_NOT_FOUND", "ALREADY_RUNNING"]
        }
      ],
      "attributes": {}
    }
  }
}
```

## 7. 校验规则

探针在**注册时**校验：

| 校验项 | 失败行为 |
|---|---|
| `domain` 格式（小写/数字/连字符，1-32） | 拒绝注册，记 ERROR |
| `apiVersion` 不支持 | 降级为 legacy 适配，记 WARN |
| 动作名重复 | 拒绝注册 |
| `risk` 为 null | 默认 `WRITE`，记 WARN |
| `errorCodes` 为空 | 允许（表示未声明） |

## 8. 验收

1. 新 Provider 实现强类型接口，注册成功，manifest 正确暴露
2. 旧 Provider（裸 Map）注册成功，被适配为 `specDeclared=false`
3. `apiVersion` 不匹配时降级而非拒绝
4. 动作名重复时拒绝注册并记错误
5. `requestId` 在下发时恒非空
6. `Principal` 各字段正确透传
7. `errorCode` 不在声明范围内时有告警

## 9. 待定

1. `inputSchema` 是否需要探针做强制校验（当前只透传，由 Provider 自校验）
2. 是否需要「动作别名」（一个动作多个名字）
3. 是否支持动作级权限声明（`requiredPermission`）
