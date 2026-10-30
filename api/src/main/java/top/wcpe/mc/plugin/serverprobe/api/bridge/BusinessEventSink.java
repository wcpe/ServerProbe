package top.wcpe.mc.plugin.serverprobe.api.bridge;

import java.util.Map;

import top.wcpe.mc.plugin.serverprobe.api.protocol.Fields;
import top.wcpe.mc.plugin.serverprobe.api.protocol.Payloads;

/**
 * 业务事件上报入口。
 *
 * <p>经 {@code ServerProbeAPI.eventSink()} 取得<b>稳定代理</b>（可缓存为字段），永不返回 null。
 * 访问器名与 {@code BridgeServices.eventSink()} 同名，不另立别名。</p>
 *
 * <p>所有方法线程安全、永不返回 null；运行期<b>永不抛异常</b>（失败一律以 false 表达）。
 * 唯一例外是 {@link #emitFields} / {@link #emitFieldsReliable} 在"字段无法标量化"时抛出的
 * {@link IllegalArgumentException}——那是调用方编程错误，不是运行期故障，理由见各方法 javadoc。</p>
 *
 * <h2>为什么分两个入口而不是一个带布尔参数</h2>
 * <p>可丢性必须由<b>调用方入口</b>唯一决定，探针不按 domain / event 名猜测业务语义——
 * 一张"按域名推断可丢"的规则表会让第三方 Provider 在无意识中丢掉结算事件。
 * 一旦选定即不可逆：{@link #emitReliable} 不会因队列压力降级为可丢，
 * {@link #emit} 也不会升级为可靠。</p>
 *
 * <h2>为什么字段值是 String 而不是泛型 / Object</h2>
 * <p>这不是"强制类型转换"，调用方无需任何 cast，交给 {@code Map<String, String>} 即可；
 * 但它确实要求调用方自己把数字与布尔<b>字符串化</b>（{@code String.valueOf(...)}）。这是刻意取舍：</p>
 * <ul>
 *   <li><b>金额必须走字符串</b>：JSON 数字在跨语言往返时会丢精度（{@code double} 只有
 *       约 16 位有效数字），账单金额一旦失真无法补救。历史上经济集成的
 *       {@code signedAmount}/{@code balanceAfter} 就是按字符串上报的。</li>
 *   <li><b>避免类型歧义</b>：{@code 1} 是数字还是字符串、{@code 0/1} 还是 {@code true/false}，
 *       若允许 {@code Object} 则每个业务方各写一套，面板侧要为每种拼写做兼容。</li>
 *   <li><b>编码零转换</b>：字符串直接进 JSON，不需要逐值做类型判定与转换；
 *       探针只做承载与转发，不解释业务语义。</li>
 * </ul>
 * <p>因此"泛型自动转换"在这里是<b>负收益</b>：它要么引入精度风险（数字），
 * 要么在热路径上多一层类型分派（每帧每字段一次）。若你需要强类型，请在插件侧用自己的
 * DTO 承接，落库/上报前一次性 {@code toString()}，不要在每次上报时反复构造。</p>
 *
 * <p><b>与 {@link #emitFields} 的关系</b>：那个便利入口不推翻本节结论，只是把"一次性
 * {@code toString()}"这一步替调用方做了——它适合装配点、命令响应、低频事件这类冷路径，
 * 代价是每个字段一次类型判定。每帧都发的事件仍应预先字符串化后走本方法。</p>
 *
 * <h2>大批量回灌（backfill）必须自己处理背压</h2>
 * <p>典型场景：经济插件从本地库查出水位线以上的数万条历史账单，逐条同步上来。此时
 * <b>生产速率会远超单条 WebSocket 连接的写出速率</b>，实测（10 万条、零停顿全速灌入）
 * 有约 2.8% 会被拒。因此：</p>
 * <ul>
 *   <li><b>必须检查返回值</b>：{@link #emitReliable} 返回 {@code false} 表示<b>未被受理</b>
 *       （事件没进可靠队列），探针不会替你重放——不检查就等于静默丢账单。</li>
 *   <li><b>失败后短暂让出 CPU 再重试</b>，不要忙等（忙等会把发送线程饿死），也不要
 *       一次把整批灌进来。按批（如 500~1000 条）推进并在批间观察
 *       {@code bridge_status.sender.reliableQueue} 深度，比固定 sleep 更稳。</li>
 *   <li><b>监控 {@code bridge_status.sender.spillDropped}</b>：落盘缓冲溢出时探针会丢弃
 *       <b>已被受理</b>的最旧事件（调用方当时收到的是 true）。该值持续 &gt; 0 说明回灌流量
 *       已超过单条连接的承载能力，应减小批次或分时段回灌。</li>
 *   <li>回灌完成后建议对账（按 {@code dedupKey} 让面板侧确认条数），这是唯一能发现
 *       "极端情况下仍有丢失"的手段。</li>
 * </ul>
 *
 * <h2>：{@code seq} 与 {@code dedupKey} 互补，不能互相替代</h2>
 * <ul>
 *   <li>{@code dedupKey} 解决「<b>同一条</b>事件重复到达」→ 幂等；</li>
 *   <li>{@code seq} 解决「<b>两条</b>事件之间有没有少」→ 完整性（定位）。</li>
 * </ul>
 * <p>{@code seq} 是 <b>per-domain</b> 的：每个业务域各有独立的递增序列，不同域之间互不干扰，
 * <b>不是</b>全局单调。它由探针在事件入队时分配（{@link #emitReliable(String, String, String, Map)}），
 * 随帧一起透传到对端。补充说明三点：</p>
 * <ul>
 *   <li><b>可丢事件不占序列</b>：{@link #emit} 分配不到 {@code seq}。否则可丢事件一被丢弃，
 *       可靠事件的序列里就会出现永远补不上的空洞，"有缺口 = 真的漏了"这条判据立刻失效。</li>
 *   <li><b>重启后续接而不是重置</b>：探针把每域已分配到的最大 {@code seq} 持久化，
 *       重启后从 {@code max + 1} 继续。于是下游可以安心把 {@code seq} 当位点用。
 *       若持久化文件丢失（首次启动 / 目录被清），序列会从一个巨大的起点
 *       （{@code 当前时间戳毫秒 << 20}）开始，保证<b>不会</b>与任何历史值冲突。</li>
 *   <li><b>补发保持原 seq</b>：从落盘缓冲重放时沿用原序号，不重新分配——
 *       重新分配会让"补发"在序列上看起来像新事件，而下游正靠 {@code seq} 判断缺口。</li>
 * </ul>
 * <p>探针只保证 {@code seq}/{@code seqEpoch} 准确，<b>不规定</b>业务载荷里放什么字段
 * （那是业务台与插件的约定）。</p>
 */
