# 功能规格：移除内置业务集成，改为第三方自注册

> 状态：**已实现，待发版登记**（实现与单测完成，真机 E2E 已通过） · 关联 PRD：FR-37（取代 FR-10）　·　分支：feature/business-self-registration


## 1. 背景与目标

当前仓库内置两个业务集成：`integration-multicurrencyeconomy`（经济域）与
`integration-allininventorysync`（背包域），共 2212 行（含测试），随 `ServerProbe.jar` 打包。
它们把 MCE/AIS 的 API、生命周期、故障边界全部收进探针仓库，导致：

- 每新增一个业务（活动、排行榜、任务…）都要改 ServerProbe 源码并重新发版；
- 探针必须跟随每个第三方插件的 API 演进而维护，MCE `1.2.0`、AIS `2.1.0-SNAPSHOT` 一旦变更就腐烂；
- 「业务面板 ↔ 业务插件」这条通路上多了一层不必要的中间实现。

本功能把内置实现**彻底移除**，让 ServerProbe 只做**路由中介**：

```
JianManager 业务面板
        │  下发 command（domain / action / payload）
        ▼
   ServerProbe 业务桥（只做路由、隔离、超时、回执）
        ▲                    │
        │  自注册 Provider    │  dispatch
        │                    ▼
  第三方业务插件（MCE / AIS / 活动 / 排行榜 …）
```

探针从此**不认识任何具体业务**：不 import 任何第三方 API，不定义任何具体域的语义，
只转发。新增业务不需要改 ServerProbe。

**为什么 `api` 必须零第三方绑定**：`api` 是对外发布的 Maven 制品（`serverprobe-api`），
一旦把某个第三方插件的私有字段名或序列化格式写进去，就变成"探针承诺长期支持这个格式"——
上游一改格式，探针就得跟版；更糟的是第三方会把它当成稳定契约来依赖，从而无法演进。
因此 `api` 只保留"换一个业务域依然成立"的通用词汇，具体协议由各插件自己的 manifest 声明。

## 2. 需求

### 范围内

- **删除** `integration/integration-multicurrencyeconomy`、`integration/integration-allininventorysync`
  两个 Gradle 模块及其全部源码、测试、构建脚本。
- 清理所有引用：`settings.gradle.kts`、`plugin/build.gradle.kts`（`taboo(...)` 依赖）、
  `build-logic/src/main/kotlin/serverprobe.bundle.gradle.kts`（模块列表与**必存在类校验**）、
  `build-logic/src/main/kotlin/serverprobe.e2e-verification.gradle.kts`（41 处：集成 backend、
  4 个 scenario、cleanup/prepare 任务、`IntegrationsPluginSet`、真实插件配置注入）。
- 删除对应 E2E：`e2e/harness/.../IntegrationsConsumer.kt`、`integrations-*` 四个场景、
  `docs/specs/built-in-integrations.md`。
- **新增 `api` 协议包**：只放**跨域通用**词汇——**探针真实帧里存在的字段**
  （`requestId`/`success`/`output`/`error`/`dedupKey`/`domain`）+ 约定词 `taskId`，
  以及 `ActionSpec` 动作声明容器；**不提取任何具体域的动作名、参数名、结果字段名**（见 §3.2 硬边界）。
- **E2E 改为 harness 模拟第三方**：harness 自定义域自注册，验证「面板下发 → 路由 → 回执 → 事件上行」全链路。
- 新 ADR-30 取代 ADR-0016 / ADR-0017 / ADR-0018。
- PRD 新增 FR-37，显式写明**取代 FR-10**（FR-10 历史记录保留不动）。

### 范围外

- 不改 JM 侧的领域协议（`domain=economy` / `inventory` 的字段名与语义保持不变）。
- 不实现任何具体业务域（探针不再包含经济/背包逻辑）。
- 不为第三方提供 MCE/AIS 的 API 封装或适配层。
- **不搬运、不记录、不维护任何具体业务域的协议说明**（含字段映射、动作清单、载荷格式）。
- 不迁移到独立仓库（直接删除；历史可从 git 追溯）。

### 前置依赖（硬约束）

**本功能必须在 FR-30（桥对外契约与外部注册，见 [bridge-external-api](bridge-external-api.md)）交付之后实施。**

理由：被删的两个内置模块正是通过 `BusinessHost.register()` 挂进桥的。若先删内置实现，
在一段时间内「既没有内置业务、也没有外部接入路径」，`economy`/`inventory` 域直接归零，
且没有任何办法补回。因此顺序固定为：

```
FR-30（自注册门面可用） → 本功能（删内置实现）
```

同时依赖 FR-31（线路治理）提供的 `bridge_status` 作为排查入口（见风险 6），
但不阻塞实施——`bridge_status` 的缺失只影响可观测性，不影响功能正确性。
本功能与 FR-32/28/29/30/31 无文件交集，可独立成批。

## 3. 设计

### 3.1 删除范围与依赖清理

