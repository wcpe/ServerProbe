package top.wcpe.mc.plugin.serverprobe.api.protocol;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务载荷的<b>通用值规范化</b>工具：把调用方手里的结构化键值整理成桥协议要求的两种落地形态。
 *
 * <h2>只做通用整理，不解释任何业务语义</h2>
 * <p>本类不知道"金额""货币""物品""余额"这些词，也不做任何按域名的分支判断——它只回答一个
 * 通用问题：<b>某个 Java 值在线上协议里应该长什么样</b>。因此它既不会给字段改名、也不会
 * 校验取值合法性，更不会因为某个域被调用而走不同代码路径。业务语义始终留在
 * {@code BusinessProvider} 与业务面板之间。</p>
 *
 * <h2>两个出口，对应两种线上形态</h2>
 * <ul>
 *   <li>{@link #toFields(Map)} → {@code Map<String, String>}：业务事件帧的结构化字段，
 *       协议规定键值均为字符串（见 {@code BusinessEventSink}）；</li>
 *   <li>{@link #toJson(Map)} → JSON 对象文本：命令帧的 {@code payloadJson}，
 *       由 {@code BusinessHost#dispatch(String, String, Map)} 等重载使用。</li>
 * </ul>
 *
 * <h2>精度：所有数字一律写成完整十进制串，不出现科学计数法</h2>
 * <p>JSON 数字在跨语言往返时会丢精度，很多实现还会把大额写成科学计数法
 * （{@code 1.0E+18}），账单金额一旦失真无法补救。因此本类在<b>数字的文本形态</b>上
 * 做了统一处理：</p>
 * <ul>
 *   <li>{@code BigDecimal} / {@code BigInteger} 一律走 {@code toPlainString()}，
 *       <b>绝不输出指数形式</b>；</li>
 *   <li>{@code Double} / {@code Float} 同样展开成完整十进制串：{@code 1e18} 写成
 *       {@code 1000000000000000000}，{@code 1e-7} 写成 {@code 0.0000001}，
 *       {@code 0.1f} 写成 {@code 0.1}（不是 {@code 0.10000000149011612}）。
 *       指数展开带来的多余尾零会被去掉，但 {@code 1.0} 仍写成 {@code 1.0}——不把浮点
 *       悄悄降级成整数形态。两个出口（{@link #toFields(Map)} / {@link #toJson(Map)}）
 *       行为一致；</li>
 *   <li>但<b>文本形态修不了 double 本身的精度上限</b>：超过 2<sup>53</sup> 的整数、或需要
 *       多位小数的金额，在进入本类之前就已经丢过精度了。这类值请在上游用
 *       {@code BigDecimal} 计算，或直接用 {@code String} 传入——字符串原样写出，
 *       一个字节都不经过浮点。</li>
 * </ul>
 *
 * <h2>装配：冷路径建议用 {@link Fields}</h2>
 * <p>本类只负责"把值规范化"，不负责"把键值攒起来"。Java 8 没有 {@code Map.of}，调用方
 * 手写 Map 要么用双花括号（每个调用点一个匿名类），要么先建变量再多次 {@code put}；
 * 需要链式装配时请用 {@link Fields}：{@code Fields.of("a", 1).with("b", 2).build()}，
 * 它最终仍走本类，规范化规则完全一致。</p>
 *
 * <h2>线程安全与依赖</h2>
 * <p>本类无状态、全部方法为静态纯函数，可并发调用；不依赖任何第三方库（api 模块保持
 * 零运行期依赖），也不引用 core 的任何类型。</p>
 */
public final class Payloads {

    /**
     * 空字段集合（不可变单例），表示"这次上报没有字段"。
     *
     * <p><b>为什么推荐它而不是 {@code null}</b>：</p>
     * <ul>
     *   <li><b>重载歧义</b>：{@code BusinessHost#dispatch(String, String, String)} 与
     *       {@code dispatch(String, String, Map)} 并存，写 {@code dispatch(domain, action, null)}
     *       无法通过编译（{@code null} 同时匹配两个重载）；传本常量则明确落在 Map 重载上。</li>
     *   <li><b>语义明确</b>：{@code null} 读起来像"调用方忘了传"或"探针该用某个默认值"，
     *       而"没有字段"是一个确定的取值。虽然本类把 {@code null} 也当空 Map 处理
     *       （见 {@link #toFields(Map)}），但调用点的可读性差别很大：{@code Payloads.empty()}
     *       一说就清楚，不必让读者去查实现确认 {@code null} 到底会不会炸。</li>
     *   <li><b>零分配</b>：每次 {@code Collections.emptyMap()} 虽然也返回共享实例，但一个
     *       具名常量更能表达"这里就是空载荷"，也便于静态导入
     *       （{@code import static ...Payloads.EMPTY_FIELDS;}）。</li>
     * </ul>
     *
     * <p>不可变：任何 {@code put/remove/clear} 都会抛 {@link UnsupportedOperationException}。
     * 类型是 {@code Map<String, String>}，可直接交给
     * {@code BusinessEventSink#emit}/{@code emitReliable}（它们要的正是这个类型）。</p>
     */
    public static final Map<String, String> EMPTY_FIELDS = Collections.emptyMap();

    /**
     * 写出 JSON 对象时每个条目的<b>粗估</b>字节数，仅用于 {@link StringBuilder} 预分配。
     *
     * <p>取 24 是因为一条 {@code "key":"value"} 的常见规模就在这个量级；估小了会触发扩容，
     * 估大了只是多占一点临时内存，因此宁可略偏大。这里不做精确计算——精确计算本身
     * 就要遍历一遍，等于把序列化做两次。</p>
     */
    private static final int ESTIMATED_ENTRY_BYTES = 24;

    /**
     * JSON 嵌套深度上限。
     *
     * <p>为什么要有：本类用递归写出嵌套结构，而{@code dispatch} 是第三方可调的入口。
     * 若调用方传入一个自引用或刻意做深的 Map，递归会把栈打穿并抛 {@code StackOverflowError}——
     * 那是 {@code Error}，调用方的 {@code catch (Exception)} 兜不住，可能直接打死线程。
     * 在这里换成 {@code IllegalArgumentException}（明确的调用方错误）比让 JVM 栈溢出更可控。
     * 32 层对任何真实业务载荷都足够宽裕。</p>
     */
    private static final int MAX_DEPTH = 32;

    /** 控制字符的十六进制数字表（只用于 {@code \\u00XX} 转义，取值范围 0x00~0x1F）。 */
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    /** 纯工具类，不可实例化。 */
    private Payloads() {
    }

    /**
     * 空载荷的方法形态，等价于 {@link #EMPTY_FIELDS}。
     *
     * <p>存在的理由只有一个：<b>调用点读起来更像一句话</b>。{@code dispatch(domain, action,
     * Payloads.empty())} 比 {@code EMPTY_FIELDS} 更贴近"这里没有参数"的语义，也省掉了
     * 静态导入的考虑；而在需要反复引用的地方（例如一个 {@code static final} 字段）用常量更直接。
     * 两者返回的是<b>同一个</b>不可变实例，可以任意混用。</p>
     *
     * <p>为什么不用 {@code null} 表达空载荷，见 {@link #EMPTY_FIELDS} 的说明：
     * {@code null} 会与 {@code dispatch} 的 {@code String} 重载产生歧义，且语义模糊。</p>
     *
     * @return 不可变的空字段 Map，永不为 null
     */
    public static Map<String, String> empty() {
        return EMPTY_FIELDS;
    }

    /**
     * 把结构化字段规范化为线上协议要求的 {@code Map<String, String>}。
     *
     * <p>逐条规则与理由：</p>
     * <ul>
     *   <li><b>{@code null} 值 → 跳过该键</b>：线上"没有这个字段"与"字段值为 null"是
     *       不同的语义，而桥字段只承载字符串。若把 {@code null} 写成字面量 {@code "null"}，
     *       面板侧会把它当成真实取值（一条 {@code "balance":"null"} 的账单比缺字段更糟）。
     *       缺省即为空，调用方也就无需先做一轮判空。</li>
     *   <li><b>{@code String} → 原样返回</b>：不做 trim、不做大小写改写、不做数字格式重排。
     *       本类只承载，不解释；字符串可能已经是签名、JSON 片段或带前导零的编号，
     *       任何"顺手规范化"都会破坏它们。</li>
     *   <li><b>{@code Double} / {@code Float} → 完整十进制串</b>：走
     *       {@link BigDecimal#valueOf(double)}（{@code Float} 先取自身的 {@code toString()}）
     *       再 {@code toPlainString()}，因此 {@code 1e18} 写成 {@code 1000000000000000000}，
     *       {@code 1e-7} 写成 {@code 0.0000001}，<b>不会出现 {@code 1.0E18}</b>。
     *       NaN 与无穷判错——见下方 {@code @throws}。</li>
     *   <li><b>其余 {@code Number} / {@code Boolean} / {@code char} →
     *       {@code String.valueOf(...)}</b>（{@code Enum} 走 {@link Enum#name()}）：
     *       这些都是无歧义的标量（{@code Long}/{@code Integer} 等的 {@code toString()} 本就是
     *       纯十进制），转字符串不会引入精度问题。{@code Enum} 用 {@code name()}
     *       而不是 {@code toString()}，因为后者常被业务覆写成本地化文案，
     *       线上取值必须稳定。</li>
     *   <li><b>{@code BigDecimal} / {@code BigInteger} → {@code toPlainString()}</b>：
     *       {@link BigDecimal#toString()} 在指数超出范围时会输出 {@code 1.0E+18}，
     *       金额因此失真；{@code toPlainString()} 永远输出完整的十进制数字串。
     *       {@code BigInteger} 的 {@code toString()} 本身不含指数，故等价。</li>
     *   <li><b>{@code Character} → {@link Character#toString()}</b>：绕过
     *       {@code String.valueOf(char)} 的重载歧义，语义等价。</li>
     *   <li><b>其它类型（集合、数组、POJO…）→ 抛 {@link IllegalArgumentException}</b>：
     *       嵌套结构一旦被 {@code toString()} 成 {@code [a, b]} 或 {@code Foo@1a2b}，
     *       面板侧拿到的就是无法解析的垃圾——而且这种损坏是静默的。宁可当场失败并点名
     *       是哪个键、什么类型，让调用方显式决定序列化格式。</li>
     * </ul>
     *
     * <p>键不允许为 {@code null}（抛 {@link IllegalArgumentException}）：桥字段的键在协议里
     * 定义为字符串，{@code null} 键无法落到线上，且几乎总意味着调用方构造 Map 时已有缺陷，
     * 静默丢弃会掩盖它。</p>
     *
     * @param source 调用方持有的结构化字段；{@code null} 或空 Map 返回 {@link #EMPTY_FIELDS}
     *               （不返回 null）
     * @return 不可变的 {@code Map<String, String>}，保留入参的迭代顺序
     * @throws IllegalArgumentException 存在 {@code null} 键，某个值不是受支持的标量类型，
     *                                  或浮点取值为 NaN/无穷
     */
    public static Map<String, String> toFields(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return EMPTY_FIELDS;
        }
        Map<String, String> fields = new LinkedHashMap<String, String>(capacityFor(source.size()));
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                throw new IllegalArgumentException(
                        "字段名为 null：桥字段的键必须是非空字符串，请检查调用方构造 Map 的代码");
            }
            Object value = entry.getValue();
            if (value == null) {
                // 缺省即"没有这个字段"，不写 "null" 字面量（理由见方法 javadoc）。
                continue;
            }
            fields.put(key, scalarText(key, value));
        }
        return Collections.unmodifiableMap(fields);
    }

    /**
     * 把结构化参数写成 JSON 对象文本（用于 {@code BusinessHost#dispatch} 等重载的
     * {@code payloadJson}）。
     *
     * <p>支持的取值：{@code null}、{@code String}、{@code Number}、{@code Boolean}、
     * {@code Character}、{@code Enum}、{@code Map}、{@code Collection}、数组（含基本类型数组）。
     * 其中：</p>
     * <ul>
     *   <li>{@code BigDecimal} / {@code BigInteger} 走 {@code toPlainString()}，
     *       <b>不会出现科学计数法</b>；</li>
     *   <li>{@code Double} / {@code Float} 同样展开成完整十进制串（{@code 1e18} 写成
     *       {@code 1000000000000000000}、{@code 1e-7} 写成 {@code 0.0000001}、
     *       {@code 0.1f} 写成 {@code 0.1}），与 {@link #toFields(Map)} 完全一致；</li>
     *   <li>{@code Enum} 写出其 {@link Enum#name()} 的字符串形式；</li>
     *   <li>字符串按 JSON 标准转义（{@code "} {@code \} 与 0x00~0x1F 控制字符）；
     *       中文、emoji 等非控制字符原样写出，由上游按 UTF-8 编码——不做多余的
     *       {@code \\uXXXX} 膨胀；</li>
     *   <li>顶层与嵌套 {@code null} 值均写出 JSON 字面量 {@code null}。这与
     *       {@link #toFields(Map)} 跳过 null 的策略不同，原因见下：桥字段只能是字符串，
     *       无法表达 null；而 JSON 本身能表达，把调用方显式传入的 {@code null} 保留下来
     *       才是无损的。</li>
     * </ul>
     *
     * <p><b>{@code null} 或空 Map 等价于 {@code {}}</b>：空载荷写成空对象而不是 {@code null}
     * 或空串，Provider 侧永远拿到一段可解析的 JSON。</p>
     *
     * <p>性能：{@link StringBuilder} 按条目数预分配；无需转义的字符串整段
     * {@code append}，只在遇到 {@code "}、{@code \} 或控制字符时才走分段转义，
     * 因此常规载荷不会退化为逐字符多分支。</p>
     *
     * @param source 调用方持有的结构化参数；{@code null} 或空 Map 返回 {@code "{}"}
     * @return JSON 对象文本，永不为 null
     * @throws IllegalArgumentException 存在 {@code null} 键、不支持的取值类型、
     *                                  非有限浮点数（NaN/无穷），或嵌套深度超过 32 层
     */
    public static String toJson(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return "{}";
        }
        StringBuilder json = new StringBuilder(2 + source.size() * ESTIMATED_ENTRY_BYTES);
        writeObject(json, source, null, 0);
        return json.toString();
    }

    /** 把一个标量转成桥字段文本；不属于受支持的标量时抛异常并点名键与类型。 */
    private static String scalarText(String key, Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Character) {
            return value.toString();
        }
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        // BigDecimal/BigInteger 必须排在 Number 之前：两者的 toString() 行为不同。
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        if (value instanceof BigInteger) {
            return value.toString();
        }
        // 浮点单列一支：默认的 toString() 会写出 1.0E18，面板解析后就是失真值。
        if (value instanceof Double || value instanceof Float) {
            Number number = (Number) value;
            requireFinite(number, key);
            return plainFloatText(number);
        }
        // 其余 Number/Boolean：String.valueOf 走其 toString()，对标量而言是无损的。
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw unsupported(key, value);
    }

    /**
     * 把有限浮点数写成<b>完整十进制串</b>（不使用科学计数法）。
     *
     * <p>为什么用 {@link BigDecimal#valueOf(double)} 而不是 {@code new BigDecimal(double)}：
     * {@code valueOf} 内部先走 {@code Double.toString} 再按十进制字面量解析，得到的是
     * "最短且可往返"的那串数字——{@code 0.1} 就是 {@code 0.1}；而
     * {@code new BigDecimal(0.1)} 取的是 double 的<b>精确二进制值</b>，会展开成
     * {@code 0.1000000000000000055511151231257827...}，把调用方根本没写过的位数也报上去。
     * 随后 {@link BigDecimal#toPlainString()} 只做一件事：把可能的指数形式展开成完整数字串
     * （{@code 1.0E18} → {@code 1000000000000000000}）。</p>
     *
     * <p>最后还要去掉小数部分末尾多余的 0：{@code Double.toString(1e-7)} 是
     * {@code "1.0E-7"}，指数展开后得到 {@code "0.00000010"}，末位那个 0 来自尾数的
     * {@code ".0"}，是表示形式的产物而不是数值信息（{@code 0.0000001} 与它等值且能原样
     * 往返）。去掉之后 {@code 1e-7} 写成 {@code 0.0000001}，而 {@code 1.0} 仍写成
     * {@code 1.0}（保留一位小数，不把浮点悄悄降级成整数形态）。</p>
     *
     * <p>{@code Float} 必须先用它<b>自己的</b> {@code toString()} 取最短表示再解析：
     * 若先经 {@code doubleValue()} 上转，{@code 0.1f} 会变成
     * {@code 0.10000000149011612}——那是 float 的真实二进制值，不是调用方写的那个数。</p>
     *
     * @param value 非 null 的 {@code Double} 或 {@code Float}，且调用方已校验为有限值
     * @return 不含指数的十进制字符串
     */
    private static String plainFloatText(Number value) {
        BigDecimal decimal;
        if (value instanceof Float) {
            // Float.toString(0.1f) = "0.1"，Float.toString(1e20f) = "1.0E20"，交给 BigDecimal 展开。
            decimal = new BigDecimal(value.toString());
        } else {
            decimal = BigDecimal.valueOf(value.doubleValue());
        }
        return trimFractionZeros(decimal.toPlainString());
    }

    /**
     * 去掉小数部分末尾多余的 0，<b>至少保留一位小数</b>；整数部分（含负号）原样保留。
     *
     * <p>例：{@code "0.00000010" → "0.0000001"}、{@code "1.0" → "1.0"}（保持不变）、
     * {@code "100.0" → "100.0"}、{@code "1000000000000000000" → 原样}。</p>
     *
     * <p>为什么保留一位而不是干脆去掉小数点：{@code 1.0} 若写成 {@code 1}，JSON 侧的类型就从
     * 浮点变成了整数，一些强类型消费方（或后续的 schema 校验）会因此改变解析结果。
     * 这里只清理"表示形式带来的 0"，不改动数值类型的外观。</p>
     */
    private static String trimFractionZeros(String plain) {
        int dot = plain.indexOf('.');
        if (dot < 0) {
            return plain;
        }
        int end = plain.length();
        while (end > dot + 2 && plain.charAt(end - 1) == '0') {
            end--;
        }
        return plain.substring(0, end);
    }

    /**
     * 校验浮点数为有限值。
     *
     * <p>NaN 与无穷<b>没有合法的 JSON 数字字面量</b>（{@code NaN}/{@code Infinity} 只是
     * JavaScript 的扩展写法），写进桥字段也会变成面板无法解读的字符串。它们只可能来自
     * 调用方的计算错误（除零、未初始化、溢出），因此在两个出口都当场判错，
     * 而不是把一个"看起来有值"的坏数据放上去。</p>
     *
     * <p>判定顺序很关键：NaN/无穷必须在进入 {@link BigDecimal} <b>之前</b>拦下。
     * {@code BigDecimal.valueOf(Double.NaN)} 与 {@code valueOf(Double.POSITIVE_INFINITY)}
     * 都会抛 {@link NumberFormatException}（消息形如"Character N is neither a decimal digit…"），
     * 既不点名字段、异常类型也不是本类约定的 {@link IllegalArgumentException}，
     * 调用方拿到的信息会少一大半。</p>
     *
     * @param value 待校验的浮点数
     * @param path  字段路径，仅用于错误消息
     * @throws IllegalArgumentException 值为 NaN 或正负无穷
     */
    private static void requireFinite(Number value, String path) {
        double raw = value.doubleValue();
        if (!Double.isNaN(raw) && !Double.isInfinite(raw)) {
            return;
        }
        throw new IllegalArgumentException(
                "载荷字段" + quote(path) + "的取值 " + value + " 无法写成数字："
                        + "NaN 与无穷大既不是合法的 JSON 数字，写进桥字段也会污染面板取值；"
                        + "请改用 String 或业务约定的缺省值");
    }

    /** 写出一个 JSON 对象（顶层或嵌套）。 */
    private static void writeObject(StringBuilder json, Map<?, ?> map, String path, int depth) {
        if (depth >= MAX_DEPTH) {
            throw depthExceeded(path);
        }
        json.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object rawKey = entry.getKey();
            if (rawKey == null) {
                throw new IllegalArgumentException(
                        "载荷" + quote(path) + "内存在 null 键：JSON 对象的键必须是字符串，"
                                + "请检查调用方构造 Map 的代码");
            }
            String key = String.valueOf(rawKey);
            if (!first) {
                json.append(',');
            }
            first = false;
            writeString(json, key);
            json.append(':');
            writeValue(json, entry.getValue(), childPath(path, key), depth + 1);
        }
        json.append('}');
    }

    /** 写出一个 JSON 值（标量、对象或数组）。 */
    private static void writeValue(StringBuilder json, Object value, String path, int depth) {
        if (value == null) {
            json.append("null");
            return;
        }
        if (value instanceof String) {
            writeString(json, (String) value);
            return;
        }
        if (value instanceof Character) {
            writeString(json, value.toString());
            return;
        }
        if (value instanceof Boolean) {
            json.append(((Boolean) value).booleanValue() ? "true" : "false");
            return;
        }
        // 同 toFields：BigDecimal/BigInteger 先于 Number 判定，浮点再单列一支，两者都不出指数形式。
        if (value instanceof BigDecimal) {
            json.append(((BigDecimal) value).toPlainString());
            return;
        }
        if (value instanceof BigInteger) {
            json.append(value.toString());
            return;
        }
        if (value instanceof Number) {
            writeNumber(json, (Number) value, path);
            return;
        }
        if (value instanceof Enum) {
            writeString(json, ((Enum<?>) value).name());
            return;
        }
        if (value instanceof Map) {
            writeObject(json, (Map<?, ?>) value, path, depth);
            return;
        }
        if (value instanceof Collection) {
            writeCollection(json, ((Collection<?>) value).iterator(), path, depth);
            return;
        }
        if (value.getClass().isArray()) {
            writeArray(json, value, path, depth);
            return;
        }
        throw unsupported(path, value);
    }

    /**
     * 写出一个 JSON 数字。
     *
     * <p>{@code Double}/{@code Float} 先判有限性再写出完整十进制串：NaN 与无穷在 JSON 里
     * <b>没有合法字面量</b>（{@code NaN}/{@code Infinity} 只是 JavaScript 扩展）。
     * 写出它们会让整段载荷在 Provider 侧解析失败，因此这里直接判错——这属于调用方取值错误，
     * 不是运行期故障。有限值则与 {@link #toFields(Map)} 走同一个
     * {@link #plainFloatText(Number)}，避免两个出口的数字格式分叉。</p>
     */
    private static void writeNumber(StringBuilder json, Number number, String path) {
        if (number instanceof Double || number instanceof Float) {
            requireFinite(number, path);
            json.append(plainFloatText(number));
            return;
        }
        // 其余 Number 子类（Long/Integer/AtomicLong…）的 toString() 都是合法十进制数字。
        json.append(number);
    }

    /** 写出一个 JSON 数组（来源是 {@link Collection}）。 */
    private static void writeCollection(StringBuilder json, Iterator<?> values, String path, int depth) {
        if (depth >= MAX_DEPTH) {
            throw depthExceeded(path);
        }
        json.append('[');
        boolean first = true;
        int index = 0;
        while (values.hasNext()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            writeValue(json, values.next(), indexPath(path, index), depth + 1);
            index++;
        }
        json.append(']');
    }

    /** 写出一个 JSON 数组（来源是任意数组，含 {@code int[]} 这类基本类型数组）。 */
    private static void writeArray(StringBuilder json, Object array, String path, int depth) {
        if (depth >= MAX_DEPTH) {
            throw depthExceeded(path);
        }
        int length = Array.getLength(array);
        json.append('[');
        for (int index = 0; index < length; index++) {
            if (index > 0) {
                json.append(',');
            }
            writeValue(json, Array.get(array, index), indexPath(path, index), depth + 1);
        }
        json.append(']');
    }

    /**
     * 写出一个 JSON 字符串（含定界引号）。
     *
     * <p>快路径：常规字符只做一次比较就 {@code continue}，循环结束后整段 {@code append}；
     * 只有遇到需要转义的字符时才把前面未转义的片段成段写出。这样纯 ASCII 载荷
     * （最常见的命令参数）不会付出逐字符多分支的代价。</p>
     */
    private static void writeString(StringBuilder json, String text) {
        json.append('"');
        int length = text.length();
        int runStart = 0;
        for (int index = 0; index < length; index++) {
            char current = text.charAt(index);
            if (current >= 0x20 && current != '"' && current != '\\') {
                continue;
            }
            if (runStart < index) {
                json.append(text, runStart, index);
            }
            appendEscape(json, current);
            runStart = index + 1;
        }
        if (runStart == 0) {
            json.append(text);
        } else if (runStart < length) {
            json.append(text, runStart, length);
        }
        json.append('"');
    }

    /** 追加一个字符的转义形式；只对 {@code "}、{@code \} 与控制字符调用。 */
    private static void appendEscape(StringBuilder json, char value) {
        switch (value) {
            case '"':
                json.append("\\\"");
                return;
            case '\\':
                json.append("\\\\");
                return;
            case '\n':
                json.append("\\n");
                return;
            case '\r':
                json.append("\\r");
                return;
            case '\t':
                json.append("\\t");
                return;
            case '\b':
                json.append("\\b");
                return;
            case '\f':
                json.append("\\f");
                return;
            default:
                // 其余 0x00~0x1F 控制字符按 \\u00XX 写出（如 0x1F -> \u001f）。
                json.append("\\u00");
                json.append(HEX_DIGITS[(value >> 4) & 0xF]);
                json.append(HEX_DIGITS[value & 0xF]);
        }
    }

    /** 子字段的路径描述；只在下钻容器时构造，标量快路径不产生任何字符串分配。 */
    private static String childPath(String parent, String key) {
        return parent == null ? key : parent + '.' + key;
    }

    /** 数组元素的路径描述，如 {@code items[2]}。 */
    private static String indexPath(String parent, int index) {
        return (parent == null ? "" : parent) + '[' + index + ']';
    }

    /** 路径的可读包装：空路径表示顶层。 */
    private static String quote(String path) {
        return path == null ? "（顶层）" : '"' + path + '"';
    }

    /** 不支持的类型：必须点名是哪个键、什么类型，并给出可执行的替代做法。 */
    private static IllegalArgumentException unsupported(String path, Object value) {
        return new IllegalArgumentException(
                "载荷字段" + quote(path) + "的类型 " + value.getClass().getName() + " 不受支持："
                        + "只接受标量（String / Number / Boolean / Enum / Character）"
                        + "以及嵌套的 Map / Collection / 数组；"
                        + "嵌套结构请自行序列化为字符串后再传入，金额请用 String 或 BigDecimal 以免丢精度");
    }

    /** 嵌套过深：换成可捕获的调用方错误，避免递归把栈打穿。 */
    private static IllegalArgumentException depthExceeded(String path) {
        return new IllegalArgumentException(
                "载荷字段" + quote(path) + "嵌套超过 " + MAX_DEPTH + " 层："
                        + "请检查是否存在自引用结构，或先在调用方把深层结构序列化成字符串");
    }

    /** {@link LinkedHashMap} 的容量换算：避免扩容，同时保持很小的载荷不做过度分配。 */
    private static int capacityFor(int size) {
        return size < 3 ? 4 : (int) (size / 0.75f) + 1;
    }
}