public interface BusinessEventSink {

    /**
     * 上报一个<b>可丢</b>业务事件（进度刷新、排行榜快照）。
     *
     * <p>走 best-effort 通道：队列满即丢并计数（{@code bridge_status.sender.bestEffortDropped}）。
     * 玩家 {@code player_join} / {@code chat} 这类事件也只能走本入口——它们没有业务幂等键，
     * 派生不出稳定的 {@code dedupKey}，而用随机 UUID 兜一个去重键会让 spill 回放的重发被 JM
     * 当成新事件重复入库（比丢一条更糟）。</p>
     *
     * @param domain   业务域；空串视为无效，返回 false
     * @param dedupKey 去重键；可空（可丢通道不保证去重）
     * @param topic    主题；null 或空串表示未声明主题（有订阅时仍会放行，见主题订阅规格 §3.5）
     * @param fields   结构化字段，键值均为字符串；null 视为空 Map
     * @return true=已受理（<b>含"被主题滤镜判定为无需上送"</b>）；false=通道饱和或探针不可用
     */
    boolean emit(String domain, String dedupKey, String topic, Map<String, String> fields);

    /**
     * 上报一个<b>不可丢</b>业务事件（结算、状态变更）。
     *
     * <p>走可靠通道：队列满时先落盘兜底，重连后回放（至少一次）。
     * <b>要求 dedupKey 非空</b>：可靠通道依赖 JM 按 {@code (domain, dedupKey)} 幂等去重，
     * 空 dedupKey 会被拒绝并返回 false——<b>不静默降级为可丢</b>，
     * 因为降级会让调用方误以为这条事件可靠，而它实际已经不受任何回放兜底保护。</p>
     *
     * @param domain   业务域；空串视为无效，返回 false
     * @param dedupKey 去重键；<b>必须非空</b>，为空返回 false 并告警一次
     * @param topic    主题；null 或空串表示未声明主题（有订阅时仍会放行，见主题订阅规格 §3.5）
     * @param fields   结构化字段，键值均为字符串；null 视为空 Map
     * @return true=已受理（含"已落盘待回放"）；false=可靠通道饱和，<b>调用方须转为业务失败</b>
     */
    boolean emitReliable(String domain, String dedupKey, String topic, Map<String, String> fields);

