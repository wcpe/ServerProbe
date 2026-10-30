# ADR-0029：业务桥契约下沉 `api` 模块，以静态门面供第三方自注册

## 状态

已接受（实现见 [bridge-external-api spec](../specs/bridge-external-api.md)）

## 背景

业务插件接入探针桥原本只有一条路：拿探针的 `PluginClassLoader`，反射
`BeanContainer.getBean(...)` 取到 core 的 `BusinessHost`，再用 `Proxy.newProxyInstance`
现造一个 `BusinessProvider`。这条路的三个问题在 FR-37（移除内置集成）之后变成硬阻塞：

1. **契约不可见**。`BusinessHost` / `BusinessProvider` / `BridgeCommandResult` 都长在 `core` 里，
   而 `core` 是内部实现模块。第三方要么依赖一个"本不该被依赖"的模块，要么反射。
2. **反射无编译期保护**。方法名写错、签名漂移，编译期一无所知，只在运行期静默失效。
3. **门面不存在**。没有任何"官方支持的接入点"，谁都能靠内部容器摸进来，
   内部结构一改就全体崩，且崩得没有边界。

同时，探针自身也需要把"桥对外发布什么"与"桥内部怎么做"分开——`core` 里混着
契约定义和实现（业务线程池、注册表、幂等、超时），无法对外发布一个稳定的制品。

## 决策

把**整个桥对外契约**（不是其中一部分）下沉到独立的 `api` 模块，并以
`ServerProbeAPI` 这个 `static volatile` 门面作为唯一外部接入点。

- `api` 为**纯 Java 8**，零第三方业务依赖，可独立发布 Maven 制品；
- 门面方法返回**稳定委派代理**——永不 `null`、可安全缓存为字段，
  探针后加载/卸载都自动生效；
- 门面**绝不抛异常**：失效原因用 `InactiveReason` 枚举表达，不用自由文本；
- 装配点放在 `plugin` 的 `@Service`（`BridgeFacadeInstaller`）上，在 IOC 完成注入后
  `install`、容器销毁前 `uninstall`，因为 `ServerProbe` 本身是 IOC 启动者而非受管 bean。

## 理由

- **范式对齐 Beacon**（`BeaconAgentProvider` 的 `static volatile` + `register/unregister`）：
  两个项目的接入体验应当一致，减少使用方的认知负担。
- **为什么不直接发布 `core`**：`core` 的公开面等于其全部实现细节，
  一旦发布就无法再改。把契约单独成模块，才有"契约稳定、实现可动"的空间。
- **为什么返回代理而不是 `null`**：探针可能后于第三方加载。若返回 `null`，
  第三方必须写"等探针就绪再注册"的时序代码；返回稳定代理则把时序问题收进 `api` 内部，
  使用方只需在拿到 `Registration` 时读一次 `getActive()`。
- **为什么用枚举而非字符串表达失效原因**：字符串会被使用方拿去做
  `equals`/`startsWith` 判断，等于把措辞固化成契约；枚举可以只增不改。

## 后果

- `api` 模块的内部包依赖被收紧为四条**单向**边（`api → api.bridge`、`api → api.reload`、
  `api → api.protocol`、`api.bridge → api.protocol`、`api.reload → api` 仅顶层 `Registration`），
  新增边必须改 spec 并说明理由。
- `BridgeServices` 的演进只允许**追加 `default` 方法**，且 default 必须返回该子门面的
  独立降级单例——写成 `UnavailableBridgeServices.INSTANCE.同方法()` 会 `StackOverflowError`。
- 门面的 `install` **仅靠 package-private 可见性**提高误用成本（调用方必须声明同包并跨包名冲突），
  **没有任何调用栈白名单**。曾计划加 `SELF_INSTALLER_CLASSES` 类名白名单，但**未落地**，
  本 ADR 不声称它存在。无论哪种方式都**不是安全隔离**：同一 JVM 内的恶意插件仍可
  声明同名包并自行调用 `install` 顶替实现。已在此明确记录，避免后续误以为它是一道防线。
- 治理版 `BridgeCommand`（带 `target`/`reason`）与 `BridgeCommandHandler` **留在 `core`**，
  不进 `api`——它们是内部治理策略，不是第三方契约。

## 备选方案

- **继续只靠反射/`BeanContainer`**：零改动，但第三方无编译期保护，且把内部结构变成事实契约。
  落选原因见「背景」第 3 点。
- **把契约放进 `plugin` 模块**：`plugin` 是发行物本身，依赖它等于依赖整个探针（含平台代码），
  且无法单独发布。
- **在 `core` 上标注 `@ApiStatus.Internal` 区分公开面**：仅靠注解无法阻止依赖，
  也无法让"发布面"与"实现面"在构建产物上真正分开。