| 位置 | 处理 |
|---|---|
| `integration/integration-multicurrencyeconomy/**` | 整目录删除 |
| `integration/integration-allininventorysync/**` | 整目录删除 |
| `integration/`（空目录） | 一并删除，避免留下无模块的悬空目录 |
| `settings.gradle.kts` L64-65 | 删除两条 `include` |
| `plugin/build.gradle.kts` L69-70 | 删除两条 `taboo(project(...))` |
| `serverprobe.bundle.gradle.kts` L122-123 | 删除模块列表两项 |
| `serverprobe.bundle.gradle.kts` L265-270 | 删除 `required` 集合里两个 `*Provider.class`。**这是「必须存在」校验**（`check(missing.isEmpty())`）且挂进 `check`，不删则删完模块后构建立即失败。删后 `required` 为空 → 该任务退化为空断言，需**重命名并改描述**为「发行 jar 不得包含内置业务实现」，保留原有 `forbidden`（外部 API 包路径）断言 |
| `serverprobe.bundle.gradle.kts` L300 | `verifyVelocityDescriptorDependencies` 里 `check("MultiCurrencyEconomy" !in dependencyIds && "AllinInventorySync" !in dependencyIds)` 硬编码两个业务名——保留该断言仍可（它是正向防护），但需确认不会误伤；若保留则列入**保留项**并说明理由 |
| `serverprobe.e2e-verification.gradle.kts` | **41 处 `ntegration` 字面量**：`backend("paper-integrations")`、4 个 `scenario("integrations-*")`、`cleanupIntegrationsLibraries`、`prepareIntegrationsRuntime` / `writeIntegrationsConfigurations`、`IntegrationsPluginSet`、`prepareIntegrationsBoth/Single/...` |
| 同上 L514-607 | **MCE 离线运行时闭包（约 200 行，不含 `ntegration` 字样，极易漏删）**：`OfflineMavenArtifact`、`offlineTabooRuntimeDirectory`、`prepareOfflineTabooRuntime`、`copyMceRuntimeClosure`、`verifyOfflineMceRuntimeClosure`、`copyOfflineRuntimeLibraries`。`prepareIntegrationsRuntime` 是它们唯一消费者，删后即死代码 |
| 同上 L802-803 | 两条 bot 接线 `wireE2eDependency("e2eIntegrationsBoth","launchIntegrationsBothBot")` 与 `...AisOnly...`。注：`launchIntegrations*Bot` **全仓无定义**（既存悬空引用），借本次一并清理 |
| `e2e/templates/configs/integrations/**` | **整目录删除**（4 个 config.yml：`CoreLib` / `ServerProbe` / `MultiCurrencyEconomy` / `AllinInventorySync`）。原有 `installE2eConfig` 从该目录读模板且 `check(template.isFile)` 缺失即失败 |
| `e2e/harness/**/IntegrationsConsumer.kt` | 删除 |
| `e2e/harness/src/test/**/IntegrationsConsumerTest.kt` | **必须一并删除**——它测的 `sameAmount(...)` 定义在被删的 `IntegrationsConsumer.kt` 里，不删则 `:e2e:harness:test` 编译失败 |
| `e2e/harness/**/ProtocolWorkerFixture.kt` | 移除 AIS 专用分支：`"inventory_change"` 处理、`handleInventoryEvent`、`INVENTORY_DOMAIN` 常量 |
| `e2e/harness/**/ServerProbeE2eHarnessPlugin.kt` | 移除 integrations 场景装配；注册路径从反射 `BeanContainer` 改为 `ServerProbeAPI`（见 §3.4 前置改造） |
| `docs/specs/built-in-integrations.md` | 删除（其历史由 ADR-30 + CHANGELOG 承载） |
| `docs/specs/open-api-bridge-e2e.md` | 更新：移除 L28/L68/L83 三处内置集成引用 |
| `docs/ARCHITECTURE.md` | 删除模块表 L50-51 两行、L56「新增平台、集成和诊断模块」措辞、依赖方向说明里的 `integration-*` 层；**新增 ADR-30 到 L229 的 ADR 总表** |
| `docs/adr/README.md` | 新增 ADR-30 行到索引表 |
| `docs/adr/0016` / `0017` / `0018` | 状态改为「已被 ADR-30 取代」 |
| `docs/PRD.md` L80 | FR-10 的 spec 链接指向将被删除的 `built-in-integrations.md` → 改为指向 ADR-0018/ADR-30 或纯文字（**不改则「文档无悬空」验收必红**） |
| `docs/PRD.md` L128 | FR-10 验收状态行含「内置集成…E2E 通过」→ 保留历史表述但补一句「已由 FR-37 移除」 |
| `README.md` L99 | 删除 `e2eIntegrationsBoth` 与 `SERVERPROBE_E2E_CORELIB_JAR/MCE_JAR/AIS_JAR` 说明 |
| `docs/wiki/Installation.md` L47 | 同上 |
| `.claude/rules/architecture-invariants.md` L8 | 依赖方向硬规则含 `integration-*` → 改为移除该项（**项目强制规则，优先级高于普通文档**） |
| `.github/workflows/e2e.yml` L106 | 注释引用 `cleanupIntegrationsLibraries` → 更新注释 |
| `CHANGELOG.md` | **历史条目一律不改**（那是历史事实）；仅在最新段新增「破坏性变更」条目说明移除与正确升级顺序（见 §6.3） |