    /**
     * 上报一个<b>不可丢</b>业务事件，并<b>由调用方指定</b>{@code seq}（补发场景专用）。
     *
     * <p>适用于"探针重启后业务插件自己回灌历史"这类场景：插件本地已经存了当时分配的序号，
     * 重新上报时希望下游看到的仍是<b>同一批位点</b>，而不是一串崭新的号。
     * 从探针落盘缓冲自动重放的事件<b>不走本入口</b>——那条路径在探针内部直接沿用原序号，
     * 不会回到业务代码。</p>
     *
     * <p><b>本方法不参与序列推进</b>：传入的 {@code seq} 只写进帧，不会抬高该域后续
     * {@link #emitReliable(String, String, String, Map)} 的分配起点。
     * 理由：调用方给的多半是<b>过去的</b>号，若用它推进序列，下一个新事件就会"撞"进历史区间，
     * 下游按序位判缺口时会把老事件当成漏掉的新事件。</p>
     *
     * <p><b>探针无法校验 {@code seq} 的正确性</b>：它只知道"调用方说这是第 N 条"。
     * 传错号（重复、倒退）的后果由调用方承担——下游会把它当真。</p>
     *
     * <p>其余语义（可靠通道、强制非空 {@code dedupKey}、可丢性不可降级、永不抛）与
     * {@link #emitReliable(String, String, String, Map)} 完全一致。</p>
     *
     * <p><b>为什么是 {@code default} 而不是抽象方法</b>：本接口是<b>第三方可直接实现</b>的
     * （见 {@code BridgeServices#eventSink()}）。加一个抽象方法会让所有既有实现
     * 在升级探针后 {@code AbstractMethodError}，而它们的功能本身并没有坏。
     * 默认实现退化为"忽略 seq 的可靠上报"——序号丢失（下游会按缺口处理），但事件不会丢。</p>
     *
     * @param domain   业务域；空串视为无效，返回 false
     * @param dedupKey 去重键；<b>必须非空</b>，为空返回 false 并告警一次
     * @param topic    主题；null 或空串表示未声明主题
     * @param fields   结构化字段，键值均为字符串；null 视为空 Map
     * @param seq      调用方指定的序号（per-domain）；{@code <= 0} 视为无效并返回 false
     * @return true=已受理（含"已落盘待回放"）；false=可靠通道饱和或参数无效，
     *     <b>调用方须转为业务失败</b>
     */
    default boolean emitReliable(
            String domain, String dedupKey, String topic, Map<String, String> fields, long seq) {
        return emitReliable(domain, dedupKey, topic, fields);
    }

