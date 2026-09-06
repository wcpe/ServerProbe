package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas

import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Arthas 原始运行包在发行 jar 内的固定布局。 */
object ArthasRuntimeLayout {

    const val legacyVersion = "3.1.1"
    const val modernVersion = "4.3.2"
    const val defaultVersion = legacyVersion
    const val checksumFileName = "sha256.properties"
    private const val descriptorPath = "META-INF/serverprobe/arthas/runtime.properties"

    val requiredJarNames = listOf(
        "arthas-core.jar",
        "arthas-boot.jar",
        "arthas-agent.jar",
        "arthas-spy.jar",
    )

    /** async-profiler 原生库（仅 4.x 发行包含）：Arthas 运行期按 `libasyncProfiler.so` 查找。 */
    val asyncProfilerLibraries = listOf(
        "libasyncProfiler-linux-x64.so",
        "libasyncProfiler-linux-arm64.so",
        "libasyncProfiler-mac.dylib",
    )

    /** 指定版本运行闭包应含的原生库（3.1.1 无 async-profiler 能力，仅 4.x）。 */
    fun requiredLibraries(version: String): List<String> =
        if (ArthasRuntimeSelector.usesModernBridge(version)) asyncProfilerLibraries else emptyList()

    fun resourcePath(version: String, name: String): String = "META-INF/serverprobe/arthas/$version/$name"

    fun resolveVersion(
        resourceReader: (String) -> InputStream?,
        javaSpecificationVersion: String = System.getProperty("java.specification.version"),
    ): String {
        val content = resourceReader(descriptorPath)?.use(InputStream::readBytes)?.let(::String)
            ?: return ArthasRuntimeSelector.select(javaSpecificationVersion, legacyVersion, modernVersion)
        val values = content.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val separator = line.indexOf('=')
                require(separator > 0) { "Arthas 版本描述格式非法" }
                line.substring(0, separator) to line.substring(separator + 1)
            }
        val legacy = values["legacy-version"] ?: values["version"] ?: error("Arthas 版本描述缺失")
        val modern = values["modern-version"] ?: legacy
        return ArthasRuntimeSelector.select(javaSpecificationVersion, legacy, modern)
    }
}

/** 按目标 JVM 的规范版本选择官方 Arthas 运行闭包。 */
object ArthasRuntimeSelector {

    fun select(javaSpecificationVersion: String, legacy: String, modern: String): String {
        requireVersion(legacy)
        requireVersion(modern)
        return if (feature(javaSpecificationVersion) <= LEGACY_MAXIMUM_FEATURE) legacy else modern
    }

    fun usesModernBridge(version: String): Boolean = version.substringBefore('.').toIntOrNull()
        ?.let { it >= MODERN_MAJOR_VERSION }
        ?: error("Arthas 版本描述非法")

    private fun feature(version: String): Int {
        val normalized = version.trim()
        val feature = if (normalized.startsWith("1.")) normalized.removePrefix("1.") else normalized
        return feature.substringBefore('.').toIntOrNull()?.takeIf { it > 0 }
            ?: error("JVM 规范版本非法")
    }

    private fun requireVersion(version: String) {
        require(version.matches(Regex("[0-9]+(\\.[0-9]+)+"))) { "Arthas 版本描述非法" }
    }

    private const val LEGACY_MAXIMUM_FEATURE = 16
    private const val MODERN_MAJOR_VERSION = 4
}

/** 原始 Arthas 资源提取结果。 */
sealed class ArthasRuntimeExtraction {
    data class Ready(val directory: Path) : ArthasRuntimeExtraction()
    data class Failed(val reason: String) : ArthasRuntimeExtraction()
}

/**
 * 从发行 jar 原子提取经构建期校验的 Arthas 最小闭包。
 *
 * 仅接收固定资源名，不接受外部路径或网络地址，保证事故现场不发生运行期下载。
 */
