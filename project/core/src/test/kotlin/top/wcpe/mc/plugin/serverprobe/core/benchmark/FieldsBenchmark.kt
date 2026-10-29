package top.wcpe.mc.plugin.serverprobe.core.benchmark

import top.wcpe.mc.plugin.serverprobe.api.protocol.Fields

/**
 * `Fields` 装配复杂度的对照基准。
 *
 * ## 要证明的事
 * `Fields.with` 早期实现是"每次复制一份 LinkedHashMap"，单次 O(n)，链式装配 n 个字段
 * 就是 **O(n²)**；改成共享父链后 `with` 只挂一个节点 = **O(1)**，展平推迟到 `build()`。
 *
 * 本基准在**同一台机器、同一轮运行**里对比两条路径，并观察耗时随字段数 n 的增长形状：
 * 真 O(n²) 在 n 翻倍时约 4 倍增长，O(n) 约 2 倍。
 */
object FieldsBenchmark {

    /** 参照实现：精确复刻"每次 with 复制整表"的旧写法，用于同机对照。 */
    private class CopyingFields private constructor(private val values: Map<String, Any?>) {

        fun with(key: String, value: Any?): CopyingFields {
            val grown = LinkedHashMap<String, Any?>(values.size + 1)
            grown.putAll(values)
            grown.put(key, value)
            return CopyingFields(grown)
        }

        companion object {
            fun create(): CopyingFields = CopyingFields(emptyMap())
        }
    }

    fun run() {
        println("=== Fields 装配复杂度（ns/op = 装配 n 个字段的平均每次耗时） ===")
        println("  n     旧法(复制Map)    新法(共享父链)   新法+build   加速比")

        // 子项自己排版输出，故静默 Bench 的逐行打印。
        Bench.silently {
            intArrayOf(4, 8, 16, 32, 64).forEach { measureRow(it) }
        }

        println(
            "  说明：加速比 = 旧法 / 新法（仅装配）。n 翻倍时旧法约 4 倍增长、新法约 2 倍，" +
                "即 O(n²) vs O(n)。",
        )
    }

    /** 测一档 n，输出一行汇总。 */
    private fun measureRow(n: Int) {
        val oldWay = Bench.measure("旧法 n=$n", iterations = 20_000) {
            var fields = CopyingFields.create()
            for (index in 0 until n) fields = fields.with("k$index", index)
            fields
        }
        val newWay = Bench.measure("新法 n=$n", iterations = 20_000) {
            var fields = Fields.create()
            for (index in 0 until n) fields = fields.with("k$index", index)
            fields
        }
        val withBuild = Bench.measure("新法+build n=$n", iterations = 20_000) {
            var fields = Fields.create()
            for (index in 0 until n) fields = fields.with("k$index", index)
            fields.build()
        }
        val speedup = if (newWay.nsPerOp > 0) oldWay.nsPerOp / newWay.nsPerOp else 0.0
        println("  %-5d %-17.1f %-17.1f %-13.1f %.1fx".format(n, oldWay.nsPerOp, newWay.nsPerOp, withBuild.nsPerOp, speedup))
    }
}
