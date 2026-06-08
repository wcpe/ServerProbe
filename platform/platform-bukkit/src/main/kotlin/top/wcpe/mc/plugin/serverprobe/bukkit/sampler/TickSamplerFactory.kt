package top.wcpe.mc.plugin.serverprobe.bukkit.sampler

import taboolib.platform.Folia
import top.wcpe.mc.plugin.serverprobe.api.sampler.ServerTickSampler

/**
 * tick 采样器工厂:按运行环境探测并装配唯一的 [ServerTickSampler]。
 *
 * 选路优先级(从高到低):
 * 1. **Folia**([Folia.isFolia]):无全局 TPS/MSPT → [UnavailableTickSampler];
 * 2. **Paper**:[TickReflection.paperTps] 反射可取到 TPS → [PaperTickSampler];
 * 3. **Legacy**:NMS `recentTps` 反射链路可用([TickReflection.isRecentTpsAvailable])→ [LegacyTickSampler];
 * 4. **Self**:以上皆不可用 → [SelfTickSampler](最终兜底)。
 *
 * 其中 Legacy/Self 依赖自建 [TickClock] 喂样本,故工厂在选中它们时一并创建并共享同一 [MsptHistogram]
 * /[TickClock],经 [TickSamplerSelection.clock] 交回调用方驱动启停;Paper/Folia 路径 [clock] 为 null。
 */
object TickSamplerFactory {

    /**
     * 探测环境并创建采样器装配结果。
     *
     * @return 采样器及其(可选的)tick 时钟。
     */
    fun create(): TickSamplerSelection {
        if (Folia.isFolia) {
            return TickSamplerSelection(UnavailableTickSampler(), null)
        }
        if (TickReflection.paperTps() != null) {
            return TickSamplerSelection(PaperTickSampler(), null)
        }
        // Legacy / Self 共享同一直方图与时钟
        val histogram = MsptHistogram()
        val clock = TickClock(histogram)
        return if (TickReflection.isRecentTpsAvailable()) {
            TickSamplerSelection(LegacyTickSampler(histogram), clock)
        } else {
            TickSamplerSelection(SelfTickSampler(clock), clock)
        }
    }
}

/**
 * 采样器装配结果。
 *
 * @property sampler 选中的 tick 采样器。
 * @property clock 该采样器依赖的 tick 时钟;Paper/Folia 路径无需时钟,为 null。
 */
data class TickSamplerSelection(
    val sampler: ServerTickSampler,
    val clock: TickClock?
)