class ArthasRuntimeExtractor(
    private val resourceReader: (String) -> InputStream?,
) {

    fun extract(dataDirectory: Path): ArthasRuntimeExtraction {
        val version = ArthasRuntimeLayout.resolveVersion(resourceReader)
        val target = dataDirectory.resolve(version)
        return runCatching {
            val expected = readChecksums(version)
            if (!isVerified(target, expected)) {
                copyResources(version, target, expected)
            }
            check(isVerified(target, expected)) { "Arthas 运行包校验失败" }
            ArthasRuntimeExtraction.Ready(target)
        }.getOrElse { ArthasRuntimeExtraction.Failed("Arthas 运行包不可用：${it.message ?: "未知错误"}") }
    }

    private fun readChecksums(version: String): Map<String, String> {
        val bytes = readResource(version, ArthasRuntimeLayout.checksumFileName)
        return String(bytes).lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val index = line.indexOf('=')
                require(index > 0) { "Arthas 哈希清单格式错误" }
                line.substring(0, index) to line.substring(index + 1)
            }
            .also { manifest ->
                require(ArthasRuntimeLayout.requiredJarNames.all(manifest::containsKey)) { "Arthas 哈希清单不完整" }
            }
    }

    private fun isVerified(target: Path, expected: Map<String, String>): Boolean {
        val jarsOk = ArthasRuntimeLayout.requiredJarNames.all { name ->
            val file = target.resolve(name)
            Files.isRegularFile(file) && sha256(file) == expected[name]
        }
        if (!jarsOk) return false
        // 原生库按文件存在性校验（构建期未入 checksum manifest；4.x 闭包必须含 Linux x64 库与两处加载布局）
        val version = target.fileName.toString()
        val libraries = ArthasRuntimeLayout.requiredLibraries(version)
        if (libraries.isEmpty()) return true
        val platformName = platformLibraryName()
            ?: return libraries.all { Files.isRegularFile(target.resolve(it)) }
        val asyncDirFile = target.resolve("async-profiler").resolve(platformName)
        val plainFile = target.resolve("libasyncProfiler.so")
        return libraries.all { Files.isRegularFile(target.resolve(it)) } &&
            Files.isRegularFile(asyncDirFile) && Files.isRegularFile(plainFile)
    }

    /** 当前 JVM 平台对应的闭包内 async-profiler 原生库文件名；无匹配返回 null。 */
    private fun platformLibraryName(): String? {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("linux") && arch.contains("aarch64") -> "libasyncProfiler-linux-arm64.so"
            os.contains("linux") && arch.contains("64") -> "libasyncProfiler-linux-x64.so"
            os.contains("mac") -> "libasyncProfiler-mac.dylib"
            else -> null
        }
    }

    private fun copyResources(version: String, target: Path, expected: Map<String, String>) {
        Files.createDirectories(target)
        ArthasRuntimeLayout.requiredJarNames.forEach { name ->
            copyResourceAtomically(version, name, target.resolve(name), expected.getValue(name))
        }
        ArthasRuntimeLayout.requiredLibraries(version).forEach { name ->
            copyResourceAtomically(version, name, target.resolve(name), null)
        }
        // Arthas ProfilerCommand 按闭包目录下 `async-profiler/<平台库>` 的相对布局查找（基于 core jar 的 codeSource 定位），
        // 同时 AsyncProfiler.loadLibrary 按无后缀名 `libasyncProfiler.so` 从 one.profiler.libraryPath 加载：
        // 两处都补齐，确保内存 Shell 模式下 profiler 命令可用。
        platformLibrary(target)?.let { platformFile ->
            val asyncDir = target.resolve("async-profiler")
            Files.createDirectories(asyncDir)
            Files.copy(platformFile, asyncDir.resolve(platformFile.fileName.toString()), StandardCopyOption.REPLACE_EXISTING)
            val plain = target.resolve("libasyncProfiler.so")
            if (!Files.isRegularFile(plain)) {
                Files.copy(platformFile, plain, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        copyResourceAtomically(version, "LICENSE", target.resolve("LICENSE"), null)
        copyResourceAtomically(version, "NOTICE", target.resolve("NOTICE"), null)
        copyResourceAtomically(version, ArthasRuntimeLayout.checksumFileName, target.resolve(ArthasRuntimeLayout.checksumFileName), null)
    }

    /** 按当前 JVM 平台选闭包内的 async-profiler 原生库文件；无匹配返回 null。 */
    private fun platformLibrary(target: Path): Path? {
        val name = platformLibraryName() ?: return null
        val file = target.resolve(name)
        return file.takeIf(Files::isRegularFile)
    }

    private fun copyResourceAtomically(version: String, name: String, target: Path, expectedHash: String?) {
        val temporary = target.resolveSibling("${target.fileName}.part")
        try {
            resourceReader(ArthasRuntimeLayout.resourcePath(version, name))?.use { input ->
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING)
            } ?: error("缺少 Arthas 内嵌资源：$name")
            require(expectedHash == null || sha256(temporary) == expectedHash) { "Arthas 文件哈希不匹配：$name" }
            moveReplacing(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun readResource(version: String, name: String): ByteArray = resourceReader(ArthasRuntimeLayout.resourcePath(version, name))
        ?.use(InputStream::readBytes)
        ?: error("缺少 Arthas 内嵌资源：$name")

    private fun moveReplacing(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(file))
        .joinToString("") { "%02x".format(it) }
}
