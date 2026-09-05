package top.wcpe.mc.plugin.serverprobe.diagnostics.arthas

import java.lang.instrument.Instrumentation
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

/**
 * Arthas 双运行时的进程内反射适配器。
 *
 * 3 系直接构造内存 Shell；4 系以禁用所有上游端口的官方 Bootstrap 创建会话级任务——命令结果经
 * PackingResultDistributor 收集 ResultModel（不依赖 term 回写，与官方 MCP server 执行方式一致）。
 */
class ArthasMemoryShellRunner(
    private val runtimeDirectory: Path,
    private val instrumentation: () -> Instrumentation?,
) : ArthasCommandRunner, AutoCloseable {
    @Volatile private var legacySpyInitialized = false
    @Volatile private var loader: ArthasIsolatedClassLoader? = null
    @Volatile private var bootstrap: Any? = null

    override fun run(command: String, output: ArthasTaskOutputBuffer) {
        val inst = instrumentation() ?: error("当前 JVM 未提供 Instrumentation，请先以 premain 启动或显式重试 attach")
        val currentLoader = runtimeLoader()
        if (usesModernApi()) {
            initializeModernBootstrap(currentLoader, inst)
            executeModern(currentLoader, command, output)
        } else {
            initializeLegacySpy(currentLoader, inst)
            initializeLegacyBootstrap(currentLoader, inst)
            execute(currentLoader, legacyShellServer(currentLoader, inst), command, output)
        }
    }

    override fun close() {
        synchronized(this) {
            destroyBootstrap()
            loader?.close()
            loader = null
            legacySpyInitialized = false
        }
    }

    private fun runtimeLoader(): ArthasIsolatedClassLoader = loader ?: synchronized(this) {
        loader ?: ArthasIsolatedClassLoader.create(runtimeDirectory).also { loader = it }
    }

    private fun usesModernApi(): Boolean = ArthasRuntimeSelector.usesModernBridge(runtimeDirectory.fileName.toString())

    private fun initializeLegacyBootstrap(loader: ClassLoader, inst: Instrumentation) {
        if (bootstrap != null) return
        synchronized(this) {
            if (bootstrap != null) return
            val type = loader.loadClass("com.taobao.arthas.core.server.ArthasBootstrap")
            // 只初始化 Arthas 进程内执行器；不调用 bind，因此不会启动 Telnet、HTTP 或上游 MCP 端点。
            bootstrap = type.getMethod("getInstance", Int::class.javaPrimitiveType, Instrumentation::class.java)
                .invoke(null, processId(), inst)
        }
    }

    /** 4 系官方会话级无终端执行：job 结果由 PackingResultDistributor 收集 ResultModel，term 仅作占位。 */
    @Suppress("TooGenericExceptionCaught")
    private fun executeModern(loader: ClassLoader, command: String, output: ArthasTaskOutputBuffer) {
        var phase = "获取 Arthas 会话管理器"
        try {
            val currentBootstrap = requireNotNull(bootstrap) { "Arthas Bootstrap 未初始化" }
            val sessionManager = currentBootstrap.javaClass.getMethod("getSessionManager").invoke(currentBootstrap)
            phase = "创建一次性会话"
            val session = sessionManager.javaClass.getMethod("createSession").invoke(sessionManager)
            try {
                val sessionType = loader.loadClass("com.taobao.arthas.core.shell.session.Session")
                val termType = loader.loadClass("com.taobao.arthas.core.shell.term.Term")
                // 输出收集器：4 系命令结果统一走 ResultModel -> ResultDistributor，与官方 MCP server 相同。
                phase = "初始化结果收集器"
                val distributor = loader.loadClass("com.taobao.arthas.core.distribution.impl.PackingResultDistributorImpl")
                    .getConstructor(sessionType).newInstance(session)
                // 无终端 term，但 tt/monitor 等老式命令的实时输出走 term.write 直写——这里同步收集到任务缓冲，
                // 官方无终端执行的 model 主通道不受影响（term 直写仅作补充捕获）。
                val term = Proxy.newProxyInstance(loader, arrayOf(termType)) { proxy, method, args ->
                    when (method.name) {
                        "toString" -> "mcp-memory-term"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "write", "echo", "echoTips" -> {
                            args?.firstOrNull()?.toString()?.let(output::append)
                            proxy
                        }
                        "type" -> "mcp-memory"
                        "width" -> 1000
                        "height" -> 200
                        "lastAccessedTime" -> System.currentTimeMillis()
                        "stdinHandler", "stdoutHandler", "resizehandler",
                        "interruptHandler", "suspendHandler", "closeHandler", "setSession" -> proxy
                        else -> null
                    }
                }
                // 等待逻辑必须响应任务线程中断（取消由 ArthasTaskManager 通过 future.cancel 触发），
                // 故不直接使用官方 CommandExecutorImpl.executeSync（其等待循环吞中断、不响应取消）。
                phase = "创建命令任务"
                val commandManager = sessionManager.javaClass.getMethod("getCommandManager").invoke(sessionManager)
                val jobController = sessionManager.javaClass.getMethod("getJobController").invoke(sessionManager)
                val jobListenerType = loader.loadClass("com.taobao.arthas.core.shell.system.JobListener")
                val jobListener = Proxy.newProxyInstance(loader, arrayOf(jobListenerType)) { proxy, method, args ->
                    when (method.name) {
                        "toString" -> "mcp-memory-job-listener"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        else -> null
                    }
                }
                val tokens = loader.loadClass("com.taobao.arthas.core.shell.cli.CliTokens")
                    .getMethod("tokenize", String::class.java).invoke(null, command)
                val job = jobController.javaClass.getMethod(
                    "createJob",
                    loader.loadClass("com.taobao.arthas.core.shell.system.impl.InternalCommandManager"),
                    List::class.java,
                    sessionType,
                    jobListenerType,
                    termType,
                    loader.loadClass("com.taobao.arthas.core.distribution.ResultDistributor"),
                ).invoke(jobController, commandManager, tokens, session, jobListener, term, distributor)
                phase = "运行命令任务"
                job.javaClass.getMethod("run").invoke(job)
                phase = "等待命令任务"
                try {
                    waitForCompletion(job)
                } catch (error: InterruptedException) {
                    // 取消/超时中断：job.interrupt 是异步终止，先等 arthas job 落终态，再收集 distributor 中
                    // 已产生但未取走的结果（monitor/tt 等长观测任务的超时输出不丢），最后保持取消语义。
                    runCatching {
                        val deadline = System.currentTimeMillis() + JOB_TERMINATE_WAIT_MILLIS
                        while (System.currentTimeMillis() < deadline) {
                            if (job.javaClass.getMethod("status").invoke(job).toString() != "RUNNING") break
                            Thread.sleep(50)
                        }
                        val pending = distributor.javaClass.getMethod("getResults").invoke(distributor) as? List<*> ?: emptyList<Any?>()
                        renderModels(currentBootstrap, loader, output, pending)
                    }
                    throw error
                }
                phase = "收集命令结果"
                val models = distributor.javaClass.getMethod("getResults").invoke(distributor) as? List<*> ?: emptyList<Any?>()
                renderModels(currentBootstrap, loader, output, models)
            } finally {
                runCatching {
                    val sessionId = session.javaClass.getMethod("getSessionId").invoke(session)
                    sessionManager.javaClass.getMethod("removeSession", String::class.java).invoke(sessionManager, sessionId)
                }
            }
        } catch (error: InterruptedException) {
            // 取消/超时中断必须直通：TaskManager 依赖捕获 InterruptedException 将任务归档为 CANCELLED，
            // 若包装成 ArthasExecutionFailure(IllegalStateException) 会被误标为 FAILED 且 phase 误导。
            throw error
        } catch (error: Throwable) {
            throw ArthasExecutionFailure(phase, error)
        }
    }

    /**
     * 官方终端渲染结果：ResultModel 经 ResultViewResolver 找到视图，draw 到伪 CommandProcess
     * （write/echo 回写任务缓冲），输出与 arthas 终端一致；ANSI 控制符与回车清洗为纯文本。
     */
    @Suppress("TooGenericExceptionCaught")
    private fun renderModels(bootstrap: Any, loader: ClassLoader, output: ArthasTaskOutputBuffer, models: List<*>) {
        if (models.isEmpty()) return
        val buffer = StringBuilder()
        val processType = loader.loadClass("com.taobao.arthas.core.shell.command.CommandProcess")
        val process = Proxy.newProxyInstance(loader, arrayOf(processType)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "mcp-memory-command-process"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "write", "echo", "echoTips" -> {
                    (args?.firstOrNull() as? CharSequence)?.let(buffer::append)
                    proxy
                }
                "type" -> "mcp-memory"
                "width" -> 1000
                "height" -> 200
                else -> null
            }
        }
        val resolver = bootstrap.javaClass.getMethod("getResultViewResolver").invoke(bootstrap)
        val resultModelType = loader.loadClass("com.taobao.arthas.core.command.model.ResultModel")
        for (model in models) {
            if (model == null) continue
            runCatching {
                val view = resolver.javaClass.getMethod("getResultView", resultModelType).invoke(resolver, model)
                    ?: return@runCatching
                view.javaClass.getMethod("draw", processType, model.javaClass).invoke(view, process, model)
            }
        }
        val text = buffer.toString()
            .replace(ANSI_ESCAPE, "")
            .replace("\r", "")
        if (text.isNotBlank()) output.append(text)
    }

    private fun initializeModernBootstrap(loader: ClassLoader, inst: Instrumentation) {
        if (bootstrap != null) return
        synchronized(this) {
            if (bootstrap != null) return
            val parameters = ArthasModernBootstrapSettings.parameters(runtimeDirectory)
            val type = loader.loadClass("com.taobao.arthas.core.server.ArthasBootstrap")
            // 4 系 Bootstrap 会统一初始化命令依赖；端口与 MCP endpoint 均显式禁用，不对外监听。
            bootstrap = type.getMethod("getInstance", Instrumentation::class.java, Map::class.java)
                .invoke(null, inst, parameters)
        }
    }

    private fun destroyBootstrap() {
        bootstrap?.let { value ->
            val termination = workerTermination(value)
            runCatching { value.javaClass.getMethod("destroy").invoke(value) }
            termination?.let(::awaitWorkerTermination)
        }
        bootstrap = null
    }

    private fun workerTermination(value: Any): Any? = runCatching {
        val field = value.javaClass.getDeclaredField("workerGroup")
        field.isAccessible = true
        field.get(value)?.let { worker -> worker.javaClass.getMethod("terminationFuture").invoke(worker) }
    }.getOrNull()

    private fun awaitWorkerTermination(termination: Any) {
        runCatching {
            termination.javaClass.getMethod("awaitUninterruptibly", Long::class.javaPrimitiveType, TimeUnit::class.java)
                .invoke(termination, WORKER_SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    private fun initializeLegacySpy(loader: ClassLoader, inst: Instrumentation) {
        if (legacySpyInitialized) return
        synchronized(this) {
            if (legacySpyInitialized) return
            inst.appendToBootstrapClassLoaderSearch(JarFile(runtimeDirectory.resolve("arthas-spy.jar").toFile()))
            val weaver = loader.loadClass("com.taobao.arthas.core.advisor.AdviceWeaver")
            val bootstrap = loader.loadClass("com.taobao.arthas.agent.AgentBootstrap")
            val spy = Class.forName("java.arthas.Spy", true, null)
            val callbacks = arrayOf(
                weaver.getMethod(
                    "methodOnBegin",
                    Int::class.javaPrimitiveType,
                    ClassLoader::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Any::class.java,
                    Array<Any>::class.java,
                ),
                weaver.getMethod("methodOnReturnEnd", Any::class.java),
                weaver.getMethod("methodOnThrowingEnd", Throwable::class.java),
                weaver.getMethod(
                    "methodOnInvokeBeforeTracing",
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                ),
                weaver.getMethod(
                    "methodOnInvokeAfterTracing",
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                ),
                weaver.getMethod(
                    "methodOnInvokeThrowTracing",
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                ),
                bootstrap.getMethod("resetArthasClassLoader"),
            )
            // Method.invoke / getMethod 均为 Java 变长参数,反射展开必须使用 spread。
            @Suppress("SpreadOperator")
            spy.getMethod("initForAgentLauncher", ClassLoader::class.java, *Array(7) { java.lang.reflect.Method::class.java })
                .invoke(null, loader, *callbacks)
            legacySpyInitialized = true
        }
    }

    private fun legacyShellServer(loader: ClassLoader, inst: Instrumentation): Any {
        val options = loader.loadClass("com.taobao.arthas.core.shell.ShellServerOptions").getConstructor().newInstance()
        options.javaClass.getMethod("setInstrumentation", Instrumentation::class.java).invoke(options, inst)
        options.javaClass.getMethod("setPid", Int::class.javaPrimitiveType).invoke(options, processId())
        val shellServerType = loader.loadClass("com.taobao.arthas.core.shell.impl.ShellServerImpl")
        val server = shellServerType.getConstructor(options.javaClass).newInstance(options)
        val resolver = loader.loadClass("com.taobao.arthas.core.command.BuiltinCommandPack").getConstructor().newInstance()
        val resolverType = loader.loadClass("com.taobao.arthas.core.shell.command.CommandResolver")
        server.javaClass.getMethod("registerCommandResolver", resolverType).invoke(server, resolver)
        server.javaClass.getMethod("setClosed", Boolean::class.javaPrimitiveType).invoke(server, false)
        return server
    }

    /** 3 系内存 Shell 执行（legacy）：每任务独立 server，结束时关闭回收。 */
    private fun execute(
        loader: ClassLoader,
        server: Any,
        command: String,
        output: ArthasTaskOutputBuffer,
    ) {
        var phase = "创建 Shell 配置"
        // 反射链路上的 Error(如 LinkageError)也必须归档为任务失败,故 catch(Throwable) 有意为之。
        @Suppress("TooGenericExceptionCaught")
        try {
            phase = "创建内存终端"
            val term = memoryTerm(loader, output)
            val termType = loader.loadClass("com.taobao.arthas.core.shell.term.Term")
            val shell = server.javaClass.getMethod("createShell", termType).invoke(server, term)
            phase = "初始化 Shell"
            shell.javaClass.getMethod("init").invoke(shell)
            phase = "创建命令任务"
            val job = shell.javaClass.getMethod("createJob", String::class.java).invoke(shell, command)
            phase = "运行命令任务"
            job.javaClass.getMethod("run").invoke(job)
            phase = "等待命令任务"
            waitForCompletion(job)
            server.javaClass.getMethod("setClosed", Boolean::class.javaPrimitiveType).invoke(server, true)
        } catch (error: InterruptedException) {
            // 与 executeModern 一致：取消/超时中断直通，保持任务 CANCELLED 语义。
            throw error
        } catch (error: Throwable) {
            throw ArthasExecutionFailure(phase, error)
        }
    }

    private fun memoryTerm(loader: ClassLoader, output: ArthasTaskOutputBuffer): Any {
        val term = loader.loadClass("com.taobao.arthas.core.shell.term.Term")
        return Proxy.newProxyInstance(loader, arrayOf(term)) { proxy, method, args ->
            when (method.name) {
                "write", "echo" -> { output.append(args?.firstOrNull()?.toString() ?: ""); proxy }
                "type" -> "mcp-memory"
                "width" -> 200
                "height" -> 80
                "lastAccessedTime" -> System.currentTimeMillis()
                "close", "readline" -> null
                else -> proxy
            }
        }
    }

    private fun waitForCompletion(job: Any) {
        while (job.javaClass.getMethod("status").invoke(job).toString() == "RUNNING") {
            if (Thread.currentThread().isInterrupted) {
                job.javaClass.getMethod("interrupt").invoke(job)
                throw InterruptedException("Arthas 任务已取消")
            }
            Thread.sleep(20)
        }
    }

    private fun processId(): Int = java.lang.management.ManagementFactory.getRuntimeMXBean().name.substringBefore('@').toInt()

    private companion object {
        const val WORKER_SHUTDOWN_WAIT_MILLIS = 1_000L
        const val JOB_TERMINATE_WAIT_MILLIS = 3_000L
        private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    }
}

/** 4 系官方 Bootstrap 的最小本地配置，禁止创建任何 TCP 或 Tunnel 连接。 */
internal object ArthasModernBootstrapSettings {

    fun parameters(runtimeDirectory: Path): Map<String, String> = linkedMapOf(
        "arthas.home" to runtimeDirectory.toString(),
        "arthas.outputPath" to runtimeDirectory.resolve("output").toString(),
        "arthas.telnetPort" to "-1",
        "arthas.httpPort" to "-1",
        "arthas.mcpEndpoint" to "",
    )
}

/** 仅携带固定执行阶段，不保留用户命令原文。 */
class ArthasExecutionFailure(val phase: String, cause: Throwable) : IllegalStateException(cause)