**全仓引用已核实**（`grep -rn "integration-|EconomyProvider|InventoryProvider|ntegration|MCE_JAR|AIS_JAR|offlineMce"`）：
上表覆盖全部命中点。**已确认 `e2e-bot/**` 与 `ScenarioResultWriter.kt` 无引用**，无需处理。

### 3.2 `api` 协议包：只放"协议骨架"，不放第三方私有格式

> **位置与依赖边**：新建 `api.protocol` 包会引入 `api.bridge → api.protocol` 的新边，而
> [bridge-external-api.md §3.4](bridge-external-api.md) 写死「api 内部包依赖仅此三条」。
> 因此本 spec 同步修订那份白名单，新增**单向**边 `api.bridge → api.protocol`，
> 且 `api.protocol` **不得**反向引用 `api.bridge`（javadoc 里只写纯文字名称，不用 `{@link}`）。

新增 `top.wcpe.mc.plugin.serverprobe.api.protocol`（纯 Java 8，零依赖）：

```java
package top.wcpe.mc.plugin.serverprobe.api.protocol;

/**
 * 业务桥协议的**通用**字段名约定。
 *
 * <p>只收录与具体业务无关、任何域都可能用到的字段名。这些是 JianManager 面板与业务插件之间的
 * 公共词汇；探针不解释、不校验业务语义，只承载与转发。</p>
 *
 * <p><b>取值必须与探针真实产出的帧一致</b>——本类不做「希望有的字段」，只做「事实上有的字段」。
 * 当前回执帧（core `commandResultJson`）与业务事件帧（core `businessEventJson`）的字段是唯一依据。</p>
 *
 * <p><b>本类刻意不包含任何具体业务域的字段名</b>（金额、货币、物品、属性、任何序列化载荷结构）：
 * 那些由各插件在自己的 manifest 中声明，探针既不知道也不关心。</p>
 */
public final class BusinessProtocol {

    /** 命令回执帧 `data` 内的关联标识。与 core 回执帧一致。 */
    public static final String FIELD_REQUEST_ID = "requestId";

    /** 命令回执帧 `data` 内的成功标志（布尔）。与 core 回执帧一致。 */
    public static final String FIELD_SUCCESS = "success";

    /** 命令回执帧 `data` 内的成功输出（字符串）。与 core 回执帧一致。 */
    public static final String FIELD_OUTPUT = "output";

    /** 命令回执帧 `data` 内的失败原因（字符串）。与 core 回执帧一致。 */
    public static final String FIELD_ERROR = "error";

    /** 业务事件帧顶层的去重键。与 core 事件帧一致。 */
    public static final String FIELD_DEDUP_KEY = "dedupKey";

    /** 业务事件帧顶层的业务域。与 core 事件帧一致。 */
    public static final String FIELD_DOMAIN = "domain";

    /**
     * 写动作的幂等键字段名——**这是约定，不是探针产出/强制的字段**。
     *
     * <p>探针不生成、不校验它；仅作为「面板与插件之间的公共词汇」供双方对齐。
     * 具体动作是否真的需要它，由插件在自己的 manifest 里声明。</p>
     */
    public static final String FIELD_TASK_ID = "taskId";

    private BusinessProtocol() {}
}
```

**为什么去掉 `errorCode` / `message`**：核实 core 的 `commandResultJson` 只产出
`requestId`/`success`/`output`/`error`。原稿把 `errorCode`/`message` 写成「回执字段名」是把
**不存在的字段**固化进对外制品——第三方照着读只会拿到空值，正是本 spec 想消除的哑失败。
若将来确实需要结构化错误码，必须先在 core 改回执帧格式，届时再加常量。

```java
package top.wcpe.mc.plugin.serverprobe.api.protocol;

import java.util.List;
import java.util.Map;

/**
 * 一个业务域的动作声明。
 *
 * <p><b>本类不直接进 manifest</b>：manifest 的值必须是 Map/List/String/基本类型，
 * 见下方「序列化约束」。请用 {@link #toMap()} 转换后再放入。</p>
 */
public final class ActionSpec {

    /**
     * @param name     动作名（由插件自定）
     * @param fields   该动作要求的参数名（由插件自定）
     * @param readOnly true=查询，false=写
     */
    public static ActionSpec of(String name, List<String> fields, boolean readOnly);

    public String getName();

    public List<String> getFields();

    public boolean getReadOnly();

    /**
     * 转为可直接放进 manifest 的普通 Map。
     *
     * <p>键为 {@code name}/{@code fields}/{@code readOnly}，值为 String/List&lt;String&gt;/Boolean。</p>
     */
    public Map<String, Object> toMap();
}
```

```java
package top.wcpe.mc.plugin.serverprobe.api.protocol;

/**
 * 动作声明的构造辅助（纯便利，不引入任何业务语义）。
 *
 * <p>本类不包含、也不引用任何具体业务域的字段名；`fields` 完全由调用方给出。</p>
 */
public final class Actions {

    /** 构造一个只读动作声明。 */
    public static ActionSpec read(String name, String... fields);

    /**
     * 构造一个写动作声明。
     *
     * <p><b>不自动追加 {@code taskId}</b>：幂等键是否必需、叫什么名字，由插件自行决定并在
     * {@code fields} 里显式列出。探针不替它做这个决定——那属于治理策略，不是通用容器该固化的事。</p>
     */
    public static ActionSpec write(String name, String... fields);
}
```

