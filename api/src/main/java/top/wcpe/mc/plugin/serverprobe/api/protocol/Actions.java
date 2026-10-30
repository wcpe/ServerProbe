package top.wcpe.mc.plugin.serverprobe.api.protocol;

import java.util.Arrays;

/**
 * 动作声明的构造辅助（纯便利，不引入任何业务语义）。
 *
 * <p>本类不包含、也不引用任何具体业务域的字段名：{@code fields} 完全由调用方给出。
 * 它存在的意义只是把 {@code new ArrayList(...)} 之类的样板从插件里挪走。</p>
 *
 * <h2>为什么不自动追加 taskId</h2>
 * 幂等键是否必需、叫什么名字，由插件自行决定并在 {@code fields} 里显式列出。
 * 探针不替它做这个决定——那属于各域自己的治理策略，
 * 不是「通用动作容器」应该固化的东西。自动追加会造成两个实际后果：
 * 面板按 manifest 渲染出的表单会凭空多出一个插件并不接受的字段；
 * 而插件若另起名字，同一个概念就出现两种拼写。
 */
public final class Actions {

    /** 只读动作（查询类）。 */
    public static ActionSpec read(String name, String... fields) {
        return ActionSpec.of(name, Arrays.asList(fields), true);
    }

    /**
     * 写动作（会改变业务状态）。
     *
     * <p>需要幂等键的动作，请把它的字段名<b>显式</b>写进 {@code fields}，
     * 例如 {@code Actions.write("grant", "player", "amount", "taskId")}。</p>
     */
    public static ActionSpec write(String name, String... fields) {
        return ActionSpec.of(name, Arrays.asList(fields), false);
    }

    /** 纯静态工具，禁止实例化。 */
    private Actions() {
        throw new AssertionError("Actions 是静态工具类，不可实例化");
    }
}
