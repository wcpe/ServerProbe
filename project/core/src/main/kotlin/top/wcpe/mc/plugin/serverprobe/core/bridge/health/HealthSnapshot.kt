package top.wcpe.mc.plugin.serverprobe.core.bridge.health

import top.wcpe.mc.plugin.serverprobe.api.bridge.DomainStatus

/**
 * 全量健康视图，不可变；`core.business_status` 直接序列化它。
 *
 * 之所以是独立类型而不是"散装字段读取器"：回执必须是从**同一时刻**的同一份值拼出来的，
 * 逐字段现读会在并发刷新时拼出撕裂组合（`stalled=true` 却 `lastSweepAtMs` 是旧的）。
 *
 * @property domains 单域视图；稳定顺序 = 已注册在前，其次各按 domain 字典序。
 * @property refreshInFlight 是否正有一轮刷新在进行。
 * @property stalled probe 线程被占住、提交被拒时为 true（见规格 §3.3 的有界性取舍）。
 * @property quarantined 当前处于隔离窗口内的域（按字典序）。
 * @property lastSweepAtMs 最近一轮 sweep 的开始时刻；从未 sweep 为 0。
 * @property skippedThisSweep 最近一轮因停滞被跳过的域（保留上次缓存）。
 */
class HealthSnapshot(
    val domains: List<DomainStatus>,
    val refreshInFlight: Boolean,
    val stalled: Boolean,
    val quarantined: List<String>,
    val lastSweepAtMs: Long,
    val skippedThisSweep: List<String>,
)
