package top.wcpe.mc.plugin.serverprobe.api.sampler

import top.wcpe.mc.plugin.serverprobe.api.enums.TickSampleSource
import top.wcpe.mc.plugin.serverprobe.api.model.TickSample

/**
 * 服务器 tick 采样器契约。
 *
 * TPS/MSPT 是唯一需要多版本 + 多平台兼容处理的关键指标,TabooLib 完全不封装。
 * 本接口抽象多种采样途径,运行时按环境探测后选择对应实现:
 * - Paper:经官方 `getTPS()` 等 API;
 * - 老版本/纯 CraftBukkit:经 NMS 反射读取或自建采样;
 * - Folia:无全局值,标 N/A([sample] 各数值字段返回 null)。
 */
interface ServerTickSampler {

    /**
     * 本采样器的数据来源,供呈现层标注数据背景。
     *
     * 固有属性,不随单次采样结果变化;Folia 实现固定为 `UNAVAILABLE`;
     * Paper/NMS 实现即使本次取不到值也保持其 source,靠 [TickSample] 字段 null 表瞬时缺值。
     */
    val source: TickSampleSource

    /**
     * 采样当前 tick 数据(TPS/MSPT)。
     *
     * @return tick 采样结果;无法采集全局值时各数值字段为 null。
     */
    fun sample(): TickSample
}
