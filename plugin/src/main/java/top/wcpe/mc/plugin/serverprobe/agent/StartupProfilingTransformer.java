package top.wcpe.mc.plugin.serverprobe.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * 启动剖析插桩转换器（{@link ClassFileTransformer}）——统一承载启动期三个耗时 hook。
 *
 * <p>按 {@code className} 分支命中以下三个目标方法，非目标类一律返回 {@code null}（JVM 沿用原始字节码，零开销）：
 * <ul>
 *   <li>{@code org/bukkit/plugin/SimplePluginManager#enablePlugin(Lorg/bukkit/plugin/Plugin;)V}：
 *       插件 enable 耗时；插件名取<b>入参</b> {@code Plugin#getName()}。</li>
 *   <li>{@code org/bukkit/plugin/SimplePluginManager#loadPlugin(Ljava/io/File;)Lorg/bukkit/plugin/Plugin;}：
 *       插件 load 耗时；入口仅有 {@code File}，插件名只能取<b>返回值</b> {@code Plugin#getName()}，
 *       且仅在返回非 {@code null} 时记录（异常出口返回值未定，跳过）。</li>
 *   <li>{@code org/bukkit/plugin/java/LibraryLoader#createLoader(Lorg/bukkit/plugin/PluginDescriptionFile;)Ljava/lang/ClassLoader;}：
 *       库下载/加载耗时；插件名取<b>入参</b> {@code PluginDescriptionFile#getName()}。
 *       <b>该类在 Minecraft 1.8–1.16 不存在</b>——transformer 仅在类被加载时命中，低版本根本不加载该类，
 *       对应分支自然永不触发，无需任何特殊版本判断。</li>
 * </ul>
 * 上述三个 hook 点的方法描述符在各自存在的版本区间内稳定。
 *
 * <p><b>现代 Paper 盲区</b>：本阶段（A2）仅做上述主 hook。现代 Paper 的新插件体系仍以
 * {@code SimplePluginManager} 作为入口垫片，故主 hook 应能覆盖；{@code PaperPluginInstanceManager}
 * 等 Paper 内部类的选配 hook 留待 A4 真机确认后再决定，避免过早为不稳定的内部类引入复杂度。
 *
 * <p><b>插桩字节码对 {@link ProbeAgentBridge} 的引用必须能在 bootstrap ClassLoader 上解析</b>：注入点位于
 * {@code SimplePluginManager} / {@code LibraryLoader} 等<b>服务器类</b>，由服务器/Paper 的 ClassLoader 加载，
 * <b>看不到</b> system ClassLoader（{@code -javaagent} jar 所在）上的 {@code ProbeAgentBridge}。因此
 * {@link ProbeAgent#bootstrap} 在注册本转换器<b>之前</b>先由 {@link BootstrapBridgeInstaller} 把 agent 包
 * （含 {@code ProbeAgentBridge}）挂到 bootstrap ClassLoader；运行期注入字节码里的 {@code ProbeAgentBridge}
 * 符号引用便经双亲委派向上命中 bootstrap 那一份（与 agent 自有写入同一份），不再 {@code NoClassDefFoundError}。
 *
 * <p>本类对 ASM 的引用在打包阶段会被 TabooLib relocate 改写到
 * {@code top.wcpe.mc.plugin.serverprobe.agent.shadow.asm}，故运行期由 system ClassLoader 加载到的是
 * relocate 后的 ASM，不与服务器/其它插件自带 ASM 冲突。
 */
public final class StartupProfilingTransformer implements ClassFileTransformer {

    /** 数据桥的 JVM 内部名，供注入字节码调用其静态方法。 */
    private static final String BRIDGE_INTERNAL_NAME =
            "top/wcpe/mc/plugin/serverprobe/agent/ProbeAgentBridge";

    /**
     * 单个 hook 的不可变描述：目标类/方法/描述符 + 插件名取值策略 + 上报到数据桥的方法名。
     *
     * <p>用配置对象承载三个 hook 的差异点（取名来源、上报目标），使 {@link TimingAdvice} 得以复用同一套
     * 计时插桩逻辑，避免为每个 hook 复制粘贴一份增强器。
     */
    private enum HookTarget {

        /** SimplePluginManager#enablePlugin：取入参 Plugin 的名字，记入 enable 表。 */
        PLUGIN_ENABLE(
                "org/bukkit/plugin/SimplePluginManager",
                "enablePlugin",
                "(Lorg/bukkit/plugin/Plugin;)V",
                NameSource.ARG0,
                "org/bukkit/plugin/Plugin",
                true,
                "recordPluginEnable"),

        /** SimplePluginManager#loadPlugin：取返回值 Plugin 的名字（非 null 才记），记入 load 表。 */
        PLUGIN_LOAD(
                "org/bukkit/plugin/SimplePluginManager",
                "loadPlugin",
                "(Ljava/io/File;)Lorg/bukkit/plugin/Plugin;",
                NameSource.RETURN_VALUE,
                "org/bukkit/plugin/Plugin",
                true,
                "recordPluginLoad"),

        /**
         * LibraryLoader#createLoader（1.17+）：取入参 PluginDescriptionFile 的名字，记入 library 表。
         *
         * <p>注意 {@code PluginDescriptionFile} 是 <b>final 类</b>（非接口），其 {@code getName()}
         * 必须以 {@code invokevirtual} 调用——故此 hook 的 {@code interfaceCall} 为 {@code false}，
         * 与上面两个以 {@code Plugin}（接口）取名的 hook 不同。
         */
        LIBRARY_LOAD(
                "org/bukkit/plugin/java/LibraryLoader",
                "createLoader",
                "(Lorg/bukkit/plugin/PluginDescriptionFile;)Ljava/lang/ClassLoader;",
                NameSource.ARG0,
                "org/bukkit/plugin/PluginDescriptionFile",
                false,
                "recordLibraryLoad");

        /** 取插件名的来源：入参 0 或方法返回值。 */
        private enum NameSource {
            /** 从方法第 0 个参数对象上调用 {@code getName()}。 */
            ARG0,
            /** 从方法返回值对象上调用 {@code getName()}（需在出口对返回值做 null 保护）。 */
            RETURN_VALUE
        }

        /** 目标类 JVM 内部名（'/' 分隔）。 */
        private final String className;
        /** 目标方法名。 */
        private final String methodName;
        /** 目标方法描述符。 */
        private final String descriptor;
        /** 插件名取值来源。 */
        private final NameSource nameSource;
        /** 承载 {@code getName()} 的对象类型 JVM 内部名（入参或返回值的声明类型）。 */
        private final String nameHolderInternalName;
        /** {@code getName()} 是否为接口方法：true → {@code invokeinterface}，false → {@code invokevirtual}。 */
        private final boolean interfaceCall;
        /** 上报到 {@link ProbeAgentBridge} 的静态方法名（签名统一为 {@code (Ljava/lang/String;J)V}）。 */
        private final String bridgeMethod;

        HookTarget(String className, String methodName, String descriptor, NameSource nameSource,
                   String nameHolderInternalName, boolean interfaceCall, String bridgeMethod) {
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.nameSource = nameSource;
            this.nameHolderInternalName = nameHolderInternalName;
            this.interfaceCall = interfaceCall;
            this.bridgeMethod = bridgeMethod;
        }

        /** 该 hook 是否命中给定方法（类名已在外层按 className 预筛）。 */
        boolean matchesMethod(String name, String desc) {
            return methodName.equals(name) && descriptor.equals(desc);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>仅对存在 hook 的目标类做 ASM 改写，其余类返回 {@code null}（不改写）。
     * 任何改写异常都被吞掉并返回 {@code null} 降级到原始字节码——agent 插桩失败绝不能拖垮服务器启动。
     */
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        // 非目标类零开销：只要没有任何 hook 命中该类名，直接返回 null。
        if (!hasHookForClass(className)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            // COMPUTE_FRAMES + COMPUTE_MAXS：交由 ASM 重算栈映射帧与最大栈/局部表，
            // 免去手工维护，对插桩这类小改动最稳妥。
            //
            // 关键：必须用 FrameSafeClassWriter 而非裸 ClassWriter——COMPUTE_FRAMES 在合并两个
            // 引用类型的栈帧时需求其最近公共父类，ASM 默认实现 getCommonSuperClass 会用
            // Class.forName 真正加载这两个类。而 LibraryLoader#createLoader 的方法体引用了
            // 库下载用的 Aether 类型（如 org.eclipse.aether.resolution.DependencyResult），这些类
            // 对加载本 ClassWriter 的 system/agent ClassLoader 不可见 → forName 抛
            // ClassNotFoundException → 帧计算抛 TypeNotPresentException → 被下方 catch 吞掉 →
            // 返回 null → 该 hook 永不生效（这正是 libraryTimings 始终为空的真因）。
            // FrameSafeClassWriter 优先用"被改写类自己的 ClassLoader"解析，再退化到 Object，彻底规避。
            ClassWriter writer = new FrameSafeClassWriter(reader, loader);
            ClassVisitor visitor = new ProfilingClassVisitor(writer, className);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch (Throwable ignored) {
            // 插桩失败时降级：返回 null 保留原始字节码，保证服务器正常启动。
            return null;
        }
    }

    /**
     * 帧计算安全的 {@link ClassWriter}：覆盖 {@link #getCommonSuperClass} 使其
     * <b>不会因类型不可加载而抛异常</b>。
     *
     * <p>问题背景：{@link ClassWriter#COMPUTE_FRAMES} 在合并栈帧引用类型时要求两类型的最近公共父类，
     * ASM 默认实现以 {@link Class#forName} 加载二者。被改写的服务器类（如 {@code LibraryLoader}）方法体
     * 可能引用对本 {@code ClassWriter} 加载器不可见的第三方类型（如库下载用的 Aether 类），默认实现会
     * {@code ClassNotFoundException} → 帧计算失败 → 整个插桩被降级丢弃。
     *
     * <p>两道防线：
     * <ol>
     *   <li>把解析委派给<b>被改写类自身的 {@link ClassLoader}</b>（{@code transform} 的 {@code loader} 参数）——
     *       服务器类的那些第三方类型对它自己的加载器通常可见，多数情况下能算出真实公共父类；</li>
     *   <li>万一仍解析不到（如 {@code loader} 为 {@code null} 的 bootstrap 类，或类确实缺失），
     *       退化返回 {@code java/lang/Object}——它是所有引用类型的合法上界，
     *       生成的帧偏保守但<b>字节码合法且能通过 JVM 校验</b>，绝不再抛异常。</li>
     * </ol>
     */
    private static final class FrameSafeClassWriter extends ClassWriter {

        /** 被改写类自身的 ClassLoader（可能为 {@code null}，表示 bootstrap ClassLoader）。 */
        private final ClassLoader classLoader;

        FrameSafeClassWriter(ClassReader classReader, ClassLoader classLoader) {
            super(classReader, COMPUTE_FRAMES | COMPUTE_MAXS);
            this.classLoader = classLoader;
        }

        /**
         * {@inheritDoc}
         *
         * <p>用被改写类自身的加载器解析两类型并求最近公共父类；任何解析失败一律退化为
         * {@code java/lang/Object}，保证 {@code COMPUTE_FRAMES} 永不因类型不可加载而中断插桩。
         */
        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                Class<?> c1 = Class.forName(type1.replace('/', '.'), false, classLoader);
                Class<?> c2 = Class.forName(type2.replace('/', '.'), false, classLoader);
                if (c1.isAssignableFrom(c2)) {
                    return type1;
                }
                if (c2.isAssignableFrom(c1)) {
                    return type2;
                }
                if (c1.isInterface() || c2.isInterface()) {
                    return "java/lang/Object";
                }
                Class<?> common = c1;
                do {
                    common = common.getSuperclass();
                } while (!common.isAssignableFrom(c2));
                return common.getName().replace('.', '/');
            } catch (Throwable ignored) {
                // 类型不可加载（第三方/缺失/bootstrap）时退化到 Object：合法上界，绝不抛异常拖垮插桩。
                return "java/lang/Object";
            }
        }
    }

    /** 是否存在命中该类名的 hook。 */
    private static boolean hasHookForClass(String className) {
        for (HookTarget hook : HookTarget.values()) {
            if (hook.className.equals(className)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 类访问器：对命中的方法套上计时 {@link TimingAdvice}，其余方法原样透传。
     */
    private static final class ProfilingClassVisitor extends ClassVisitor {

        /** 当前被改写的类名，用于在 {@link #visitMethod} 中按类筛选 hook。 */
        private final String className;

        ProfilingClassVisitor(ClassVisitor classVisitor, String className) {
            super(Opcodes.ASM9, classVisitor);
            this.className = className;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            // 在本类的 hook 中查找命中当前方法者；命中即套增强器，否则透传。
            for (HookTarget hook : HookTarget.values()) {
                if (hook.className.equals(className) && hook.matchesMethod(name, descriptor)) {
                    return new TimingAdvice(mv, access, name, descriptor, hook);
                }
            }
            return mv;
        }
    }

    /**
     * 通用计时增强器：入口存 nanoTime，出口算耗时并按 {@link HookTarget} 配置上报数据桥。
     *
     * <p>沿用 A1 的稳健做法：出口处先把 {@code costMs} 算好存入局部变量，再按"先名字、后耗时"的顺序压栈，
     * 不做脆弱的操作数栈换位，也不破坏原方法出口栈（异常出口栈顶的异常对象保持原位，异常照常向上抛）。
     *
     * <p>{@link AdviceAdapter#onMethodExit(int)} 覆盖<b>所有</b>出口（正常 RETURN 与异常 ATHROW）。
     * 对 {@code RETURN_VALUE} 取名的 hook（loadPlugin），出口分两种处理：
     * <ul>
     *   <li>异常出口（{@code ATHROW}）：返回值未定，<b>跳过</b>记录。</li>
     *   <li>正常返回（{@code ARETURN}）：栈顶即返回的 {@code Plugin}，先 {@code dup} 一份做非 null 判断，
     *       非 null 才取名上报，避免 {@code NullPointerException}。</li>
     * </ul>
     */
    private static final class TimingAdvice extends AdviceAdapter {

        /** 本增强器对应的 hook 配置。 */
        private final HookTarget hook;

        /** 入口 nanoTime 的局部变量槽位（由 {@code newLocal} 分配）。 */
        private int startNanosLocal;

        TimingAdvice(MethodVisitor methodVisitor, int access, String name, String descriptor, HookTarget hook) {
            super(Opcodes.ASM9, methodVisitor, access, name, descriptor);
            this.hook = hook;
        }

        @Override
        protected void onMethodEnter() {
            // long start = System.nanoTime();
            invokeStatic(Type.getType(System.class),
                    new org.objectweb.asm.commons.Method("nanoTime", "()J"));
            startNanosLocal = newLocal(Type.LONG_TYPE);
            storeLocal(startNanosLocal, Type.LONG_TYPE);
        }

        @Override
        protected void onMethodExit(int opcode) {
            if (hook.nameSource == HookTarget.NameSource.RETURN_VALUE) {
                onReturnValueExit(opcode);
            } else {
                onArgExit();
            }
        }

        /**
         * 入参取名（enablePlugin / createLoader）：所有出口路径都记录。
         *
         * <p>不依赖出口栈内容，故正常与异常出口一致处理；从 {@code arg0} 取名最稳。
         */
        private void onArgExit() {
            int costMsLocal = computeCostMsLocal();
            // bridgeMethod(arg0.getName(), costMs);
            loadArg(0);
            invokeNameGetter();
            loadLocal(costMsLocal, Type.LONG_TYPE);
            invokeBridge();
        }

        /**
         * 返回值取名（loadPlugin）：仅正常返回且返回非 null 时记录，异常出口跳过。
         *
         * <p>为彻底规避"dup 副本跨分支"导致的栈映射帧不一致，这里改用<b>局部变量中转</b>：
         * 把返回值存入局部 → 全程从局部读取做判空/取名/重新压回，出口栈始终只有一个 {@code ret}，
         * 两条分支到 {@code ARETURN} 前的栈状态天然一致，最稳妥。
         *
         * @param opcode 出口字节码（{@code ARETURN}/{@code ATHROW} 等）
         */
        private void onReturnValueExit(int opcode) {
            // 异常出口：返回值未定，直接跳过（不动栈顶异常对象，保证异常正常抛出）。
            if (opcode != ARETURN) {
                return;
            }
            int costMsLocal = computeCostMsLocal();
            // 把待返回的 Plugin 从栈顶存入局部变量，栈清空；后续从局部读取，避免 dup 跨分支。
            Type pluginType = Type.getObjectType(hook.nameHolderInternalName);
            int retLocal = newLocal(pluginType);
            storeLocal(retLocal, pluginType);

            // if (ret != null) { bridge(ret.getName(), costMs); }
            loadLocal(retLocal, pluginType);
            Label skip = new Label();
            ifNull(skip); // 消费这份引用，仅做判空
            loadLocal(retLocal, pluginType);
            invokeNameGetter();
            loadLocal(costMsLocal, Type.LONG_TYPE);
            invokeBridge();
            mark(skip);

            // 把返回值重新压回栈顶，交给原 ARETURN 返回；两条分支到此栈均为 [ret]。
            loadLocal(retLocal, pluginType);
        }

        /**
         * 计算 {@code costMs = (System.nanoTime() - start) / 1_000_000} 并存入新局部变量。
         *
         * @return 存放 costMs 的局部变量槽位
         */
        private int computeCostMsLocal() {
            invokeStatic(Type.getType(System.class),
                    new org.objectweb.asm.commons.Method("nanoTime", "()J"));
            loadLocal(startNanosLocal, Type.LONG_TYPE);
            math(SUB, Type.LONG_TYPE);
            push(1_000_000L);
            math(DIV, Type.LONG_TYPE);
            int costMsLocal = newLocal(Type.LONG_TYPE);
            storeLocal(costMsLocal, Type.LONG_TYPE);
            return costMsLocal;
        }

        /**
         * 在栈顶对象上调用 {@code getName():String}。
         *
         * <p>按 hook 配置选择调用指令：接口（{@code Plugin}）走 {@code invokeinterface}，
         * final 类（{@code PluginDescriptionFile}）走 {@code invokevirtual}，二者不可混用否则生成非法字节码。
         */
        private void invokeNameGetter() {
            org.objectweb.asm.commons.Method getName =
                    new org.objectweb.asm.commons.Method("getName", "()Ljava/lang/String;");
            Type holder = Type.getObjectType(hook.nameHolderInternalName);
            if (hook.interfaceCall) {
                invokeInterface(holder, getName);
            } else {
                invokeVirtual(holder, getName);
            }
        }

        /** 调用数据桥上报方法 {@code bridgeMethod(String, long)}（栈：name, costMs）。 */
        private void invokeBridge() {
            invokeStatic(Type.getObjectType(BRIDGE_INTERNAL_NAME),
                    new org.objectweb.asm.commons.Method(hook.bridgeMethod, "(Ljava/lang/String;J)V"));
        }
    }
}