**序列化约束（必须写进 javadoc，否则 manifest 会静默失效）**：

core 的 JSON 编码走 [ConfigJsonCodec](../../project/core/src/main/kotlin/top/wcpe/mc/plugin/serverprobe/core/json/ConfigJsonCodec.kt)：
顶层 Map 走 `fillSection` 逐键写入，**只递归 `Map`**；其它值（含 POJO）原样 `section.set`。
因此：

- `manifest()` 返回的嵌套值**只能是** `Map`、`List`（元素为 String/数字/布尔/`Map`）、String、数字、布尔。
- `ActionSpec` 必须经 `toMap()` 转换后才能放入 `actions` 列表；直接放实例会导致编码结果不可预期。
- 这条约束同时适用于 FR-30 的 `manifest()` javadoc，需在那份 spec 同步补一句。

**与 FR-30 的口径统一（本 spec 单方面改动，必须显式声明）**：

[bridge-external-api.md](bridge-external-api.md) 的示例里 `actions` 是 `Arrays.asList("progress", "settle")`
（`List<String>`）。本 spec 改为 `List<Map>`（由 `ActionSpec.toMap()` 产出），是为了让面板能拿到
「每个动作要哪些字段、是否只读」——否则面板无法渲染操作表单。

这是**对 FR-30 契约的修订**，须同步改那份 spec 的示例与 §3.2 类型描述，并在 ADR-30 里记一笔。
两者不能各写一套。

**硬边界（写进 ADR-30，不可协商）**：

- `api` 模块**禁止绑定任何第三方私有格式**：不出现第三方插件名、不出现第三方专有字段名
  （金额、货币、物品、属性、任何序列化载荷结构…）、不出现第三方 API 类型。
- 只用编译期检查守住这条：`api` 的依赖树保持零第三方业务坐标；新增任何常量/类需过 code review
  确认「换一个业务域它依然成立吗」。
- 原 `EconomyEnvelope` / `InventoryEnvelope` 的字段映射**随模块一起删除，不在任何地方保留副本**
  （包括文档、示例、测试 fixture）。它们的最终形态只存在于 git 历史。
- 具体域的字段名由各插件的 `manifest()` 自行声明；探针不校验，写错由 JM 侧解析失败暴露。
  **本仓不提供、也不维护任何具体域的协议说明表**。

### 3.3 第三方接入路径（唯一）

下面用**虚构的 `activity` 域**做示例——刻意不用任何真实业务域，避免被误读成本仓约定的协议：

```java
// 某个第三方插件（如活动系统）的 onEnable
public final class ActivityProvider implements BusinessProvider {

    public String domain() { return "activity"; }

    public Map<String, Object> manifest() {
        // 动作名与字段名全部由本插件自定，探针不校验
        List<Map<String, Object>> actions = new ArrayList<Map<String, Object>>();
        actions.add(Actions.read("progress", "playerId", "activityId").toMap());
        actions.add(Actions.write("settle", "activityId", "rewardId").toMap());

        // manifest 的嵌套值只能是 Map/List/String/数字/布尔（见 §3.2 序列化约束）
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("actions", actions);
        return m;
    }

    public BridgeCommandResult dispatch(String action, String payloadJson) {
        // 自行解析 payloadJson，执行本插件业务，返回结构化结果
    }
}
```

产出的 `manifest()` JSON（面板实际收到的形态）：

```json
{
  "actions": [
    {"name": "progress", "fields": ["playerId", "activityId"], "readOnly": true},
    {"name": "settle",   "fields": ["activityId", "rewardId"], "readOnly": false}
  ]
}
```

```java
// 注册与注销
private Registration registration;

public void onEnable() {
    registration = ServerProbeAPI.businessHost().register(new ActivityProvider());
    // 返回失效句柄（探针未就绪）时，可用 awaitAvailable 后重试
}

public void onDisable() {
    if (registration != null) registration.close();
}
```

探针侧不感知任何第三方类型：原来 integration 模块对 MCE/AIS API 的 `compileOnly` 依赖随模块一起消失，
`api` 与 `core` 的依赖树里不再有任何业务插件坐标。插件只依赖 `ServerProbe-api`（`compileOnly`），
而 `api` 不依赖任何业务插件——依赖方向变成单向且干净。

### 3.4 E2E：harness 模拟第三方

**前置改造（不做则 E2E 必失败，且失败方式很隐蔽）**

现状 harness **不具备**调用 `ServerProbeAPI` 的条件，必须先改三处：

| 项 | 现状 | 必须改成 |
|---|---|---|
| 依赖 | `e2e/harness/build.gradle.kts` 无 `:api` | 加 `compileOnly(project(":api"))`。**必须 `compileOnly`**：若用 `implementation`，`tasks.jar` 的 `from(runtimeClasspath)` 会把 api 打进 harness jar，于是 harness 有**自己的一份** `ServerProbeAPI`，静态字段 `services` 与探针那份不是同一实例 → 注册永不生效且**不报错** |
| 类加载 | `plugin.yml` 只有 `loadbefore: ServerProbe` | **保持只声明 `loadbefore`，不加 `softdepend`/`depend`**。理由见下：二者与 `loadbefore` 对同一插件构成依赖环 |
| 注册路径 | `ServerProbeE2eHarnessPlugin` 用 `Proxy.newProxyInstance(探针 loader) + BeanContainer.getBean` 反射 | 改为 `ServerProbeAPI.businessHost().register(...)`。这正是本 spec 要验证的对外路径 |

