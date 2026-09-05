# 启动剖析指南

这是 ServerProbe 的**首要能力**：当你觉得"开服越来越慢、又说不清慢在哪"时，用启动剖析把它**量化定位**到具体插件、世界、生命周期阶段，并能与历史对比。

## 一、能拿到什么

执行 `/probe startup` 查看最近一次启动画像：

- **端到端启动总时长**：JVM 启动到服务就绪
- **慢插件 Top-N**：逐插件 onEnable 耗时排名（本插件经生命周期打点，全部插件经日志解析；挂 agent 后为纳秒级精确耗时）
- **各世界加载耗时**：逐世界与 spawn-chunk 预加载
- **生命周期分段**：CONST / INIT / LOAD / ENABLE / ACTIVE 各阶段
- **与上次对比**：每项 Δ 标注（首次记录则无对比基线）
- **慢启动告警**：总时长 > 基线 ×1.5 时告警（`alert.enabled`）

启动画像**落盘为本地 JSON**（`plugins/ServerProbe/data/`），每次启动一份。

## 二、精确归因（可选增强）

### 启动期 agent

以 `-javaagent:plugins/ServerProbe.jar` 启动后，额外获得：

- 逐插件 load/enable **纳秒级精确耗时**（覆盖本插件之前加载的插件）
- **库下载耗时**（LibraryLoader，1.17+）
- 世界创建 / 配置加载 / 事件注册 / 命令注册耗时
- **多线程折叠栈采样**（Server thread / Netty / ServerMain）
- **火焰图 + 时间线**：`/probe flamegraph` 导出**自包含 HTML**（CSS/JS 内联、无 CDN）到 `data/flamegraph/`
- **对外网络外呼监控**：`/probe http` 回看哪个插件/代码发起了对外请求

采集严格收敛在启动窗口（插件就绪即关闭），默认不启用，启用失败静默降级。

### Incision 方法级归因

Bukkit/Paper 端可启用 Incision（`incision.enabled=true`）采集 `SimplePluginManager#enablePlugin` 的逐插件精确启用耗时，写入启动画像。默认关闭。

## 三、读图示例

```
总时长: 18.0s (较上次 +2.3s)
慢插件 Top-5:
  ServerProbe          1.0s
  CoreLib              0.8s
  ...
世界加载:
  world        3.2s
  world_nether 0.9s
```

> 以上为示意输出，实际以命令输出为准。

## 四、盲区与补法

- 服务端 bootstrap（DFU/注册表）早于任何插件加载，普通插件不可见，只能整体时长对比。
- ServerProbe 自身加载前的盲区（早于本插件 onEnable 的插件计时、库下载）由可选 premain agent 补上。
