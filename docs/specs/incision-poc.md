# 功能规格：Incision 方法级精确归因（FR7）

> 状态：已交付@v0.2.0 · 关联 PRD：FR7 / §5.5 / ADR-2

## 范围

仅 Bukkit/Paper 端以 VanillaModify 同款 Incision 注解链路采集 `SimplePluginManager#enablePlugin`：`@Surgeon` 持有 `@Lead`/`@Trail`，由 Incision 在 CONST 阶段扫描、注册与卸载。构建使用 TabooLib Gradle 插件 `2.0.37-fix` 和运行库 `6.3.0-5b6fe60`，运行期模块从 `https://maven.wcpe.top/repository/maven-public/` 下载。

配置 `incision.enabled` 默认 `false`；关闭时 advice 立即返回且不写精确耗时。开启后重启生效。未命中有效切点时服务端和其他插件仍正常启用，启动画像保留普通路径。

## 验收结果

环境：Paper `1.21.11-132`、JDK `21.0.4`。

| 项目 | 结果 | 证据 |
|---|---|---|
| 默认关闭 | 通过 | `incision.enabled=false` 时启动完成，启动画像为 `incisionEnabled=false`、`incisionActive=false`、精确耗时列表为 null。 |
| 注解式采集 | 通过 | `incision.enabled=true` 时临时 `IncisionTarget` 插件启用，启动画像为 `incisionEnabled=true`、`incisionActive=true`，记录 `IncisionTarget` 的精确耗时。 |
| 无效切点降级 | 通过 | 将目标方法临时改为不存在的 `enablePluginMissing` 后，服务端仍输出 `Done`；启动画像为 `incisionEnabled=true`、`incisionActive=false`、精确耗时列表为 null。 |
| 卸载 | 通过 | 使用 Incision `@Awake(DISABLE)` 生命周期自动清理当前 ClassLoader 的 advice，不保留项目内 bootstrap 或扫描器替代实现。 |
| 性能 | 通过 | 受控 5ms/tick 工作负载下，MSPT p95 从 `6.7194ms` 到 `6.6765ms`，变化 `-0.0429ms`（`-0.64%`），低于 5% 上限。 |

## 数据契约

`StartupProfile` 的 `schemaVersion` 为 `4`，新增向后兼容字段：

- `incisionEnabled`：本次启动是否请求采集；
- `incisionActive`：目标切点是否实际执行；
- `incisionPluginEnableTimings`：仅实际采集时提供的逐插件精确启用耗时。

## 兼容性边界

本次真机覆盖仅限 Paper `1.21.11-132` 与 JDK `21.0.4`。不将该结论外推到 Spigot、Folia、BungeeCord 或服务端 bootstrap/NMS 阶段；上述范围保持普通启动画像路径。