同时加一条构建断言：**harness jar 内不得存在 `top/wcpe/mc/plugin/serverprobe/api/` 条目**（防误用 `implementation`）。

**为什么不能加 `softdepend`/`depend`（对上一版 spec 的修订）**：本表初版要求加 `softdepend: [ServerProbe]`，
但那与必须保留的 `loadbefore: [ServerProbe]` 对**同一个插件**给出相反顺序（前者"排在探针之后"、
后者"排在探针之前"），在依赖图里直接成环，加载顺序取决于解析器具体的破环策略——不可依赖。
而不加也不会拿不到类：旧式 `plugin.yml` 插件的 `PluginClassLoader` 是**全局可搜**的，
不按声明依赖做可见性门控，所以 `ServerProbeAPI` 仍能解析。
关键在于**解析时机**：`E2eBusinessProvider` 的接口与门面类都是惰性的，首次解析发生在
`ServerLoadEvent`（全部插件已启用）时的 `registerBusinessProvider()`，那时 ServerProbe 早已 loaded/enabled。
因此 harness 的顺序约束（fixture 先绑端口）与"能用探针契约"可以同时成立。

**与既有 `bridge-fixture` 场景的关系**：现有 FR-09 的 `bridge-fixture` 已覆盖
manifest 发现、echo 往返、emit + 业务事件、失败、超时、超时后仍可往返。
本 spec 的 4 个场景**不与它重复**，只补「删除内置集成后新增的故障面」——
但 `self-register-both` 的两域隔离断言需与 `bridge-fixture` 的失败/超时用例错开，避免重复覆盖。

**事件通路的前置说明**：`self-register-both` 断言「两域事件均上行」。事件上报的对外 API
（`ServerProbeAPI.eventSink()`）属于 FR-33；若本功能先于 FR-33 落地，事件断言只能走
core 内部的 `BridgeClient.emitBusinessEvent`（当前离线 harness 就是反射调的这条）。
**本 spec 采用后者**：事件断言用 `BridgeClient.emitBusinessEvent` 的通路，
并把它标为「FR-33 落地后迁移到 `eventSink()`」。这样「可与 FR-32/28/29/30/31 独立成批」的声明才成立。

**不沿用原来的「装哪些插件」维度**——那是内置集成时代的场景划分（both / MCE-only / AIS-only / none），
自注册之后「探针内置了什么」这个维度已不存在。改为按**自注册的生命周期与故障面**设计 4 个场景：

| 新场景 | 内容 | 断言 |
|---|---|---|
| `self-register-both` | harness 注册 `activity` + `ranking` 两域，各自处理 dispatch 与事件 | 两域 manifest 均可发现（含各自 `actions` 的 JSON 结构）；各自回执的 `output` 含**独立 nonce**；两域事件均上行且带 `dedupKey`；一域失败不影响另一域（事故域隔离） |
| `self-register-single` | 只注册 `activity`，另查一个未注册域 | 已注册域正常往返；未注册域回「域不可用」；`manifest.domains` 只含已注册域 |
| `self-register-reload` | 注册 → `close()` → 再注册同域（模拟插件热重载） | 旧句柄 `getActive()==false`；重注册后新 Provider 生效；旧句柄 `close()` 不误删新 Provider（陈旧句柄保护） |
| `self-register-none` | 不注册任何域 | `jbis/manifest` 返回 `{"domains":{}}`（**空集是正常态**）；任一域命令返回「域不可用」；探针心跳、指标采集、TPS 上报均不受影响 |

**fixture 需要的改造（现状不支持下列断言，必须一并做）**

现有 `ProtocolWorkerFixture` 是**单连接单次 accept + 单一全局状态机 + 一次性 CompletableFuture**，
不足以支撑新断言：

| 断言需求 | 现状限制 | 改造 |
|---|---|---|
| 两域各自 nonce | echo 断言只做固定 payload 严格相等，无 nonce 概念 | 改为「回执 output 中的随机串等于 harness 写入证据文件的值」 |
| 两域事件分别收集 | `evidence` / `inventoryEvent` 是 `CompletableFuture` 一次性，`complete()` 只能填一次 | 改成 `List` 或按 domain 建 Map 收集 |
| 一域失败同时另一域正常 | `Stage` 枚举串行推进，一次只推一条命令序列 | 增加并发命令下发（不等上一条回执） |
| 重连/多次 accept | `serverSocket.accept()` 无循环 | 改为按脚本多次 accept（与 FR-36 共用该项改造） |

**覆盖的关键风险**

- **探针不含业务实现**：发行 jar 内不存在 `top/wcpe/mc/plugin/serverprobe/integration/` 下任何类；
  也不存在第三方业务包路径——判据用**包路径前缀**，不写易错的类名。
