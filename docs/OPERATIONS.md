# 运维手册:ServerProbe

> 部署、升级、备份恢复、回滚、排障的操作指南。运维方式变化时更新。
>
> 现状边界(诚实标注):本项目**尚未发布正式构建产物**,需自行构建;**仅在 1.21.4 Paper 单端真机验证全通过**,其余端(1.8 / Folia / BungeeCord)仅编译通过、功能未逐一真机。本手册描述的命令、路径、配置键以 `README.md`、`plugin/src/main/resources/config.yml` 与代码实际为准。

---

## 1. 部署

**前置条件**

- 对应版本的 Bukkit 系服务端(CraftBukkit / Spigot / Paper / Folia,1.8 – 1.21.11)或 BungeeCord 代理端。
- 与服务端匹配的 JRE(Java 8+;1.17+ 服务端需 17+,1.20.5+ 需 21+)。ServerProbe 核心为 Java 8 字节码,可被全部 JRE 加载。

**步骤**

1. 将构建出的 `ServerProbe-*.jar` 放入服务端的 `plugins/` 目录(代理端放入 BungeeCord 的 `plugins/`)。
2. 重启服务端,首次启动自动生成默认配置 `plugins/ServerProbe/config.yml`。
3. 进服执行 `/probe`(或下方健康检查命令)验证插件已加载、命令可用。

> 当前尚未发布正式产物,`plugins/` 中没有现成 jar 可下载。需自行用 `./gradlew build` 构建**发行版本**(见第 6 节),产物位于各模块的 `build/libs/` 下,取最终发行 jar 放入 `plugins/`。

**健康检查**

- `/probe health` —— 服务器健康总览(TPS / MSPT / 内存 / CPU / 在线人数),用于部署后确认采集链路正常。

**关键配置项速览**(完整说明见 `plugin/src/main/resources/config.yml`,修改后需重启或重载生效)

| 配置键 | 默认值 | 作用 |
|---|---|---|
| `collect-period-ticks` | `100`(约 5 秒) | 指标采集周期 |
| `history-capacity` | `360` | 内存中近期历史保留份数 |
| `history-retention` | `30` | 启动画像归档保留份数 |
| `history-file.enabled` | `true` | 历史指标 JSONL 落盘开关 |
| `history-file.retention-days` | `7` | 历史文件保留天数 |
| `history-file.max-total-mb` | `200` | 单实例历史文件总体积上限(MB) |
| `world.sample-period-ticks` | `600`(约 30 秒) | 世界/实体采样周期 |
| `metrics.enabled` | `false` | Prometheus `/metrics` 端点开关(默认关闭、仅本机) |
| `alert.enabled` | `false` | 告警引擎开关(默认关闭) |
| `http-monitor.enabled` | `true` | HTTP/TCP 外呼监控(需挂载 agent 方生效) |
| `debug` | `false` | 是否输出 DEBUG 级调试日志 |
| `server-name` | `""` | 实例名;留空自动生成稳定实例 ID |

### 1.1 可选:启用启动期 agent

ServerProbe 是**二合一 jar**——同一个 `ServerProbe.jar` 既是 `plugins/` 下的插件,又可作 `-javaagent`。**默认无需任何额外操作**,丢进 `plugins/` 即为功能完整的纯插件。

若要额外采集 ServerProbe **自身加载之前**的盲区(逐插件精确耗时、库下载耗时、主线程栈采样、对外 HTTP/TCP 外呼监控),在**启动命令**中加一行 `-javaagent` 指向同一个 jar:

```bash
java -javaagent:plugins/ServerProbe.jar -jar paper.jar
```

- **手动启用**:必须自行修改启动命令 / 启动脚本。**不加这行则纯插件模式照常工作**,所有既有能力不受影响。
- **为何安全**:这是**启动期命令行 premain**(由 JVM 在 `main` 之前加载),**不是运行时 self-attach**,在 Paper + JDK21/24 上零警告、不受 JEP 451 限制;premain 顶层 `catch(Throwable)` 兜底,启用失败一律静默降级,不会崩 JVM。
- **当前边界**:仅 **1.21.4 Paper 单端真机验证**;Folia 无单一主线程,主线程栈采样降级标 N/A;BungeeCord 端推迟。引入唯一新依赖 ASM(已 relocate 隔离)。

---

## 2. 升级

1. 用新版本 `ServerProbe-*.jar` 替换 `plugins/` 下的旧 jar。
2. 重启服务端。

- **配置兼容**:落盘根对象均含 `schemaVersion`(M1 = 1),用于格式演进与向后兼容;新版本读旧文件、旧版本读新文件均以 `schemaVersion` 容错。配置文件向后兼容,新增项使用默认值,无需手工迁移。
- **破坏性变更**:升级前查阅 `CHANGELOG.md`,确认本次升级是否包含破坏性变更(配置键改名、落盘格式 `schemaVersion` 跃迁等)再操作。

---

## 3. 数据备份与恢复

**备份对象**:`plugins/ServerProbe/` 目录,包含:

