package top.wcpe.mc.plugin.serverprobe.buildlogic.ci

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Java 8 字节码门禁的判定核心。
 *
 * 校验核心模块编译产物与发行 jar 内所有 `.class` 的 major version <= 52（Java 8）。
 * README 对外承诺「Java 8+」，一旦有人误调 toolchain 或引入高版本依赖，产物会在 Java 8 服务器上
 * 以 `UnsupportedClassVersionError` 启动失败，故设为独立门禁。
 *
 * 本对象是原 `.github/scripts/check-bytecode.py` 的等价实现：受检模块、豁免项、上限与
 * 「产物缺失也算失败」的口径都未变动，只是把运行时换成了构建期自带的 JDK。
 */
object BytecodeGate {

    /** Java 8 对应的 class 文件 major version 上限。 */
    const val MAX_MAJOR = 52

    /**
     * 受检模块（相对仓库根）。
     *
     * `build-logic` 是 Gradle 插件工程（included build），编译目标固定 Java 21（major 65）
     * 且只运行于构建期、不随发行包分发，必须排除，否则门禁必然失败。
     */
    val MODULES = listOf(
        "project/core",
        "api",
        "plugin",
        "platform/platform-bukkit",
        "platform/platform-bungee",
        "platform/platform-velocity",
        "diagnostics/diagnostics-arthas",
    )

    /** 发行 jar（shadow 合并后的二合一 jar）文件名通配写法（报告用）。 */
    private const val DIST_JAR_GLOB = "plugin/build/libs/ServerProbe-*.jar"

    /**
     * 发行 jar 内的豁免项。
     *
     * 这些条目来自随 jar 内置的三方依赖（sqlite-jdbc 等），Java 8 JVM 不会加载它们，
     * 因此不构成「探针要求 Java 9+」：
     * - `META-INF/versions/` 目录：multi-release jar 的高版本变体目录；
     * - `module-info.class`：模块描述符，不是运行时加载的类。
     */
    private val JAR_EXEMPT_PREFIXES = listOf("META-INF/versions/")
    private val JAR_EXEMPT_SUFFIXES = listOf("module-info.class")

    /** class 文件头里 major version 的字节偏移（第 7~8 字节，big-endian）。 */
    private const val MAJOR_HEADER_BYTES = 8

    /** 单个受检 class：展示路径（相对仓库根，jar 内以 `!` 分隔）与 major version。 */
    data class Entry(val displayPath: String, val major: Int)

    /** 一次门禁判定的完整结果。 */
    data class Outcome(
        val entries: List<Entry>,
        val jars: List<String>,
        val violations: List<Entry>,
        val missing: List<String>,
    ) {
        /** 各 major version 的分布（升序打印）。 */
        fun distribution(): Map<Int, Int> = entries.groupingBy { it.major }.eachCount()

        /** 无违规且无缺失才算通过。 */
        fun passed(): Boolean = violations.isEmpty() && missing.isEmpty()
    }

    /**
     * 在给定产物上执行门禁判定。
     *
     * 输入由调用方（任务）按声明好的输入接线传入，本对象不自己搜目录：哪些模块、哪个 jar 属于
     * 「受检产物」是**任务输入声明**的事，判定核心只负责读 class 头并给出结论。
     *
     * @param classRoots 各受检模块 main 源集的 class 输出目录（Kotlin 与 Java 可能各一个，可以不存在）。
     * @param jars 发行 jar 列表（允许为空：此时计入「缺失」并判失败）。
     * @param requiredModules 必须产出 class 的模块（相对仓库根，如 `project/core`）。
     * @param root 仓库根目录（只用于把展示路径转成相对路径）。
     * @return 判定结果（违规与缺失都不抛异常，交由调用方决定如何失败）。
     */
    fun evaluate(
        classRoots: Collection<File>,
        jars: Collection<File>,
        requiredModules: Collection<String>,
        root: File,
    ): Outcome {
        // 目标缺失必须报错：否则模块改名/构建失败会让门禁静默变绿、失去意义。
        // 按**模块**而非按目录判定：同一模块的 Kotlin/Java 输出目录天然可能有一个不存在（无相应源码）。
        val entries = classRoots.sortedBy { it.invariantSeparatorsPath }.flatMap { dir -> classEntries(dir, root) }
        val missing = requiredModules
            .filterNot { module -> entries.any { it.displayPath.startsWith("$module/build/classes/") } }
            .map { module -> "$module/build/classes" }
            .toMutableList()

        val existingJars = jars.filter { it.isFile }.sortedBy { it.name }
        if (existingJars.isEmpty()) {
            missing += DIST_JAR_GLOB
        }
        val jarEntries = existingJars.flatMap { jar -> jarClasses(jar, root) }

        val all = entries + jarEntries
        return Outcome(
            entries = all,
            jars = existingJars.map { it.relativePath(root) },
            violations = all.filter { it.major > MAX_MAJOR },
            missing = missing,
        )
    }

    /** 收集单个 class 输出目录下的全部 class（目录不存在或为空则返回空表，由调用方计为缺失）。 */
    private fun classEntries(dir: File, root: File): List<Entry> {
        if (!dir.isDirectory) {
            return emptyList()
        }
        val label = dir.relativePath(root)
        return dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .map { file ->
                Entry(
                    displayPath = "$label/${file.relativeTo(dir).invariantSeparatorsPath}",
                    major = file.inputStream().use(::majorOf),
                )
            }
            .sortedBy { it.displayPath }
            .toList()
    }

    /** 收集发行 jar 内的 class（豁免项除外）。 */
    private fun jarClasses(jar: File, root: File): List<Entry> = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .filter { it.name.endsWith(".class") }
            .filterNot { entry ->
                JAR_EXEMPT_PREFIXES.any { entry.name.startsWith(it) } ||
                    JAR_EXEMPT_SUFFIXES.any { entry.name.endsWith(it) }
            }
            .map { entry ->
                Entry(
                    displayPath = "${jar.relativePath(root)}!${entry.name}",
                    major = zip.getInputStream(entry).use(::majorOf),
                )
            }
            .sortedBy { it.displayPath }
            .toList()
    }

    /**
     * 取 class 文件头第 7~8 字节（big-endian）的 major version。
     *
     * @param input class 文件字节流（只读前 [MAJOR_HEADER_BYTES] 字节）。
     * @return major version，例如 Java 8 为 52。
     */
    fun majorOf(input: InputStream): Int = majorOf(input.readNBytes(MAJOR_HEADER_BYTES))

    /**
     * 从 class 文件头解析 major version。
     *
     * @param header 至少含前 8 字节的 class 文件头。
     * @return major version。
     */
    fun majorOf(header: ByteArray): Int {
        require(header.size >= MAJOR_HEADER_BYTES) { "class 文件头不足 $MAJOR_HEADER_BYTES 字节，实际 ${header.size}" }
        return ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
    }

    /** 相对仓库根的 posix 路径（报告用，跨平台稳定）。 */
    private fun File.relativePath(root: File): String =
        relativeTo(root).invariantSeparatorsPath.ifEmpty { name }
}
