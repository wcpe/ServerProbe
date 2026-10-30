package top.wcpe.mc.plugin.serverprobe.api.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个业务域的动作声明（不可变）。
 *
 * <h2>为什么不直接放进 manifest</h2>
 * manifest 的值必须是 {@code Map} / {@code List} / {@code String} / 数字 / 布尔，
 * 见 {@link #toMap()} 的说明。把本类实例直接塞进 manifest 列表会导致编码结果不可预期，
 * <b>必须</b>先经 {@link #toMap()} 转换。
 *
 * <h2>它解决什么</h2>
 * 面板要渲染操作表单，就必须知道「每个动作叫什么、需要哪些字段、是不是只读」。
 * 仅给一串动作名（{@code List<String>}）不够——面板只能盲发命令。
 * 本类就是把这三件事变成通用结构的最小载体。
 *
 * <p>本类<b>不</b>描述任何具体业务语义：动作名与字段名全部由调用方给出。</p>
 */
public final class ActionSpec {

    private final String name;

    private final List<String> fields;

    private final boolean readOnly;

    private ActionSpec(String name, List<String> fields, boolean readOnly) {
        this.name = name;
        // 防御性拷贝：调用方之后改自己的 list 不应影响已声明的动作。
        this.fields = Collections.unmodifiableList(new ArrayList<String>(fields));
        this.readOnly = readOnly;
    }

    /**
     * 构造一个动作声明。
     *
     * @param name     动作名，由插件自定；空串或 null 会被规整为空串（探针不校验语义）
     * @param fields   该动作要求的参数名，由插件自定；null 视为空列表
     * @param readOnly true=查询类动作，false=写入类动作
     * @return 不可变的动作声明
     */
    public static ActionSpec of(String name, List<String> fields, boolean readOnly) {
        List<String> copy = fields == null ? Collections.<String>emptyList() : fields;
        return new ActionSpec(name == null ? "" : name, copy, readOnly);
    }

    public String getName() {
        return this.name;
    }

    public List<String> getFields() {
        return this.fields;
    }

    public boolean getReadOnly() {
        return this.readOnly;
    }

    /**
     * 转为可直接放进 manifest 的普通 {@code Map}。
     *
     * <p>键为 {@code name} / {@code fields} / {@code readOnly}（对应
     * {@link BusinessProtocol#ACTION_NAME} 等常量），值为 {@code String} /
     * {@code List<String>} / {@code Boolean}——全部是 JSON 编码器能递归处理的类型。</p>
     *
     * <p>例如插件可这样声明两个动作：</p>
     * <pre>{@code
     * Map<String, Object> manifest = new LinkedHashMap<String, Object>();
     * manifest.put(BusinessProtocol.MANIFEST_ACTIONS, Arrays.asList(
     *     Actions.read("query", "player").toMap(),
     *     Actions.write("grant", "player", "amount").toMap()
     * ));
     * }</pre>
     *
     * @return 新的可变 Map（每次调用都返回独立实例，调用方可放心修改）
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(BusinessProtocol.ACTION_NAME, this.name);
        map.put(BusinessProtocol.ACTION_FIELDS, this.fields);
        map.put(BusinessProtocol.ACTION_READ_ONLY, Boolean.valueOf(this.readOnly));
        return map;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ActionSpec)) {
            return false;
        }
        ActionSpec that = (ActionSpec) other;
        return this.readOnly == that.readOnly
            && this.name.equals(that.name)
            && this.fields.equals(that.fields);
    }

    @Override
    public int hashCode() {
        int result = this.name.hashCode();
        result = 31 * result + this.fields.hashCode();
        result = 31 * result + (this.readOnly ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "ActionSpec{name='" + this.name + "', fields=" + this.fields
            + ", readOnly=" + this.readOnly + '}';
    }
}