- **零业务依赖**：`./gradlew :plugin:dependencies` 输出不含 `integration-`；`api` 与 `project:core`
  的依赖树不含第三方业务坐标。
- **全链路真执行**：面板命令 → 路由 → 插件执行 → 回执含 nonce（排除模板成功）→ 插件上报事件 → 面板收到。

### 3.5 PRD 与 ADR 记账

**PRD**：FR-10 保持「已交付@v0.3.0」不动，新增：

> FR-37 | 业务对接改为纯第三方自注册：移除内置 MCE/AIS 集成，探针不含任何具体业务实现，
> 只做业务面板 ↔ 插件的路由中介；`api` 只提供跨域通用协议词汇
> （真实帧字段 `requestId` / `success` / `output` / `error` / `dedupKey` / `domain` + 约定词 `taskId`）
> 与 `ActionSpec` 容器
> **（取代 FR-10 的交付形态）** | P1 | 计划

**ADR-30**（新）：`移除内置业务集成，改为第三方自注册`

> **编号说明**：`docs/adr/README.md` 规定「编号 = 目录现有最大 + 1，永不复用、不补洞，别硬记某个数」。
> 本 spec 与桥改造三份 spec 都预写了 29/30/31，**实施时按当时目录的最大编号取号**，
> 若前序批次作废或调整则顺延，不要在三个批次里各自硬编码。

- **取代 ADR-0016 / ADR-0017 / ADR-0018 的全部决策**（不是只取代其中某一条）：
  这三份记录的都是「探针内置 MCE/AIS 适配」的具体做法（物品传输契约、背包写降级、内置模块化），
  内置实现整体移除后它们同时失效。
- 记录理由：新增业务不改探针；不跟随第三方 API 演进；职责单一（只做路由）。
- 记录后果：`economy`/`inventory` 域的字段名不再由本仓定义，改由各插件 manifest 声明；
  JM 侧协议不变，但第三方实现错误时探针不会替它兜（只如实转发失败）。
- 记录本 spec 对 FR-30 契约的修订：`manifest.actions` 从 `List<String>` 变为 `List<Map>`（见 §3.2）。
- 记录备选：保留为 examples（否决：仍需维护）；保留但不打包（否决：仓库仍有腐烂成本）。

**ADR 索引必须同步**：`docs/adr/README.md` 的索引表与 `docs/ARCHITECTURE.md` 的 ADR 总表
都要新增这一行（否则验收「记账完整」查不到）。

## 4. 任务拆分

- [ ] 删除 `integration/*` 两个模块（源码、测试、build.gradle.kts）与空目录
- [ ] 删除 `e2e/templates/configs/integrations/**`（4 个 config.yml）
- [ ] 清理 `settings.gradle.kts` + `plugin/build.gradle.kts` + `serverprobe.bundle.gradle.kts`
      （模块列表、`required` 必存在校验 → 改造为「不得包含」语义并重命名任务）
- [ ] 清理 `serverprobe.e2e-verification.gradle.kts`：41 处 `ntegration` 字面量
      + **MCE 离线运行时闭包约 200 行（`OfflineMavenArtifact`/`prepareOfflineTabooRuntime`/
      `copyMceRuntimeClosure`/`verifyOfflineMceRuntimeClosure`/`copyOfflineRuntimeLibraries`）**
      + 两条 bot 接线（`wireE2eDependency("e2eIntegrationsBoth"/"...AisOnly")`）
- [ ] 删除 `e2e/harness/**/IntegrationsConsumer.kt` **与 `IntegrationsConsumerTest.kt`**（后者不删则单测编译失败）
- [ ] `e2e/harness/**/ProtocolWorkerFixture.kt`：移除 AIS 分支（`"inventory_change"`/`handleInventoryEvent`/`INVENTORY_DOMAIN`）
- [ ] `e2e/harness` 前置改造：`compileOnly(project(":api"))` + `plugin.yml` 加 `softdepend: [ServerProbe]`（保留 `loadbefore`）+ 注册路径改 `ServerProbeAPI` + jar 任务排除 api + 构建断言「harness jar 无 api 条目」
- [ ] `ProtocolWorkerFixture` 能力扩展：多次 accept、按 domain 收集事件（原为一次性 `CompletableFuture`）、并发命令下发、nonce 往返
- [ ] 把 4 个 integrations 场景改造成 4 个 self-register 场景（both / single / reload / none）
- [ ] `api`：新增 `protocol` 包（`BusinessProtocol` / `ActionSpec`（含 `toMap()`） / `Actions`）
- [ ] 同步修订 `bridge-external-api.md`：`manifest.actions` 类型由 `List<String>` 改为 `List<Map>` + 补沙箱化约束 + 更新 api 包依赖白名单（新增 `api.bridge → api.protocol` 单向边）
- [ ] ADR（编号按目录当时最大 +1）：移除内置集成，改为第三方自注册；0016/0017/0018 标为被取代；**登记进 `docs/adr/README.md` 索引与 ARCHITECTURE 的 ADR 总表**
- [ ] PRD：新增 FR-37（标注取代 FR-10）；**修正 L80 悬空链接与 L128 验收行**
- [ ] 单测：`ActionSpec.toMap()` 经 `Json.encode`→`Json.parse` 往返后三键齐全；`Actions.read/write` 的字段与只读标志（**`write` 不自动加 `taskId`**）
- [ ] E2E：4 个 self-register 场景，含 nonce 往返、事故域隔离、陈旧句柄
- [ ] 构建校验：词根联合断言（见 §5）；发行 jar 正向断言含 `api.protocol`
- [ ] 文档：ARCHITECTURE（删模块表两行 + L56 措辞 + 依赖方向 + ADR 总表）、`README.md` L99、`docs/wiki/Installation.md` L47、`.claude/rules/architecture-invariants.md` L8、`.github/workflows/e2e.yml` L106、`docs/API.md`（只讲通用协议包与接入步骤，示例用虚构域）、CHANGELOG（破坏性变更 + 正确升级顺序）