    /**
     * 上报一个<b>可丢</b>业务事件，字段用任意标量 Map 给出（{@link #emit} 的便利入口）。
     *
     * <p>内部调 {@link Payloads#toFields(Map)} 做标量化，再转交
     * {@link #emit(String, String, String, Map)}——可丢性、去重、主题滤镜等语义完全不变。</p>
     *
     * <p><b>为什么叫 {@code emitFields} 而不是重载 {@code emit}</b>：已有方法的字段类型是
     * {@code Map<String, String>}，若再加一个 {@code Map<String, ?>} 版本，两者擦除后签名相同
     * （都是 {@code Map}），无法共存；改叫 {@code emitFields} 既避开冲突，也把"这个入口会做
     * 字段规范化"表达在名字里。</p>
     *
     * <p>取值规则全部由 {@link Payloads#toFields(Map)} 定义，要点：{@code null} 值跳过该键、
     * 字符串原样、数字/布尔/枚举字符串化、浮点一律写成完整十进制串（不出现科学计数法）；
     * <b>大额金额仍建议用 {@code String} 或 {@code BigDecimal}</b>——那解决的是有效位数，
     * 不是格式。</p>
     *
     * <p>字段较多时用 {@link top.wcpe.mc.plugin.serverprobe.api.protocol.Fields} 链式装配
     * （{@code Fields.of("a", 1).with("b", 2).build()}），免去手写 Map 的样板。</p>
     *
     * <p><b>嵌套结构（集合、数组、POJO）不支持</b>：请自行序列化成字符串再传，否则会抛
     * {@link IllegalArgumentException} 并点名是哪个字段。异常类型与 {@link #emit} 的
     * "永不抛"之间的关系见 {@link BusinessHost#dispatch(String, String, Map)} 的说明——
     * 同属调用方编程错误，故快速失败。</p>
     *
     * @param domain   业务域；空串视为无效，返回 false
     * @param dedupKey 去重键；可空（可丢通道不保证去重）
     * @param topic    主题；null 或空串表示未声明主题
     * @param fields   结构化字段，取值需为受支持的标量；null 视为空 Map
     * @return true=已受理；false=通道饱和或探针不可用
     * @throws IllegalArgumentException 某个字段不是受支持的标量类型，或键为 null
     */
    default boolean emitFields(String domain, String dedupKey, String topic, Map<String, ?> fields) {
        return emit(domain, dedupKey, topic, Payloads.toFields(fields));
    }

    /**
     * 上报一个<b>不可丢</b>业务事件，字段用任意标量 Map 给出（{@link #emitReliable} 的便利入口）。
     *
     * <p>内部调 {@link Payloads#toFields(Map)} 做标量化，再转交
     * {@link #emitReliable(String, String, String, Map)}。因此
     * <b>{@code dedupKey} 仍必须非空</b>：为空时同样返回 false，<b>不会</b>因经过本入口而
     * 降级为可丢通道。</p>
     *
     * <p>取值规则、金额精度、"嵌套结构请自行序列化"这几点与
     * {@link #emitFields} 完全一致。</p>
     *
     * @param domain   业务域；空串视为无效，返回 false
     * @param dedupKey 去重键；<b>必须非空</b>，为空返回 false 并告警一次
     * @param topic    主题；null 或空串表示未声明主题
     * @param fields   结构化字段，取值需为受支持的标量；null 视为空 Map
     * @return true=已受理（含"已落盘待回放"）；false=可靠通道饱和，<b>调用方须转为业务失败</b>
     * @throws IllegalArgumentException 某个字段不是受支持的标量类型，或键为 null
     */
    default boolean emitFieldsReliable(String domain, String dedupKey, String topic, Map<String, ?> fields) {
        return emitReliable(domain, dedupKey, topic, Payloads.toFields(fields));
    }

    /**
     * 上报一个<b>可丢</b>业务事件，字段用 {@link Fields} 构造器直接给出（{@link #emitFields} 的免样板重载）。
     *
     * <p>存在意义只有一条：让调用点省掉 {@code .build()}。</p>
     * <pre>{@code
     * sink.emitFields("economy", "tx-1", "economy.progress",
     *         Fields.of("activityId", id).with("progress", pct));
     * }</pre>
     *
     * <p>其余语义（取值规范化、金额精度、{@code null} 值跳过、不支持类型抛
     * {@link IllegalArgumentException}）与 {@link #emitFields} 完全一致——
     * 它最终就是 {@code emitFields(..., fields.build())}。</p>
     *
     * @param domain   业务域；空串视为无效，返回 false
     * @param dedupKey 去重键；可空（可丢通道不保证去重）
     * @param topic    主题；null 或空串表示未声明主题
     * @param fields   字段构造器；{@code null} 视为空字段
     * @return true=已受理；false=通道饱和或探针不可用
     * @throws IllegalArgumentException 某个字段不是受支持的标量类型，或键为 null
     */
    default boolean emitFields(String domain, String dedupKey, String topic, Fields fields) {
        return emitFields(domain, dedupKey, topic, fields == null ? null : fields.build());
    }

