# 功能规格：Incision 方法级精确归因 PoC（FR7）

> 状态：织入链路已确认；性能、回滚与失败降级尚未验收　·　关联 PRD：FR7 / §5.5 / ADR-2

## 目标

在目标 Paper + JDK 上确认 TabooLib Incision 能织入
`SimplePluginManager#enablePlugin`。正式功能仍必须默认关闭，并在失败时不影响插件启用。

## 已完成的 PoC

2026-08-24 使用仅用于验证的临时构建，直接合并 Incision
`6.3.0-75b18a2`，为 `enablePlugin` 加入前后置计时切点。环境为 Paper
`1.21.11-132` 与 JDK `21.0.4`；ServerProbe 正常启用，并输出：

```text
Incision PoC 命中 enablePlugin:BukkitPlugin 118ms
```

这证明该目标方法已被织入且通知方法实际执行。临时 Incision 依赖与 PoC 类已从发行构建移除，当前发行 jar 不携带 Incision。

## 仍需完成

- [ ] 在代表性负载下比较启用前后的 MSPT p95，劣化不得超过 5%；
- [ ] 实现并验证 `incision.enabled=false` 的运行时关闭路径，重启后无织入残留；
- [ ] 模拟不兼容切点，确认插件仍可启用且记录可诊断的降级信息；
- [ ] 上述全部通过后，才可将 FR7 变为正式默认关闭功能并更新 ADR-2。

单次真机织入成功不等于 FR7 验收通过。