- `config.yml` —— 配置文件。
- `data/startup/` —— 启动画像:`latest.json`(最近一次,供启动对比)+ `<epochMs>.json`(历次归档)。
- `data/metrics/<实例>/metrics-<yyyyMMdd>.jsonl` —— 历史指标,按实例分目录、按自然日滚动的 JSON Lines。
- `data/flamegraph/flamegraph-<时间戳>.html` —— 导出的启动火焰图自包含 HTML(挂载 agent 后由 `/probe flamegraph` 生成)。
- `data/http/http-<yyyyMMdd>.log` —— 对外 HTTP/TCP 外呼记录(挂载 agent 后落盘)。

**多实例**:落盘按实例分目录存放(`data/metrics/<实例>/`),备份时按实例分目录管理,避免互相覆盖。

**保留策略由配置控制**(到期文件由插件自动清理,无需人工干预):

- `history-retention` —— `data/startup/` 启动画像归档保留份数。
- `history-file.retention-days` —— 历史指标 JSONL 保留天数。
- `history-file.max-total-mb` —— 单实例历史文件总体积上限,超出从最旧文件起清理至达标,**绝不删除当天文件**。

**恢复**:纯文件存储、无数据库。停服后将备份的 `plugins/ServerProbe/`(或其中的 `config.yml` + `data/` 目录)还原到原位,再启动即可。

---

## 4. 回滚

1. 将 `plugins/` 下的 jar 换回上一个版本。
2. 重启服务端。

- 落盘文件向后兼容:旧版本读取新格式文件时以根对象的 `schemaVersion` 容错,通常无需清理 `data/`。
- 若回滚跨越了破坏性的 `schemaVersion` 跃迁,先查 `CHANGELOG.md` 评估影响;必要时将 `data/` 一并还原到对应版本的备份。
- **代码层面的回滚**(撤销某次提交 / 某个功能)走 SDD 流程,见 `sdd-rollback-change` 技能:优先 `git revert`,先评估兼容影响,再同步文档。

---

## 5. 排障

**① Prometheus 端口被占用**

- 现象:`metrics.enabled: true` 时端点起服失败。Windows 上可能报 `Address already in use` 但 `netstat` 查不到占用进程——这是撞上系统动态保留端口段。
- 行为:端点起服失败会**优雅降级**(打印 WARN 日志),**不影响插件其余功能与服务端启用**。
- 处理:换一个端口。Windows 可用 `netsh int ipv4 show excludedportrange protocol=tcp` 查看保留段,改 `metrics.port` 为范围外端口。Linux 生产环境无此问题。

**② 启动期 agent 未生效**

- 检查启动命令里 `-javaagent:plugins/ServerProbe.jar` 的路径是否正确(相对路径以启动工作目录为基准)。
- 查看启动日志确认 agent 是否加载。premain 启用失败会**静默降级**为纯插件模式,这属正常容错,不会崩 JVM。
- 未挂载 agent 时,`/probe flamegraph`、`/probe http` 及启动画像中的逐插件精确耗时 / 库下载耗时 / 主线程热点等增强项不可用,属预期。

**③ 日志位置与 debug 开关**

- 探针日志走服务端控制台 / 日志文件,级别 ERROR / WARN / INFO / DEBUG。
- 将 `config.yml` 的 `debug: true` 打开后重启,可输出 DEBUG 级调试日志用于排查;问题定位后建议改回 `false` 避免刷屏。

**④ Folia 下的预期降级(非故障)**

- TPS / MSPT 全局标 **N/A**(Folia 无全局主线程 tick)。
- 世界采集走路线 1:仅区块数,实体 / 方块实体计数置 **N/A**。
- 启动期 agent 主线程栈采样标 **N/A**(无单一主线程)。

**关键日志 / 数据位置一览**

- 配置:`plugins/ServerProbe/config.yml`
- 启动画像:`plugins/ServerProbe/data/startup/`
- 历史指标:`plugins/ServerProbe/data/metrics/<实例>/`
- 火焰图:`plugins/ServerProbe/data/flamegraph/`
- 外呼记录:`plugins/ServerProbe/data/http/`

---

## 6. 构建与版本(运维相关)

**构建命令**(在项目根目录执行)

| 命令 | 用途 |
|---|---|
| `./gradlew build` | 构建**发行版本**(用于正常使用,不含 TabooLib 本体);运维取此产物部署 |
| `./gradlew taboolibBuildApi -PDeleteCode` | 构建**开发版本**(含 TabooLib 本体,供开发者使用,不可运行;`-PDeleteCode` 移除逻辑代码以减小体积) |
| `./gradlew test` | 运行单元测试 |

**版本号权威来源 = 根 `gradle.properties` 的 `version` 字段**(当前 `1.0.0-SNAPSHOT`,Gradle 构建原生读取)。

> 版本口径存在不一致:规划路线(`CHANGELOG.md` Roadmap / PRD §10)将 M1 首版标为 `0.1.0`,而 `README.md` 徽章 / PRD 标注为 `v0.2-draft`,根 `gradle.properties` 则为 `1.0.0-SNAPSHOT`。三套口径不统一,**以 `gradle.properties` 为构建实际值**,统一对外口径待项目维护者定夺。