**文件归属**

| 归属 | 文件 |
|---|---|
| 删除 | `integration/**`、`e2e/templates/configs/integrations/**`、`e2e/harness/**/IntegrationsConsumer.kt`、`e2e/harness/src/test/**/IntegrationsConsumerTest.kt`、`docs/specs/built-in-integrations.md` |
| 新增 | `api/.../api/protocol/*`、`docs/adr/<下一个编号>-*.md`、4 个 self-register E2E 场景 |
| 修改（构建） | `settings.gradle.kts`、`plugin/build.gradle.kts`、`build-logic/.../serverprobe.bundle.gradle.kts`、`build-logic/.../serverprobe.e2e-verification.gradle.kts`、`e2e/harness/build.gradle.kts`、`e2e/harness/src/main/resources/plugin.yml` |
| 修改（契约） | `docs/specs/bridge-external-api.md`（`manifest.actions` 类型 + api 包依赖白名单） |
| 修改（代码） | `e2e/harness/**`（`ProtocolWorkerFixture`、`ServerProbeE2eHarnessPlugin`） |
| 修改（文档/规则） | `docs/PRD.md`、`docs/ARCHITECTURE.md`、`docs/adr/README.md`、`docs/API.md`、`docs/specs/open-api-bridge-e2e.md`、`README.md`、`docs/wiki/Installation.md`、`.claude/rules/architecture-invariants.md`、`.github/workflows/e2e.yml`、`CHANGELOG.md`、`docs/adr/00{16,17,18}-*.md`（状态行） |
| **不归本功能** | `api.bridge.*` 的契约定义（归 FR-30）；`bridge_status`/`business_status` 的实现（归 FR-31/FR-35）。本 spec 只消费它们 |

## 5. 验收标准

**统一的残留判据**（替代原先各条零散的 `grep 某字面量 = 0`，那些会漏检不含该字面量的死代码）：

```bash
# 词根联合断言 —— 命中任何一项都需要人工确认或删除
grep -rnE "integration-|Integrations|integration/|multicurrencyeconomy|allininventorysync|\
MultiCurrencyEconomy|AllinInventorySync|MCE_JAR|AIS_JAR|offlineMce|OfflineMavenArtifact" \
  --include="*.kts" --include="*.kt" --include="*.java" --include="*.yml" --include="*.md" .
```
白名单（允许保留的命中）：`CHANGELOG.md` 历史条目、`docs/adr/0016|0017|0018` 正文（被取代声明）、
`docs/specs/business-self-registration.md` 自身、ADR-30。

