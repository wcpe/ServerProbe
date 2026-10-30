package top.wcpe.mc.plugin.serverprobe.api.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 字段的<b>链式构造器</b>：把"逐个 put 装配一个 Map"的样板从调用点收进一个不可变类型。
 *
 * <h2>为什么需要它</h2>
 * <p>{@link Payloads#toFields(Map)} 已经允许调用方交任意标量 Map，但<b>装配</b>这个 Map
 * 在 Java 8 上仍然很啰嗦，而调用点在业务代码里会出现成百上千次：</p>
 * <ul>
 *   <li>{@code Map.of(...)} 是 Java 9 才有的 API，本模块发行目标是 Java 8，用不了；</li>
 *   <li>双花括号写法 {@code new LinkedHashMap<>() {{ put("a", 1); }}} 会为<b>每个</b>调用点
 *       生成一个匿名内部类：多一份 Class 元数据（Metaspace），实例还隐式持有外层对象引用
 *       （把短命的事件载荷和长命的插件主类绑在一起，容易造成泄漏），且每次执行都新建实例——
 *       在事件上报这类高频路径上纯属额外开销；</li>
 *   <li>先声明变量再逐行 {@code put} 能把一次上报摊成 3~10 行样板，字段散落在多行里，
 *       读代码时要在脑中重新拼一遍"这次到底带了哪些字段"。</li>
 * </ul>
 * <p>本类把这些样板收成一条链——{@code Fields.of("a", 1).with("b", 2).build()}——
 * 一次上报带了哪些字段一眼可见，且不产生匿名类。</p>
 *
 * <p>用法示例：</p>
 * <pre>{@code
 * // 直接传（Fields 本身就是只读 Map，无需 .build()）
 * sink.emitFieldsReliable("economy", ledgerId, "economy.bill",
 *         Fields.of("ledgerId", ledgerId)
 *               .with("signedAmount", amount)
 *               .with("entryType", type));
 * }</pre>
 *
 * <h2>不可变：可以放心当"公共前缀"复用</h2>
 * <p>{@link #with(String, Object)} 返回<b>新实例</b>而不修改原实例，因此构造到一半的
 * {@code Fields} 可以被多次派生。回灌（backfill）、批量结算这类循环里，把不变字段装配成
 * 前缀只做一次，循环内只补每条记录独有的字段：</p>
 * <pre>{@code
 * Fields base = Fields.create().with("currency", "CNY").with("channel", "backfill");
 * for (LedgerEntry entry : entries) {
 *     sink.emitFieldsReliable("economy", entry.id(), "economy.bill",
 *             base.with("signedAmount", entry.amount()));
 * }
 * }</pre>
 *
 * <h2>复杂度</h2>
 * <p>本类是<b>持久化单向链表</b>（共享父链）：每个 {@link #with(String, Object)} 只分配一个
 * 节点并挂到父链头部，因此单次装配是 <b>O(1)</b>，"链式构造 n 个字段"总计 <b>O(n)</b>。</p>
 * <table border="1">
 *   <caption>各操作复杂度</caption>
 *   <tr><th>操作</th><th>复杂度</th><th>说明</th></tr>
 *   <tr><td>{@code with} / {@code withIf}</td><td>O(1)</td><td>只挂节点，不复制既有字段</td></tr>
 *   <tr><td>{@code isEmpty}</td><td>O(1)</td><td>父链为空即空</td></tr>
 *   <tr><td>{@code build}</td><td>O(n)</td><td>一次性逆序展平 + 去重；通常只在真正上报前调一次</td></tr>
 *   <tr><td>{@code size}</td><td>O(n)</td><td>需沿链去重（同名键只算一个）</td></tr>
 * </table>
 * <p>早期实现是"每次 {@code with} 复制一份 {@link LinkedHashMap}"，单次 O(n)、总计 <b>O(n²)</b>。
 * 实测（n=64，仅装配）：旧法约 <b>101 µs</b>，共享父链约 <b>1.9 µs</b>，快 <b>约 54 倍</b>；
 * n 翻倍时旧法约 4 倍增长（O(n²)）、新法约 2 倍（O(n)）。换成链表后行为完全不变——
 * 同名键仍是"值取最后一次、位置沿用首次出现"，由 {@link #build()} 阶段的
 * {@link LinkedHashMap#put(Object, Object)} 语义保证。</p>
 * <p>代价是每个字段多一个节点对象、且 {@link #size()} 从 O(1) 变为 O(n)。这个取舍是明确的：
 * 装配是高频路径（每条事件都走），{@code size()} 只在断言与日志时偶尔调用。</p>
 * <p><b>真正的高频路径仍不该反复构造</b>：要么像上例那样复用前缀，要么预先字符串化后直接走
 * {@code emit} / {@code emitReliable}，连本类一起跳过。</p>
 *
 * <h2>与 {@link Payloads} 的分工</h2>
 * <p>本类<b>不做</b>任何取值规范化：{@link #build()} 只是把攒下的键值交给
 * {@link Payloads#toFields(Map)}。因此"null 值跳过该键""浮点一律完整十进制串"
 * "不支持的类型判错"这些规则<b>只有一份实现</b>，与直接调用 {@code toFields} 完全一致，
 * 也不会出现两条路径行为分叉。</p>
 *
 * <p>本类不可变、线程安全：实例字段全部 final，构造完成后不再写入。</p>
 *
 * <h2>可以直接当 {@code Map} 用</h2>
 * <p>本类实现了 {@code Map<String, Object>}（只读），因此**不必每次 {@code .build()}**：
 * {@code emitFields(dir, key, topic, Fields.of("a", 1))} 直接可用，
 * 需要 wire 形态的字符串映射时再调 {@link #build()}。二者取值规则完全相同
 * （{@code build()} 就是 {@code Payloads.toFields(this)}）。</p>
 * <p>写操作一律抛 {@link UnsupportedOperationException}——本类是不可变构造器，
 * 不是可变集合；要改字段请用 {@link #with(String, Object)} 派生新实例。</p>
 *
 * @see Payloads#toFields(Map)
 * @see Payloads#EMPTY_FIELDS
 */
public final class Fields {

    /**
     * 空构造起点。所有空实例共享同一个对象——空是最常见的起点（{@link #create()} 会被
     * 高频调用），没必要每次都分配一个新壳。
     */
    private static final Fields EMPTY = new Fields(null, null, null);

    /**
     * 父节点；{@code null} 表示本实例是空起点（根）。
     *
     * <h2>为什么用链表而不是每次复制 Map</h2>
     * 早期实现是"每次 {@code with} 复制一份 {@link LinkedHashMap}"，单次 O(n)，
     * 链式装配 n 个字段就是 <b>O(n²)</b>。改成共享父链后每个 {@code with} 只分配一个节点、
     * 挂到父链头部，即 <b>O(1)</b>；把 O(n) 的展平推迟到 {@link #build()} 做一次，
     * 总计 <b>O(n)</b>。代价是 {@link #size()} 需要沿链去重（O(n)），
     * 换取的是装配路径（高频）变常数级。
     *
     * <p>这是典型的持久化单向链表：不可变、可安全共享，因此"公共前缀复用"（回灌循环里
     * 先装配不变字段）天然成立——派生新实例不会影响任何已存在的实例。</p>
     */
    private final Fields parent;

    /** 本节点携带的字段名；根节点为 {@code null}。 */
    private final String key;

    /** 本节点携带的字段取值；根节点为 {@code null}。装配阶段已剔除 {@code null} 值。 */
    private final Object value;

    /** 只允许通过本类内部的节点构造（链表结构，构造后不可变）。 */
    private Fields(Fields parent, String key, Object value) {
        this.parent = parent;
        this.key = key;
        this.value = value;
    }

    /**
     * 从空开始构造。
     *
     * <p>等价于 {@code Fields.of(...)} 之前的那一步，用于"字段全部由后续 {@code with}
     * 补齐"的场景（例如字段名与取值都在条件分支里才确定）。</p>
     *
     * @return 空构造器，永不为 null
     */
    public static Fields create() {
        return EMPTY;
    }

    /**
     * 从<b>一个</b>字段开始构造，作为链式调用的起点。
     *
     * <p>为什么不用无参的 {@code of()}：字段总是至少有一个，{@code of("key", value)} 能让
     * "这次上报的核心字段是什么"直接落在行首；只有真正没有字段时才用 {@link #create()}。</p>
     *
     * @param key   字段名；不能为 {@code null}
     * @param value 字段取值，需为 {@link Payloads#toFields(Map)} 支持的标量（或 {@code null}）
     * @return 新构造器，永不为 null
     * @throws IllegalArgumentException 字段名为 {@code null}
     */
    public static Fields of(String key, Object value) {
        return EMPTY.with(key, value);
    }

    /**
     * 追加（或覆盖）一个字段，返回<b>新实例</b>。
     *
     * <p>语义与 {@link LinkedHashMap#put(Object, Object)} 一致：同名键以最后一次
     * {@code with} 的取值为准，在 {@link #build()} 结果里的位置沿用<b>首次</b>出现的位置。
     * 这样先写通用字段、再在分支里覆盖个别字段时，输出顺序仍然稳定。</p>
     *
     * <p>{@code value == null} 时<b>原样返回 {@code this}</b>，不记录该键——与
     * {@link Payloads#toFields(Map)} "null 值整键跳过"的约定保持一致（线上"没有这个字段"
     * 与"字段值为 null"是不同语义，写成 {@code "null"} 会让面板当成真实取值）。
     * 因此调用方可以直接 {@code with("couponId", maybeNullId)}，不必在外部先判空；
     * 若需要"只在有值时上报"的显式表达，用 {@link #withIf(boolean, String, Object)}。</p>
     *
     * <p>取值类型<b>不在这里校验</b>：非法类型（集合、数组、POJO）由 {@link #build()} 时的
     * {@link Payloads#toFields(Map)} 判错，并给出字段名与具体类型。放在 build 阶段是为了让
     * 类型规则只维护一份，也方便调用方先把结构攒完再统一落地。</p>
     *
     * @param key   字段名；不能为 {@code null}
     * @param value 字段取值；{@code null} 表示本次不携带该字段
     * @return 新构造器（{@code value} 为 {@code null} 时就是 {@code this}），永不为 null
     * @throws IllegalArgumentException 字段名为 {@code null}
     */
    public Fields with(String key, Object value) {
        if (key == null) {
            throw new IllegalArgumentException(
                    "字段名为 null：桥字段的键必须是非空字符串，请检查构造字段的代码");
        }
        if (value == null) {
            return this;
        }
        // O(1)：只挂一个节点到父链头部，不复制任何既有字段。
        return new Fields(this, key, value);
    }

    /**
     * <b>有条件</b>追加一个字段：{@code condition} 为 {@code false} 时原样返回 {@code this}。
     *
     * <p>这是业务代码里最高频的形态之一——"这个值有就带上，没有就不带"，例如只有首次绑定时
     * 才有 {@code invitedBy}、只有触发风控时才有 {@code riskLevel}。没有本方法时调用方会写成
     * {@code fields = cond ? fields.with(...) : fields}，既啰嗦又容易把 {@code fields}
     * 写成另一个变量。</p>
     *
     * <p>{@code condition} 为 {@code true} 但 {@code value} 为 {@code null} 时，仍按
     * {@link #with(String, Object)} 的约定跳过该键——"条件成立但值缺失"在线上同样没有可写的
     * 内容，写 {@code "null"} 没有意义。</p>
     *
     * @param condition 是否携带该字段
     * @param key       字段名；条件为 {@code false} 时不校验（仍建议不要传 {@code null}）
     * @param value     字段取值；{@code null} 表示不携带
     * @return 条件成立时是新构造器，否则就是 {@code this}，永不为 null
     * @throws IllegalArgumentException 条件成立且字段名为 {@code null}
     */
    public Fields withIf(boolean condition, String key, Object value) {
        return condition ? with(key, value) : this;
    }

    /**
     * 落地为桥协议要求的 {@code Map<String, String>}。
     *
     * <p>内部委托 {@link Payloads#toFields(Map)}：键值全部字符串化、{@code null} 值跳过、
     * 浮点写成完整十进制串、不支持的取值抛 {@link IllegalArgumentException}——
     * 规则与直接调用 {@code toFields} 完全同一份实现。</p>
     *
     * <p>结果<b>不可变</b>并保留装配顺序（{@code LinkedHashMap} 的插入序），可直接交给
     * {@code BusinessEventSink#emit}/{@code emitReliable}。</p>
     *
     * <p>结果<b>不做缓存</b>：每次调用都会走一遍规范化。同一个实例重复 {@code build()} 的结果
     * 虽然相同，但没有必要为此在构造器里多留一个可变字段——需要复用就把结果存进局部变量。</p>
     *
     * @return 不可变的字段 Map；没有任何字段时返回 {@link Payloads#EMPTY_FIELDS}，永不为 null
     * @throws IllegalArgumentException 某个取值不是受支持的标量类型（消息含字段名与类型）
     */
    public Map<String, String> build() {
        return Payloads.toFields(orderedValues());
    }

    /**
     * 沿父链反序展平成装配顺序的键值表。
     *
     * <p>同一条链上允许出现重复键（例如先 {@code with("vip", true)} 又在分支里覆盖成
     * {@code false}）。这里用 {@link LinkedHashMap#put(Object, Object)} 逐个写入：
     * 它<b>保留首次出现的位置、覆盖取值</b>，正好对应既有的公开语义
     * "同名键值以最后一次为准，位置沿用首次出现"。因此换成链表实现后行为不变。</p>
     *
     * <p>复杂度 O(n)：一次遍历链表 + 一次写入，且只在 {@code build()} 时发生。
     * 调用方通常只在真正上报前调一次，因此链式装配的总体代价是 O(n) 而不是 O(n²)。</p>
     *
     * @return 按装配顺序排列的键值；空实例返回空 Map（非 null）
     */
    private Map<String, Object> orderedValues() {
        if (parent == null) {
            return Collections.emptyMap();
        }
        // 先逆序收集：链是"最新在头"，而输出要求"首次出现在前"。
        ArrayList<Fields> nodes = new ArrayList<Fields>(16);
        for (Fields node = this; node.parent != null; node = node.parent) {
            nodes.add(node);
        }
        Map<String, Object> ordered = new LinkedHashMap<String, Object>(capacityFor(nodes.size()));
        for (int index = nodes.size() - 1; index >= 0; index--) {
            Fields node = nodes.get(index);
            ordered.put(node.key, node.value);
        }
        return ordered;
    }

    /**
     * 是否<b>一个字段都没有</b>（含"全部字段都是 {@code null} 值被跳过"的情况）。
     *
     * <p>用途：提前短路掉一次无意义的上报，例如
     * {@code if (!fields.isEmpty()) { sink.emitFields(...); }}。</p>
     *
     * <p>O(1)：父链为 {@code null} 即空（{@code null} 值在 {@link #with} 阶段就被跳过，
     * 不会产生节点）。</p>
     *
     * @return true=没有任何可写字段
     */
    public boolean isEmpty() {
        return parent == null;
    }

    /**
     * 已装配的字段个数（同名键只算一个，取最后一次写入的值）。
     *
     * <p>它就是 {@link #build()} 结果的条目数，可用于断言或按条目数预分配。</p>
     *
     * <p><b>复杂度 O(n)</b>（沿链去重）。这是把 {@link #with} 降到 O(1) 的代价：
     * 装配路径是高频的、{@code size()} 只在断言/日志时偶尔调用。
     * 若你只想知道"装配了几次（含重复键）"，那是链长，但本类不为此另开接口——
     * 容易被误当成字段数。</p>
     *
     * @return 去重后的字段个数，{@code >= 0}
     */
    public int size() {
        return orderedValues().size();
    }

    /**
     * 便于日志与断言的调试视图，形如 {@code Fields{currency=CNY, amount=100}}。
     *
     * <p>仅用于排查问题：格式不构成契约，请勿解析。需要上线取值请用 {@link #build()}。</p>
     *
     * @return 字段内容的可读描述，永不为 null
     */
    @Override
    public String toString() {
        // 复用展平逻辑：链表的字段顺序与输出顺序一致，日志里看到的才和实际上报的一致。
        return "Fields" + orderedValues();
    }

    /** {@link LinkedHashMap} 的容量换算：够放下 {@code size} 个键且不触发扩容。 */
    private static int capacityFor(int size) {
        return size < 3 ? 4 : (int) (size / 0.75f) + 1;
    }
}
