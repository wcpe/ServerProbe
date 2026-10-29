package top.wcpe.mc.plugin.serverprobe.api.bridge;

/**
 * 动作的<b>风险等级</b>。
 *
 * <p>下游业务台据此决定是否强制二次确认（见 JM）。<b>Provider 必须诚实声明</b>：
 * 标 {@link #READ} 却写数据会让风险控制彻底失效——面板会把它当查询直接执行。</p>
 *
 * <p>本枚举刻意只有三级：再多分级需要下游逐级定义策略，而当前只有「是否需确认」这一个真实差别。
 * 未声明（{@code null}）时探针按 {@link #WRITE} 处理，理由见 {@link LegacyBusinessProviderAdapter}
 * 的类注释：安全优先。</p>
 */
public enum RiskLevel {

    /** 只读，无副作用：可任意重复执行，不需二次确认。 */
    READ,

    /** 有副作用但可重复执行（幂等写）：需要确认，重发不产生额外影响。 */
    WRITE,

    /** 有副作用且不可逆（如删除、清空、结算发放）：必须二次确认，且调用方应考虑强制填写操作原因。 */
    DESTRUCTIVE
}