| 项 | 判据（可执行） |
|---|---|
| 代码与构建零残留 | 上述词根断言：除白名单外**无命中** |
| 模块已删 | `test ! -d integration`；`test ! -d e2e/templates/configs/integrations` |
| 构建通过 | `./gradlew build` 通过（detekt / IoC 静态分析 / 字节码门禁全绿） |
| bundle 校验已改造 | `serverprobe.bundle.gradle.kts` 的 `required` 集合不再含 `*Provider.class`；任务名与 description 已改为「不得包含内置业务实现」语义；构建不因缺类失败 |
| **产物正向断言** | `unzip -l` 发行 jar：①**含** `top/wcpe/mc/plugin/serverprobe/api/protocol/ActionSpec.class`（防删过头/api 未打进 jar）；②**不含** `top/wcpe/mc/plugin/serverprobe/integration/` 前缀；③不含第三方业务包前缀 |
| **harness 未自带 api** | `unzip -l mc-testkit-e2e-harness.jar` **不含** `top/wcpe/mc/plugin/serverprobe/api/` 条目（防 `implementation` 误用导致静态字段分裂、注册静默失效） |
| **api 零第三方绑定** | ①`./gradlew :api:dependencies` 只有 Lombok；②`api/src/main/java/**` 对词根黑名单（见下）零命中；③`BusinessProtocol` 的常量集合**是**「真实帧字段」的子集，且每个常量的值都能在 core 的 `commandResultJson`/`businessEventJson` 里找到出处 |
| **api.protocol 词根黑名单** | `api/src/main/java/**/protocol/**` 不含：`currency`/`balance`/`amount`/`item`/`nbt`/`inventory`/`economy`/`mccoins`/`attribute`/`ender`（可 grep、可进 CI；比「禁止领域词」这种模糊表述可执行） |
| 序列化可用 | 单测：`ActionSpec.toMap()` 产出的 Map 经 `Json.encode` 后能被 `Json.parse` 回读，且 `actions` 数组元素含 `name`/`fields`/`readOnly` 三键（**防 POJO 直接进 manifest 导致的静默失效**） |
| api 包依赖边 | `api.protocol` 不引用 `api.bridge` 的任何类型（除 javadoc 纯文字）；`api.bridge` 单向依赖 `api.protocol`；FR-30 §3.4 的白名单已更新为四条边 |
| 自注册往返 | E2E `self-register-both`：两域 `manifest.domains` 可发现且 `actions` JSON 结构正确；回执 `output` 含随机 nonce（与 harness 写入证据文件的值一致）；两域事件均上行且带 `dedupKey` |
| 事故域隔离 | E2E `self-register-both`：一域 dispatch 抛异常/超时**同时**另一域正常往返（需 fixture 并发命令支持，见 §3.4） |
| 插件缺失降级 | E2E `self-register-none`：`jbis/manifest` 返回 `{"domains":{}}`（**空集断言为正常态**）；任一域命令回「域不可用」；心跳与指标采集不受影响 |
| 陈旧句柄 | E2E `self-register-reload`：①`close()` 后、重注册**前**，旧句柄 `getActive()==false` 且原因为 `CLOSED`；②重注册后新 Provider 生效；③旧句柄再 `close()` 不影响新 Provider。**不**断言重注册后旧句柄的原因（实现可为 `CLOSED` 或 `REPLACED`，两者都合法） |
| **CHANGELOG 破坏性变更** | 最新段含「破坏性变更」小节，写出：①域消失风险；②**正确升级顺序为「同一次重启一起替换」或「先探针后插件」，明确禁止先插件**；③对新版插件要求 `softdepend` + 类缺失可失败 |
| 记账完整 | PRD 有 FR-37 且标注取代 FR-10；ADR 文件存在且**已登记进 `docs/adr/README.md` 索引与 ARCHITECTURE 的 ADR 总表**；0016/0017/0018 状态行均为「已被取代」 |
| **文档无悬空（可执行）** | `grep -rn "built-in-integrations" docs/` 的命中仅允许出现在 `CHANGELOG.md` 与被取代的 ADR 正文中；PRD L80 的链接已改指 |

## 6. 风险 / 待定

1. **第三方接入门槛上升**：原来探针替 MCE/AIS 适配好，现在要插件自己实现。缓解：`api.protocol` +
   `docs/API.md` 接入示例 + harness 参考实现（在 `e2e/` 里，可当样例读）。
2. **JM 侧协议不能变**：`economy`/`inventory` 的字段名仍是 JM 解析依据，删除后这些约定只存在于
   JM 仓与 git 历史。**本仓明确不接手这份责任**：`api.protocol` 只给通用词汇（`taskId`/`success`/
   `error`），具体域字段由插件 manifest 声明、由 JM 适配；探针不校验、不记录、不提供
   协议说明表。这样出错时责任边界清晰（谁定义谁负责），但排查链路变长——故在 `docs/API.md` 的接入指南里
   明确写出「字段名要与面板侧一致，探针不会替你校验」这一句话。
3. **删除是破坏性变更**：对已部署用户，升级后 `economy`/`inventory` 域会消失，直到他们装上新版
   MCE/AIS（其中含自注册逻辑）。**升级顺序必须写对**：

   - **首选：同一次重启内一起替换**（探针 + 新 MCE + 新 AIS），窗口为零。
   - 若要分批，顺序**只能是「先探针、后插件」**：旧插件不自注册 → 域短期消失（功能降级，服务不崩）。
   - **绝不能「先插件、后探针」**：`ServerProbeAPI` 是本次新增的类，旧探针的 jar 里没有它；
     新插件先上会直接抛 `NoClassDefFoundError`（除非插件自己 softdepend + 类缺失时静默跳过，
     而那是另一个仓库的事）。
   - 对新版 MCE/AIS 的硬要求（写进 CHANGELOG 的升级说明）：`softdepend: [ServerProbe]`，
     且调用 `ServerProbeAPI` 必须可失败——类缺失或句柄未激活时静默跳过并 WARN，不得让自身 `onEnable` 抛异常。
4. **原背包物品契约**：其专有载荷结构不再由本仓定义。若上游插件将来改格式，JM 侧需同步，
   探针不参与——这正是期望的解耦，ADR-30 已写明「探针不再提供该格式的任何兼容层」。
5. **`jbis/manifest` 可能为空**：全新部署（未装任何自注册插件）时该接口返回 `{"domains":{}}`。
   JM 侧若把「空 manifest」当作「探针异常」会误报。需与 JM 确认空集是正常态；本仓的 E2E
   `self-register-none` 已把空 manifest 断言为**正常**。
6. **第三方接入门槛上升的兜底不足**：本仓只能提供通用协议词汇与虚构域示例，无法替第三方验证
   它写的字段名是否与 JM 一致。若上线后出现「插件说成功、面板解析不到」的哑失败，排查链条会跨三个仓库。
   缓解：`docs/API.md` 明确写「字段名须与面板侧一致，探针不校验」；并把 `bridge_status`/`business_status`
   （FR-31/FR-35）作为排查入口——它们能证明「命令确实到了插件、回执确实是插件给的」。
