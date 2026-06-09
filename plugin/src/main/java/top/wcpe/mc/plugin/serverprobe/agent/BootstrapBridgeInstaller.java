package top.wcpe.mc.plugin.serverprobe.agent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * 把<b>唯一</b>数据桥 {@link ProbeAgentBridge} 安装到 bootstrap ClassLoader 的安装器（纯 Java）。
 *
 * <p><b>为什么需要它</b>：{@link StartupProfilingTransformer} 给 {@code SimplePluginManager#enablePlugin}
 * 等<b>服务器类</b>织入了调用 {@link ProbeAgentBridge} 静态方法的字节码，而这些服务器类由 Paper/服务器自身的
 * ClassLoader 加载，<b>看不到</b> app ClassLoader（{@code -javaagent} jar 所在）上的 {@code ProbeAgentBridge}
 * → 插桩后方法首次执行即抛 {@code NoClassDefFoundError}（真机表现为 enablePlugin 报 ERROR）。
 *
 * <p><b>修复原理</b>：bootstrap ClassLoader 是所有 ClassLoader 的祖先。把数据桥提取到一个临时 jar 后用
 * {@link Instrumentation#appendToBootstrapClassLoaderSearch(JarFile)} 挂上去，任何被插桩的服务器类按双亲委派
 * 向上都能解析到 bootstrap 上的 {@code ProbeAgentBridge}。
 *
 * <p><b>提取范围只含数据桥（关键，本次修复的核心）</b>：仅提取
 * {@code top/wcpe/mc/plugin/serverprobe/agent/ProbeAgentBridge.class}（含其内部类 {@code ProbeAgentBridge$*.class}，
 * 如未来引入）。<b>绝不</b>再把 {@link ProbeAgent} / {@link StartupStackSampler} /
 * {@link StartupProfilingTransformer} / relocate 后的影子 ASM 提到 bootstrap。原因是 premain 入口
 * {@code ProbeAgent} <b>必然由 app ClassLoader 加载</b>（{@code -javaagent} jar 在 app classpath，JVM 用 app CL
 * 加载 {@code Premain-Class}）；若把 {@code StartupStackSampler} 等同包类也 append 到 bootstrap，则 app CL 的
 * {@code ProbeAgent} 引用它们时按双亲委派<b>先命中 bootstrap 那一份</b>，形成"app CL 的类引用 bootstrap CL 上同包类"
 * 的<b>跨 ClassLoader（app↔bootstrap）访问</b> → {@code IllegalAccessError}（同包不同 runtime package）→ premain
 * 抛异常 → JVM 启动失败。bootstrap 上<b>只需要</b>放被插桩字节码引用的类，而那<b>只有</b>数据桥一个。
 *
 * <p><b>数据落点一致性</b>：本安装器仍须在 {@link ProbeAgent#bootstrap} 中、<b>任何对 {@code ProbeAgentBridge}
 * 的引用之前</b>调用。这样 app CL 在解析 agent 自身代码（{@link ProbeAgent} / {@link StartupStackSampler}）里的
 * {@code ProbeAgentBridge} 引用时，会按双亲委派<b>先问 bootstrap</b>并命中 bootstrap 那一份——app CL 因此
 * <b>不会</b>再定义自己的副本。最终：premain 自身写入（app CL → 向上委派到 bootstrap）、栈采样写入（app CL → 向上
 * 委派到 bootstrap）、插桩字节码读写（服务器 CL → 向上委派到 bootstrap）落在<b>同一份</b> {@code ProbeAgentBridge}，
 * 不存在数据分裂。注意此处<b>不</b>存在 {@code IllegalAccessError}：app CL 的 agent 类引用的是数据桥（bootstrap），
 * 数据桥所有对外方法均为 {@code public static}，跨 ClassLoader 访问合法。
 *
 * <p><b>容错</b>：定位 jar / 提取 / append 任一环节失败，仅向 {@code System.err} 记录并返回 {@code false}（降级），
 * 由调用方据此<b>跳过插桩注册</b>，<b>绝不</b>让异常冒泡导致 JVM 启动失败。
 *
 * <p>设计约束：<b>纯 JDK</b>，由 app ClassLoader 加载，只依赖 {@code java.*}，不触碰 kotlin/taboolib/bukkit。
 */
final class BootstrapBridgeInstaller {

    /** 数据桥 class 在 jar 中的精确条目名（唯一需要进 bootstrap 的类）。 */
    private static final String BRIDGE_CLASS_ENTRY =
            "top/wcpe/mc/plugin/serverprobe/agent/ProbeAgentBridge.class";

    /** 数据桥内部类条目前缀（如未来引入 {@code ProbeAgentBridge$Xxx.class}，一并提取以免 bootstrap 缺类）。 */
    private static final String BRIDGE_INNER_CLASS_PREFIX =
            "top/wcpe/mc/plugin/serverprobe/agent/ProbeAgentBridge$";

    /** 临时 jar 文件名前缀（便于在系统临时目录中辨识来源）。 */
    private static final String TEMP_JAR_PREFIX = "serverprobe-agent-bootstrap";

    /** 临时 jar 文件名后缀。 */
    private static final String TEMP_JAR_SUFFIX = ".jar";

    /** 工具容器，禁止实例化。 */
    private BootstrapBridgeInstaller() {
    }

    /**
     * 把数据桥 {@link ProbeAgentBridge} 提取到临时 jar 并挂到 bootstrap ClassLoader。
     *
     * <p>必须在注册 {@link StartupProfilingTransformer} 之前、且在首次引用 {@link ProbeAgentBridge} 之前调用。
     *
     * @param inst JVM 提供的字节码插桩接口；为 {@code null} 时直接降级返回 {@code false}
     * @return 成功挂载返回 {@code true}；定位/提取/append 失败返回 {@code false}（调用方据此降级、跳过插桩）
     */
    static boolean install(Instrumentation inst) {
        if (inst == null) {
            return false;
        }
        try {
            File agentJar = locateAgentJar();
            if (agentJar == null) {
                System.err.println("[ServerProbe] 无法定位 agent jar，跳过 bootstrap 注入并降级（不注册插桩，仅保留栈采样）");
                return false;
            }
            Path tempJar = Files.createTempFile(TEMP_JAR_PREFIX, TEMP_JAR_SUFFIX);
            // JVM 退出时清理临时 jar；append 后该文件在整个进程生命周期被 bootstrap 持有，故仅退出时删除。
            tempJar.toFile().deleteOnExit();
            int extracted = extractBridgeClass(agentJar, tempJar.toFile());
            if (extracted <= 0) {
                System.err.println("[ServerProbe] 从 agent jar 未提取到数据桥 ProbeAgentBridge.class，跳过 bootstrap 注入并降级");
                return false;
            }
            // try-with-resources 保证 JarFile 句柄释放；appendToBootstrapClassLoaderSearch 会读取其内容。
            try (JarFile jarFile = new JarFile(tempJar.toFile())) {
                inst.appendToBootstrapClassLoaderSearch(jarFile);
            }
            return true;
        } catch (Throwable t) {
            // 任何异常都不允许冒泡：premain 抛异常会直接导致 JVM 启动失败。降级即可。
            System.err.println("[ServerProbe] 注入 agent 类到 bootstrap ClassLoader 失败，降级（不注册插桩）：" + t);
            return false;
        }
    }

    /**
     * 定位 agent 自身 jar 文件（即 ServerProbe.jar，{@code -javaagent} 指向的那个）。
     *
     * <p>经 {@link ProbeAgent} 的 {@link CodeSource} 取其加载来源 URL 并转 {@link File}。
     * 来源不是普通可读 jar 文件（如 IDE 直跑的 class 目录）时返回 {@code null}，由调用方降级。
     *
     * @return agent jar 文件；无法定位或非 jar 文件时为 {@code null}
     * @throws URISyntaxException URL 转 URI 失败时抛出（由 {@link #install} 统一兜底）
     */
    private static File locateAgentJar() throws URISyntaxException {
        ProtectionDomain protectionDomain = ProbeAgent.class.getProtectionDomain();
        if (protectionDomain == null) {
            return null;
        }
        CodeSource codeSource = protectionDomain.getCodeSource();
        if (codeSource == null) {
            return null;
        }
        URL location = codeSource.getLocation();
        if (location == null) {
            return null;
        }
        File file = new File(location.toURI());
        // 必须是存在的普通文件（jar）：目录形态（IDE 直跑）无法作为 JarFile 提取，降级处理。
        if (!file.isFile()) {
            return null;
        }
        return file;
    }

    /**
     * 把 {@code agentJar} 中数据桥 {@link ProbeAgentBridge}（及其内部类）的条目复制到 {@code targetJar}。
     *
     * <p><b>只复制数据桥</b>：匹配精确条目 {@link #BRIDGE_CLASS_ENTRY} 或内部类前缀 {@link #BRIDGE_INNER_CLASS_PREFIX}，
     * 其余 agent 类（{@link ProbeAgent}/{@link StartupStackSampler}/{@link StartupProfilingTransformer}/影子 ASM）
     * 一律<b>不</b>提取——它们留在 app ClassLoader，避免与 app CL 加载的 {@code ProbeAgent} 形成跨 CL 同包访问的
     * {@code IllegalAccessError}。
     *
     * @param agentJar  源 agent jar
     * @param targetJar 目标临时 jar
     * @return 实际复制的条目数（应为 1，含内部类时更多；用于判定是否提取到数据桥）
     * @throws IOException 读写 jar 失败时抛出（由 {@link #install} 统一兜底）
     */
    private static int extractBridgeClass(File agentJar, File targetJar) throws IOException {
        int count = 0;
        // 双 try-with-resources：源 JarFile 与目标 JarOutputStream 均确保关闭，杜绝句柄泄露。
        try (JarFile source = new JarFile(agentJar);
             JarOutputStream target = new JarOutputStream(Files.newOutputStream(targetJar.toPath()))) {
            Enumeration<JarEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !isBridgeEntry(entry.getName())) {
                    continue;
                }
                copyEntry(source, entry, target);
                count++;
            }
        }
        return count;
    }

    /**
     * 判定 jar 条目是否属于数据桥（精确类或其内部类）。
     *
     * @param entryName jar 条目名（'/' 分隔）
     * @return 命中数据桥类或其内部类返回 {@code true}
     */
    private static boolean isBridgeEntry(String entryName) {
        return BRIDGE_CLASS_ENTRY.equals(entryName) || entryName.startsWith(BRIDGE_INNER_CLASS_PREFIX);
    }

    /**
     * 复制单个 jar 条目（去掉压缩元数据，按默认 DEFLATED 重写）到目标流。
     *
     * @param source 源 jar
     * @param entry  待复制条目
     * @param target 目标 jar 输出流
     * @throws IOException 读写失败时抛出
     */
    private static void copyEntry(JarFile source, JarEntry entry, JarOutputStream target) throws IOException {
        // 新建同名条目：不沿用原 entry（其可能带 STORED + CRC/size 约束，跨流复制易触发校验异常）。
        target.putNextEntry(new JarEntry(entry.getName()));
        try (InputStream in = source.getInputStream(entry)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                target.write(buffer, 0, read);
            }
        }
        target.closeEntry();
    }
}
