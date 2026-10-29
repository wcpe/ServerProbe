package top.wcpe.mc.plugin.serverprobe.api;

import top.wcpe.mc.plugin.serverprobe.api.read.MetricsReadApi;
import top.wcpe.mc.plugin.serverprobe.api.read.NetworkForensicsReadApi;
import top.wcpe.mc.plugin.serverprobe.api.read.StartupReadApi;

/**
 * 只读开放接口。
 *
 * 面向第三方插件的只读数据访问出口,供其经 TabooLib 服务获取探针数据
 * (最新指标快照、近期指标历史、最近一次启动画像)。
 *
 * 本接口**只读**:仅暴露查询能力,不暴露任何写入/控制能力,确保外部消费方无法影响探针运行。
 *
 * 本接口按子能力拆分为若干子接口,自身仅作**聚合**门面(不含任何自有方法),便于能力继续增长时
 * 落到对应的子接口,而不是堆在单一接口上:
 * <ul>
 *   <li>{@link MetricsReadApi} — 指标快照与跨快照聚合;</li>
 *   <li>{@link StartupReadApi} — 启动画像与启动对比摘要;</li>
 *   <li>{@link NetworkForensicsReadApi} — 网络包取证查询与取证服务状态。</li>
 * </ul>
 * 既有实现与调用方无需改动:实现本接口即等价于实现全部子接口的能力;也可按需只依赖某个子接口。
 */
public interface ProbeReadApi extends MetricsReadApi, StartupReadApi, NetworkForensicsReadApi {
}
