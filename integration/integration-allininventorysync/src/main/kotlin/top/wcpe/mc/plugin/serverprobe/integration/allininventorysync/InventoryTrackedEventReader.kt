package top.wcpe.mc.plugin.serverprobe.integration.allininventorysync

/** 可选 AIS 追踪事件的公开字段快照，避免主模块在方法签名中硬依赖 AIS API。 */
internal data class InventoryTrackedEvent(
    val playerName: String,
    val playerUuid: String,
    val action: String,
    val ruleId: String,
    val ruleDescription: String,
    val material: String,
    val amount: Int,
    val displayName: String,
)

/** 仅按 AIS 公共事件 getter 读取数据；缺字段直接失败并由监听器隔离。 */
internal object InventoryTrackedEventReader {

    fun read(source: Any): InventoryTrackedEvent {
        val player = getter(source, "getPlayer")
        val item = getter(source, "getItem")
        val rule = getter(source, "getRule")
        return InventoryTrackedEvent(
            playerName = getter(player, "getName") as String,
            playerUuid = getter(getter(player, "getUniqueId"), "toString") as String,
            action = enumName(getter(source, "getAction")),
            ruleId = getter(rule, "getId") as String,
            ruleDescription = getter(rule, "getDescription") as String,
            material = enumName(getter(item, "getType")),
            amount = getter(item, "getAmount") as Int,
            displayName = displayName(item),
        )
    }

    private fun displayName(item: Any): String {
        val meta = getter(item, "getItemMeta")
        return if (getter(meta, "hasDisplayName") as? Boolean == true) {
            getter(meta, "getDisplayName") as String
        } else {
            ""
        }
    }

    private fun enumName(value: Any): String = (value as? Enum<*>)?.name ?: getter(value, "name") as String

    private fun getter(target: Any, name: String): Any {
        val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            ?: error("AIS 追踪事件缺少公开方法:$name")
        if (!method.isAccessible) {
            method.isAccessible = true
        }
        return method.invoke(target) ?: error("AIS 追踪事件公开方法返回空值:$name")
    }
}
