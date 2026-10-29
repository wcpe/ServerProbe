package top.wcpe.mc.plugin.serverprobe.core.bridge.reload

import java.io.File
import java.io.IOException

/**
 * 重载路径的规范化与越界校验（配置重载规格 §3.3）。
 *
 * ## 为什么必须在这一层挡住
 * 一次重载会让**第三方插件**去读它声明的文件。若放行 `../../etc/passwd`、`/etc/passwd`
 * 或 `plugins/../secret`，JM 的一条普通命令就变成了任意文件读取/触发点；而符号链接还能
 * 让"字面合法"的路径在 canonical 化后逃出 `plugins/` 子树。故规则按**四道**执行，
 * 顺序不可交换：
 *
 * 1. 拒绝绝对路径（`/x`、`\x`、Windows 盘符 `C:`）与空串、含 `NUL` 的输入；
 * 2. 统一分隔符为 `/`，逐段折叠 `.` 与 `..`（`..` 无段可弹即越界）；
 * 3. 折叠后必须以 `plugins/` 开头；
 * 4. canonical 化（跟随符号链接）后仍须落在 `<工作目录>/plugins` 内。
 *
 * ## 为什么保留末尾斜杠
 * 目录前缀（`plugins/Foo/`）的末尾斜杠是**语义**：它区分「目录下任意层级」与「这一份文件」。
 * 规范化若把斜杠吃掉，声明就退化成精确匹配，监听器会静默收不到本该收到的路径。
 *
 * ## 与规格的差异
 * 规格写的是 `object ReloadPathGuard { fun normalize(raw: String): String? }`。这里保留该签名，
 * 但额外提供带 [workingDir] 参数的重载：单测必须能在临时目录（含符号链接）里验证第 4 条规则，
 * 而进程工作目录不可改。
 */
object ReloadPathGuard {

    /** `plugins/` 子树根的名字。 */
    const val PLUGINS_ROOT = "plugins"

    /** Windows 盘符前缀（`C:` / `c:`）。 */
    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")

    /** 路径里出现的 `NUL` 字符（用码点构造，避免源码里出现不可见字符）。 */
    private val NUL: Char = 0.toChar()

    /**
     * 以**进程工作目录**为基准规范化并校验路径。
     *
     * @param raw 原始路径（可含 `\`、`.`、`..`、末尾 `/`）。
     * @return 规范化路径；不合法时返回 null（调用方据此记 `PATH_ESCAPE`）。
     */
    fun normalize(raw: String): String? = normalize(raw, File("."))

    /**
     * 以 [workingDir] 为基准规范化并校验路径。
     *
     * @param raw 原始路径。
     * @param workingDir 服务器工作目录（基准）。
     * @return 规范化路径；不合法时返回 null。
     */
    fun normalize(raw: String, workingDir: File): String? {
        val lexical = lexically(raw) ?: return null
        return if (staysInside(lexical, workingDir)) lexical else null
    }

    /**
     * 词法层校验（规则 1~3，不触碰文件系统）。
     *
     * @return 规范化路径；不合法时返回 null。
     */
    private fun lexically(raw: String): String? {
        if (hasIllegalForm(raw)) return null
        val unified = raw.replace('\\', '/')
        val segments = collapse(unified) ?: return null
        val joined = join(segments, unified.endsWith("/"))
        return if (isInsidePlugins(joined)) joined else null
    }

    /** 规则 1：空串 / 含 `NUL` / 绝对路径（前导斜杠或 Windows 盘符）一律不合法。 */
    private fun hasIllegalForm(raw: String): Boolean =
        raw.isEmpty() ||
            raw.indexOf(NUL) >= 0 ||
            raw.startsWith("/") ||
            raw.startsWith("\\") ||
            DRIVE_PREFIX.containsMatchIn(raw)

    /**
     * 规则 2：逐段折叠 `.` 与 `..`。
     *
     * `..` 在栈空时出现即逃逸（`../../etc/passwd`），返回 null。
     *
     * @return 折叠后的段列表；逃逸时返回 null。
     */
    private fun collapse(unified: String): List<String>? {
        val segments = ArrayList<String>()
        for (segment in unified.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.size - 1)
                else -> segments.add(segment)
            }
        }
        return segments.ifEmpty { null }
    }

    /** 重新拼接；末尾斜杠是目录前缀的语义，必须保留。 */
    private fun join(segments: List<String>, keepTrailingSlash: Boolean): String =
        segments.joinToString("/") + if (keepTrailingSlash) "/" else ""

    /** 规则 3：必须在 `plugins/` 子树内（`plugins` 与 `plugins/` 本身都属合法范围）。 */
    private fun isInsidePlugins(joined: String): Boolean =
        joined == PLUGINS_ROOT || joined.startsWith("$PLUGINS_ROOT/")

    /**
     * canonical 层校验（规则 4，跟随符号链接）。
     *
     * 目标文件可能还不存在（JM 允许对"即将写入的配置"下发重载），此时 canonical 化只做词法归一，
     * 不会抛异常——所以这里只需比较前缀。IO 失败按越界处理（宁可拒绝，也不放行一个说不清的路径）。
     */
    private fun staysInside(normalized: String, workingDir: File): Boolean {
        val root = canonicalOf(File(workingDir, PLUGINS_ROOT)) ?: return false
        val target = canonicalOf(File(workingDir, normalized)) ?: return false
        return target.path == root.path || target.path.startsWith(root.path + File.separator)
    }

    /** canonical 化；失败返回 null（调用方按越界处理）。 */
    private fun canonicalOf(file: File): File? = try {
        file.canonicalFile
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
