package top.wcpe.mc.plugin.serverprobe.api.protocol;

/**
 * 业务桥协议的<b>通用</b>字段名约定。
 *
 * <h2>本类只做一件事</h2>
 * 收录与具体业务无关、任何业务域都可能用到的字段名。这些是业务面板（JM CP）与业务插件之间的
 * 公共词汇：探针不解释、不校验业务语义，只负责承载与转发。
 *
 * <h2>取值必须与探针真实产出的帧一致</h2>
 * 本类不做「希望有的字段」，只做「事实上有的字段」。当前唯一依据是 core 的两处编码器：
 * <ul>
 *   <li>命令回执帧 —— 结构化字段全在 {@code data} 内：
 *       {@code requestId} / {@code success} / {@code output} / {@code error}；</li>
 *   <li>业务事件帧 —— {@code domain} 与 {@code dedupKey} 在<b>顶层</b>，业务载荷在 {@code data} 内。</li>
 * </ul>
 * 改动这些常量前，必须同步核对上述两个编码器，否则面板与插件会各按一套名字收发。
 *
 * <h2>刻意不包含的内容</h2>
 * <b>本类不包含任何具体业务域的字段名</b>——不出现金额、货币、物品、属性，也不出现任何
 * 序列化载荷的内部结构。那些属于各插件自己的 {@code manifest()} 与业务载荷，
 * 由插件自行声明；探针既不知道、也不关心，更不在本仓维护任何具体域的协议说明表。
 *
 * <p>换句话说：判断一个常量该不该加进本类，标准是「换一个完全不同的业务域，它依然成立吗」。</p>
 *
 * <p>本类是纯常量容器，不可实例化。</p>
 */
public final class BusinessProtocol {

    /**
     * 命令回执帧 {@code data} 内的关联标识（字符串）。
     *
     * <p>插件不需要自己生成或记忆它：探针把入站命令的 {@code requestId} 原样回填，
     * 面板据此把回执对上先前发出的命令。</p>
     */
    public static final String FIELD_REQUEST_ID = "requestId";

    /** 命令回执帧 {@code data} 内的成功标志（布尔）。与 core 回执帧一致。 */
    public static final String FIELD_SUCCESS = "success";

    /**
     * 命令回执帧 {@code data} 内的成功输出（字符串）。
     *
     * <p>成功时面板应展示它；失败时它可能为空串，此时看 {@link #FIELD_ERROR}。</p>
     */
    public static final String FIELD_OUTPUT = "output";

    /** 命令回执帧 {@code data} 内的失败原因（字符串）。成功时为空串。 */
    public static final String FIELD_ERROR = "error";

    /**
     * 业务事件帧<b>顶层</b>的去重键（字符串）。
     *
     * <p>与 {@link #FIELD_DOMAIN} 组成幂等键：面板按 {@code (domain, dedupKey)} 落库去重，
     * 因此插件应让同一业务事实在重发时复用同一个 dedupKey。</p>
     */
    public static final String FIELD_DEDUP_KEY = "dedupKey";

    /** 业务事件帧<b>顶层</b>的业务域（字符串）。与 core 事件帧一致。 */
    public static final String FIELD_DOMAIN = "domain";

    /**
     * 动作声明中的动作名（字符串）。
     *
     * <p>与 {@link ActionSpec} / {@link Actions} 配合使用：这就是 {@code ActionSpec#toMap()}
     * 产出 Map 的键名之一。</p>
     */
    public static final String ACTION_NAME = "name";

    /** 动作声明中的参数名列表（{@code List<String>}），即 {@code ActionSpec#toMap()} 的键名之一。 */
    public static final String ACTION_FIELDS = "fields";

    /** 动作声明中的只读标志（布尔），即 {@code ActionSpec#toMap()} 的键名之一。 */
    public static final String ACTION_READ_ONLY = "readOnly";

    /** manifest 中承载动作清单的键名（{@code List<Map>}，元素由 {@link ActionSpec#toMap()} 产出）。 */
    public static final String MANIFEST_ACTIONS = "actions";

    /** 纯常量容器，禁止实例化。 */
    private BusinessProtocol() {
        throw new AssertionError("BusinessProtocol 是常量容器，不可实例化");
    }
}
