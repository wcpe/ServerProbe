package top.wcpe.mc.plugin.serverprobe.core.config

/**
 * 托管配置**键路径**的合并与显式删除（FR-32，协议规格 §3.4.1 的 `values` / `removals`）。
 *
 * ## 为什么单独抽出来
 * "删除一个键"在托管配置里有两种形态并存（[ManagedConfigStore.valueOf] 两种都支持）：
 * 扁平键（`aggregation.window`）与嵌套对象（`alert.rules` 下的子键）。删除必须**两种都对**，
 * 否则控制面写了 `removals: ["alert.rules.tps-warn"]` 而探针只在扁平键里找不到就直接忽略，
 * 表现为"删了但没删掉"——这类静默失效比报错更难查。故两处（应用链路与两代快照回退）共用本类一份实现。
 *
 * ## 纯函数
 * 所有方法都返回**新表**、不改入参：托管配置的两代快照是"整体替换"的不可变对象，
 * 原地修改会破坏 [ManagedConfigStore] 的原子性前提（读方可能正持有旧表）。
 */
internal object ManagedConfigValues {

    /**
     * 把一次下发合并进基线：先按 [removals] 删键，再用 [pushed] 覆盖。
     *
     * 键的合并粒度是**顶层键**：`pushed` 的每个键整体覆盖基线里的同名键（`alert.rules` 这类对象
     * 以整块替换，不做深合并）。这与"控制面下发的是完整键值"这一约定一致，也避免深合并时
     * 出现"想删子键结果被父对象整体覆盖回来"的歧义。
     *
     * @param base 当前生效值。
     * @param pushed 本次下发的值；`null` 值一律忽略（显式删除只走 [removals]）。
     * @param removals 需显式删除的点分键路径。
     * @return 新表（保持 [base] 的键顺序，新键追加在后）。
     */
    fun merge(
        base: Map<String, Any?>,
        pushed: Map<String, Any?>,
        removals: Collection<String>,
    ): Map<String, Any?> {
        val merged = LinkedHashMap(removeAll(base, removals))
        pushed.forEach { (key, value) ->
            if (value != null) {
                merged[key] = value
            }
        }
        return merged
    }

    /**
     * 按多条路径删除键。
     *
     * @param values 取值表。
     * @param paths 点分键路径集合。
     * @return 新表；[paths] 为空或一条都没命中时返回入参本身。
     */
    fun removeAll(values: Map<String, Any?>, paths: Collection<String>): Map<String, Any?> {
        var result = values
        paths.forEach { path -> result = remove(result, path) }
        return result
    }

    /**
     * 按一条路径删除键。
     *
     * 删除要同时覆盖三种可能的存放形态（控制面与 `config.yml` 都可能产出）：
     * 1. **扁平键**：表里直接有 `alert.rules.tps-warn` 这个键；
     * 2. **点分前缀对象**：表里有 `alert.rules` 这个键，其值是对象，待删的是它下面的 `tps-warn`；
     * 3. **逐层嵌套**：表里是 `alert` → `rules` → `tps-warn` 的普通嵌套。
     *
     * 故先试整条路径精确命中，再从**最长**的点分前缀开始找可下钻的对象（最长优先避免
     * `alert` 与 `alert.rules` 同时存在时下钻到太浅的一层、把兄弟键一起弄丢）。
     *
     * @param values 取值表。
     * @param path 点分键路径。
     * @return 新表；未命中时返回入参本身（调用方可据此判断"没删掉任何东西"）。
     */
    fun remove(values: Map<String, Any?>, path: String): Map<String, Any?> = removeOrNull(values, path) ?: values

    /**
     * 删除的实现：命中返回新表，未命中返回 null。
     *
     * 用"null 表示没删掉"而不是比较表大小——子对象被删空时外层大小不变（`{rules:{}}` 与
     * `{rules:{x:1}}` 都是 1 个键），按大小判断会把"删成功了"误判成"没删"，
     * 表现为深层键删不掉（`alert.rules.tps-warn` 正是这种形态）。
     */
    private fun removeOrNull(values: Map<String, Any?>, path: String): Map<String, Any?>? {
        if (values.containsKey(path)) {
            val copied = LinkedHashMap(values)
            copied.remove(path)
            return copied
        }
        var index = path.lastIndexOf('.')
        while (index > 0) {
            val prefix = path.substring(0, index)
            val child = values[prefix] as? Map<*, *>
            if (child != null) {
                val next = removeOrNull(toTypedMap(child), path.substring(index + 1))
                if (next != null) {
                    val copied = LinkedHashMap(values)
                    copied[prefix] = next
                    return copied
                }
            }
            // 该前缀未命中：继续尝试更短的前缀（`alert.rules` 与 `alert` 都可能存在）
            index = path.lastIndexOf('.', index - 1)
        }
        return null
    }

    /** `Map<*, *>` → `Map<String, Any?>`（键统一转字符串，值不变）。 */
    private fun toTypedMap(source: Map<*, *>): LinkedHashMap<String, Any?> {
        val typed = LinkedHashMap<String, Any?>(source.size)
        source.forEach { (key, value) -> typed[key.toString()] = value }
        return typed
    }
}