    /**
     * 上报一个<b>不可丢</b>业务事件，字段用 {@link Fields} 构造器直接给出。
     *
     * <p>{@code dedupKey} 仍<b>必须非空</b>：为空时返回 false 并告警一次，
     * <b>不会</b>因经过本入口而降级为可丢通道。</p>
     *
     * @param domain   业务域；空串视为无效，返回 false
     * @param dedupKey 去重键；<b>必须非空</b>
     * @param topic    主题；null 或空串表示未声明主题
     * @param fields   字段构造器；{@code null} 视为空字段
     * @return true=已受理（含"已落盘待回放"）；false=可靠通道饱和，<b>调用方须转为业务失败</b>
     * @throws IllegalArgumentException 某个字段不是受支持的标量类型，或键为 null
     */
    default boolean emitFieldsReliable(String domain, String dedupKey, String topic, Fields fields) {
        return emitFieldsReliable(domain, dedupKey, topic, fields == null ? null : fields.build());
    }

    // ───────────────────────── 强语义入口（推荐新代码使用） ─────────────────────────

    /**
     * 上报一个<b>可丢</b>业务事件，返回<b>带原因的受理凭据</b>。
     *
     * <p>与 {@link #emit} 的唯一差别是返回类型：{@code boolean} 只能告诉你“没受理”，
     * 而 {@link EmitReceipt} 还能告诉你<b>为什么</b>，从而决定该不该重试。</p>
     *
     * <p><b>为什么新增方法而不是改 {@link #emit} 的返回类型</b>：本接口允许第三方直接实现，
     * 改返回类型是源码级不兼容；而新增一个 {@code default} 方法对既有实现零影响。
     * 默认实现从 {@link #emit} 的布尔值反推原因，因此<b>未覆写它的实现也能拿到正确语义</b>——
     * 探针自己的实现覆写它，给出精确原因与序号。</p>
     *
     * @param domain   业务域
     * @param dedupKey 去重键；可空
     * @param topic    主题；null 或空串表示未声明主题
     * @param fields   结构化字段；null 视为空 Map
     * @return 受理凭据，永不为 null
     */
    default EmitReceipt emitReceipt(
            String domain, String dedupKey, String topic, Map<String, String> fields) {
        if (emit(domain, dedupKey, topic, fields)) {
            return EmitReceipt.accepted();
        }
        return EmitReceipt.rejected(EmitReceipt.inferRejection(domain, dedupKey));
    }

    /**
     * 上报一个<b>不可丢</b>业务事件，返回<b>带原因与序号</b>的受理凭据。
     *
     * <p>相比 {@link #emitReliable(String, String, String, Map)}，受理凭据会带上探针分配的
     * {@code seq}/{@code seqEpoch}，业务侧可拿它当本地水位线，不必再查 {@code bridge_status}。</p>
     *
     * @param domain   业务域
     * @param dedupKey 去重键；<b>必须非空</b>
     * @param topic    主题；null 或空串表示未声明主题
     * @param fields   结构化字段；null 视为空 Map
     * @return 受理凭据（已受理时 {@code seq > 0}），永不为 null
     */
    default EmitReceipt emitReliableReceipt(
            String domain, String dedupKey, String topic, Map<String, String> fields) {
        if (emitReliable(domain, dedupKey, topic, fields)) {
            return EmitReceipt.accepted();
        }
        return EmitReceipt.rejected(EmitReceipt.inferRejection(domain, dedupKey));
    }
}
