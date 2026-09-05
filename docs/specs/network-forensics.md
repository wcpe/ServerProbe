# 功能规格：全平台网络流量与数据包取证

> 状态：已完成验收，待下次正式版本登记　·　关联 PRD：FR-11　·　分支：当前分支

## 1. 背景与目标

现有网络指标只有在线人数与 ping，无法回答流量突增、某类包攻击或事故时具体收到了什么。本功能在 Bukkit/Spigot/Paper/Folia、BungeeCord、Velocity 上统一采集双向流量和包速率，并把重要包以有界、可清理的本地 SQLite 证据保存下来。

## 2. 需求（要什么）

- 全平台输出 ingress/egress bytes/s、packets/s 与包类型计数。
- 记录时间、方向、玩家 UUID/名称、完整 IP、包类型、通道、原始长度、SHA-256 与可选载荷。
- 默认开启取证；元数据与包类型始终记录，完整载荷只记录白名单包类型；Plugin Message 还必须命中独立通道白名单，默认空。
- 单包载荷默认最多 64 KiB，可配置；截断仍保存原始长度与完整原始包 SHA-256。
- SQLite JDBC 固定 `3.53.2.1`，作为发行 jar 内嵌闭包随包分发（ADR-0019 的发行形态演进 + ADR-0027：不再依赖 TabooLib 在线下载）；数据库文件为通用库 `serverprobe-store.sqlite`（旧 `network-forensics.sqlite` 启动时自动迁移）；驱动加载失败时仅关闭数据库取证，聚合指标继续工作并打印中文 WARN。
- 默认保留 60 天、数据库上限 4 GiB，可配置；任一限制超出时按最早记录优先删除。
- Prometheus 只暴露聚合速率、包类型计数和脱敏 IP 前缀：IPv4 `/24`、IPv6 `/64`，每采样周期 Top 100，其余合并为 `other`。
- Web 与 FR-08 只读 API 可查询完整 IP、包类型和白名单载荷；必须给出时间范围，可按方向、包类型、玩家 UUID/名称、IP 过滤，最多 100 条/页。
- 范围内：实时聚合、Netty 管线接入、SQLite、保留策略、Prometheus/Web/FR-08 查询。
- 不做：远程数据库、载荷加密、自动封禁、深度协议语义解析、把完整 IP 或载荷暴露给 Prometheus。

## 3. 设计（怎么做）

- `project:core` 定义平台无关的 `PacketObservation`、聚合器、脱敏器、查询模型和 `PacketForensicsStore`；各平台模块只负责连接生命周期、玩家关联和协议包规范化。
- Netty EventLoop 上只完成计数、类型识别、SHA-256 增量计算与白名单载荷的有界复制；严禁跨线程保留 `ByteBuf`。记录进入有界 MPSC 队列，由单写线程批量事务落库；队列满时丢弃取证记录并增加 dropped 指标，不阻塞网络线程。
- Bukkit/Paper/Folia 从玩家 `ChannelPipeline` 接入；BungeeCord/Velocity 从连接管线接入。注入 handler 使用 ServerProbe 唯一名称，支持登录、转服、重连和卸载时幂等移除。
- 包类型优先采用已解码消息类规范名；无法解码时使用协议状态、方向与 packet id 形成稳定名称。Plugin Message 同时提取 channel，用双白名单决定载荷。
- SQLite 使用 WAL、`busy_timeout`、批量插入和按 `captured_at,id` 的游标分页；索引覆盖时间、方向、类型、玩家、IP。清理分批执行并在删除后按阈值 checkpoint，不在网络线程或服务器主线程做磁盘 IO。
- 查询对象返回 `payloadCaptured` 与截断标记；非白名单记录的 `payload` 永远为空。Web 复用既有鉴权，FR-08 JVM 内调用方视为同进程受信任消费者。
- IP Top 100 在每个采样周期内按包数排序，标签只含掩码前缀；不得缓存或输出完整 IP 标签。
- 架构决策在规格获批后写入 ADR-0019。

## 4. 任务拆分

- [x] 先补白名单、截断/哈希、IP 掩码、Top-N、分页与保留策略测试。
- [x] 实现平台无关聚合、队列、SQLite Store 与驱动降级。
- [x] 实现 Bukkit、BungeeCord、Velocity 管线适配及生命周期测试。
- [x] 扩展 FR-08 API、Prometheus 与 Web 查询。
- [x] 用 mc-testkit 真实协议客户端覆盖六类服务端平台与进出方向。
- [x] 做突发流量、队列满、4 GiB 模拟上限、60 天清理和卸载性能验收。
- [x] 文档同步：PRD 状态、ARCHITECTURE、API、配置、OPERATIONS、CHANGELOG。

## 5. 验收标准

- Bukkit/Spigot/Paper/Folia/BungeeCord/Velocity 均能从真实连接得到非零双向 bytes/s、packets/s 与稳定包类型计数。
- 白名单包保存载荷，非白名单与默认空 Plugin Message 通道不保存载荷；截断记录的原始长度和 SHA-256 正确。
- 查询强制时间范围，所有过滤器与游标分页正确，单页请求大于 100 被拒绝或收敛为 100。
- Prometheus 输出不含完整 IP、UUID、玩家名或载荷，只含聚合指标和规定掩码的 Top 100/`other`。
- 60 天与 4 GiB 双上限都能触发最早优先清理；清理与批量写入期间网络线程无数据库 IO。
- 模拟驱动下载失败时探针保持启用、聚合仍更新、取证 API 明确报告不可用且日志为中文 WARN。
- mc-testkit 结果文件覆盖真实登录、普通游戏包、白名单 Plugin Message、恶意大包/突发包与断线重连并全部 PASS。

## 6. 风险 / 待定

- 旧版本服务端的 Netty 字段与协议类名差异较大，需要按平台/版本做最小反射适配并以真机矩阵锁定。
- 完整包 SHA-256 会增加 CPU；验收必须比较关闭/开启取证的网络线程开销，超标时只能通过采样/关闭载荷降级，不能改成阻塞写库。
- 无剩余功能项；待完成的是发布登记。

## 7. 验收记录（2026-08-28）

- 六平台真实结果文件全部 PASS（`build/mc-testkit/results/`）：Spigot 1.20.1（`network-forensics-bukkit`）、Paper 1.20.1、Folia 1.21.4、BungeeCord、Velocity；真实登录流量、白名单通道 `serverprobe:test` 载荷、24594 字节前缀截断 + 完整 SHA-256、Prometheus 脱敏、Web/FR-08 完整 IP 均经真实查询断言。
- 平台差异结论（真机实证，E2E 模板与验收桩已固化）：
  - 玩家加入时的高频区块包会把单页 100 条查询挤出白名单记录，直连场景验收查询必须按白名单包类型过滤；
  - Folia 1.21.4 运行时为 Mojang 映射，入站自定义载荷类名为 `bukkit.ServerboundCustomPayloadPacket`（Spigot/Paper 1.20 为 `bukkit.PacketPlayInCustomPayload`）；
  - Folia 上验收轮询必须运行在独立线程，不得占用全局 region 调度器线程（会冻结全局 tick 并饿死探针调度任务）。
- 单测：`SqlitePacketForensicsStoreTest`、`IpMaskerTest`、`PacketTrafficAggregatorTest`、`PacketCapturePolicyTest`、`ReflectiveNettyForensicsTest`、`NetworkForensicsWebQueryTest`、`BukkitNetworkForensicsE2eContractTest` 等全绿；逐条证据见 `.tmp/acceptance-phase-M5-2026-08-28.md`。

