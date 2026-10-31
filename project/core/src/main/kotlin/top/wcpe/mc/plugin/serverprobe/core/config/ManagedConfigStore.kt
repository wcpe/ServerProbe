package top.wcpe.mc.plugin.serverprobe.core.config

import top.wcpe.mc.plugin.serverprobe.core.control.ConfigTier

/**
 * 托管配置值的内存缓存(协议规格 §3.3 的"托管缓存")。
 *
 * 保存**两代**已生效的下发值:[Generations.current] 是当前生效版本,[Generations.previous] 是上一版。
 * 读取顺序(见 [ManagedConfigResolver])为 current → previous → `config.yml` → 代码默认值,
 * 其中 previous 的用途是"当前版本里缺某个键时仍能给出上一次下发的值"。
 *
 * ## 线程模型
 * 写入方是桥的后台线程(FR-32 的 `config_push` 应用链路),读取方是采集线程与主线程。
 * 故 [replace] 只做**整表原子替换**:两代值装在一个不可变对象里,一次 `@Volatile` 读就能拿到自洽的一对,
 * 读方不会读到"current 是新一代、previous 却是再上一代"这种错位组合。**不做逐键修改**,也就没有中间态。
 *
 * ## 不做校验
 * 本类只存值。档位与物理边界校验在 [ConfigTier.validate](写入前由 FR-32 执行),FORBIDDEN 键的回落
 * 在读取侧由 [ManagedConfigResolver] 统一执行——两处都不在本类,避免同一规则出现多份实现。
 */
object ManagedConfigStore {

    /** 两代生效值(不可变快照;整体一次性替换)。 */
    class Generations internal constructor(
        /** 当前生效的一代。 */
        val current: Map<String, Any?>,
        /** 上一代。 */
        val previous: Map<String, Any?>,
    )

    /** 当前快照;初始态两代都是空表。 */
    @Volatile
    private var generations = Generations(emptyMap(), emptyMap())

    /** 取一次自洽的两代快照(读方应只取一次,避免跨代拼接)。 */
    fun generations(): Generations = generations

    /** 当前生效的一代;无下发时为空表。 */
    fun currentValues(): Map<String, Any?> = generations.current

    /** 上一代;从未替换过时为空表。 */
    fun previousValues(): Map<String, Any?> = generations.previous

    /**
     * 原子替换成新一代生效值:旧 current 变为 previous。
     *
     * @param values 新的一代(键为配置键名,值为 JSON 解析后的原值);内部做防御性拷贝。
     * @return true 表示内容确有变化(供 FR-32 决定是否回报 `config_applied`);false 表示与当前一致。
     */
    fun replace(values: Map<String, Any?>): Boolean = replace(values, emptyList())

    /**
     * 原子替换成新一代生效值,并按 [removals] 显式删键(FR-32)。
     *
     * 与单参版本的区别只有一处:**删除也要作用在上一代上**。否则 `previous → current` 的回落顺序
     * (见 [ManagedConfigResolver]) 会把刚删掉的键从上一代"复活",表现为"下发了删除却仍然生效"。
     *
     * 值为 null 的条目同样视为删除(对应 YAML 的 null 语义),不会在表里留下 null 值。
     *
     * @param values 新一代生效值(已是"合并 + 删除"后的完整集合)。
     * @param removals 需从两代中一并抹掉的点分键路径。
     * @return true 表示当前生效值确有变化。
     */
    @Synchronized
    fun replace(values: Map<String, Any?>, removals: Collection<String>): Boolean {
        val snapshot = generations
        val next = ManagedConfigValues.removeAll(values.filterValues { it != null }, removals)
        if (next == snapshot.current) {
            return false
        }
        generations = Generations(next, ManagedConfigValues.removeAll(snapshot.current, removals))
        return true
    }

    /**
     * 回退到应用前的两代快照(FR-32 自检失败时使用)。
     *
     * 与"再 replace 一次上一代"不同:本方法把**两代都还原**成下发前的组合,
     * 不会留下"current 是回退值、previous 反而成了失败值"的错位。
     *
     * @param snapshot 下发前由 [generations] 取得的快照。
     */
    @Synchronized
    fun restore(snapshot: Generations) {
        generations = snapshot
    }

    /** 清空两代缓存(回到"从未下发"的初始状态)。 */
    @Synchronized
    fun clear() {
        generations = Generations(emptyMap(), emptyMap())
    }

    /**
     * 取某个键在一代取值表里的值;支持点分路径逐段下钻(如 `alert.rules` 下的子键)。
     *
     * 先按**扁平键**精确匹配(托管面用的就是 `config.yml` 的键名,如 `aggregation.window`),
     * 再逐段把点分前缀当作嵌套对象下钻——两种形态都可能出现,故都支持。
     *
     * @param values 一代取值表。
     * @param key 配置键名,允许是更深层的点分路径。
     * @return 命中的原值;未命中返回 null。
     */
    fun valueOf(values: Map<String, Any?>, key: String): Any? {
        if (values.containsKey(key)) {
            return values[key]
        }
        var index = key.indexOf('.')
        while (index > 0) {
            val nested = values[key.substring(0, index)]
            if (nested is Map<*, *>) {
                val hit = descend(nested, key.substring(index + 1))
                if (hit != null) {
                    return hit
                }
            }
            index = key.indexOf('.', index + 1)
        }
        return null
    }

    /** 在嵌套对象里按剩余点分路径下钻。 */
    private fun descend(node: Map<*, *>, path: String): Any? {
        // 扁平键优先:嵌套对象里也可能直接以完整剩余路径为键
        if (node.containsKey(path)) {
            return node[path]
        }
        val dot = path.indexOf('.')
        if (dot < 0) {
            return null
        }
        val child = node[path.substring(0, dot)] as? Map<*, *> ?: return null
        return descend(child, path.substring(dot + 1))
    }
}
