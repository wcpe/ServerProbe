package top.wcpe.mc.plugin.serverprobe.bukkit.mcp

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 多版本矩阵的兼容约束：**本模块的类不得在类型描述符里引用 Folia 内部类型**。
 *
 * <h2>为什么这条不变量必须守</h2>
 * <p>旧版服务器（1.8–1.20 Spigot）在加载我们的平台类时，会**急切解析**成员的类型描述符。
 * 只要某个字段/方法签名上出现 `io.papermc.paper.threadedregions.*`，
 * 在那里就是 `NoClassDefFoundError`，而且往往在插件启用阶段抛——整个探针起不来。
 * 所以 Folia 维度只能走反射（见 `FoliaInternalRegionIdentityAdapter`），
 * 绝不能上主类的签名。</p>
 *
 * <h2>为什么不靠反射</h2>
 * <p>以前这里是 `Class.forName(name)` 取 `declaredFields`/`declaredMethods` 再翻类型名。
 * 它有两个毛病：① 只能检查「在当前 JVM 上能加载的类」，恰恰是旧版服务器上的问题没人管；
 * ② 检查名单是手写的，新加的类自动漏检。</p>
 *
 * <p>现在直接读**本模块编译产物的字节码**：具体做法是扫每个 class 文件里的
 * `papermc/paper/threadedregions` 斜杠形式。这个判别是精确的——代码里反射用的类名
 * 字符串写的是点号（`io.papermc.paper.threadedregions.TickRegionScheduler`），
 * 只有**类型描述符**才会以斜杠形式出现在常量池里。因此：
 * 反射用的字符串常量不算违规，真上了签名就会被抓。</p>
 */
class E2eMatrixSmokeContractTest {

    @Test
    @DisplayName("本模块所有类的类型描述符里不出现 Folia 内部类型")
    fun `模块类不暴露Folia类型`() {
        val classesRoot = moduleClassesRoot()
        val classFiles = classesRoot.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()

        assertTrue(
            classFiles.size > MIN_EXPECTED_CLASSES,
            "必须真的扫到了本模块编译产物，否则这条断言会变成“什么都没检查就绿”。实际扫到 ${classFiles.size} 个，根=$classesRoot",
        )

        val offenders = classFiles.flatMap { file ->
            val bytes = file.readBytes()
            DESCRIPTOR_MARKERS.mapNotNull { marker ->
                val index = indexOf(bytes, marker.toByteArray(Charsets.US_ASCII))
                if (index < 0) null else "${file.relativeTo(classesRoot).path}（偏移 $index）"
            }
        }

        assertTrue(
            offenders.isEmpty(),
            "平台类签名里不得出现 Folia 内部类型，否则旧版服务器解析类时会 NoClassDefFoundError：" +
                "旧版只认反射（见 FoliaInternalRegionIdentityAdapter），实际=$offenders",
        )
    }

    /** 本模块 main 源集编译产物目录（从已加载类的 codesource 取，随构建目录变化）。 */
    private fun moduleClassesRoot(): File {
        val location = BukkitWorldDetailProvider::class.java.protectionDomain.codeSource.location
        return File(location.toURI())
    }

    /** 在字节数组里找一个 ASCII 子串，返回首字节偏移（找不到返回 -1）。 */
    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }

    private companion object {
        /** 本模块类数量远多于这个值；防的是“扫到空目录也绿”。 */
        private const val MIN_EXPECTED_CLASSES = 10

        /**
         * 只认**斜杠形式**：类名字符串常量写的是点号，不会命中；
         * 类型描述符里的包名是斜杠分隔，一上签名就命中。
         */
        private val DESCRIPTOR_MARKERS = listOf(
            "papermc/paper/threadedregions",
        )
    }
}
