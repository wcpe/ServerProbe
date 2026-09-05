package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas

import java.net.URLClassLoader
import java.nio.file.Path

/**
 * 仅向 Arthas 暴露其官方最小闭包的隔离加载器。
 *
 * 本类不调用 Arthas Bootstrap，因此不会启动其 Telnet、HTTP 或任何上游 MCP 传输层。
 */
class ArthasIsolatedClassLoader private constructor(urls: Array<java.net.URL>) : URLClassLoader(
    urls,
    ClassLoader.getSystemClassLoader().parent,
) {
    companion object {
        fun create(directory: Path): ArthasIsolatedClassLoader = ArthasIsolatedClassLoader(
            ArthasRuntimeLayout.requiredJarNames
                .map { directory.resolve(it).toUri().toURL() }
                .toTypedArray(),
        )
    }
}
