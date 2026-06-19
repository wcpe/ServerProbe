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
 * 启动剖析插桩转换器（{@link ClassFileTransformer}）——统一承载启动期七个耗时 hook。
 *
 * <p>按 {@code className} 分支命中目标方法，非目标类一律返回 {@code null}（JVM 沿用原始字节码，零开销）。
 * 命中的方法在入口存 nanoTime、在出口取 nanoTime，统一调用 {@link ProbeAgentBridge#recordSpan} 上报
 * （type、name、起止纳秒）。七个 hook：
 * <ul>
 *   <li>{@code SimplePluginManager#enablePlugin}：插件 enable 耗时（入参 {@code Plugin}）。</li>
 *   <li>{@code SimplePluginManager#loadPlugin}：插件 load 耗时（返回值 {@code Plugin}，非 null 才记）。</li>
 *   <li>{@code LibraryLoader#createLoader}（1.17+）：库下载/加载耗时（入参 {@code PluginDescriptionFile}）。</li>
 *   <li>{@code CraftServer#createWorld}：世界创建耗时（入参 {@code WorldCreator}）。{@code CraftServer} 类名
 *       含/不含版本号包名（{@code org/bukkit/craftbukkit/CraftServer} 或 {@code .../v1_x/CraftServer}），故按
 *       <b>后缀 {@code /CraftServer}</b> 匹配——不再用宽前缀 {@code org/bukkit/craftbukkit}（那会让整包数百个类
 *       都进入 ASM 全量重写，白付帧重算开销）。</li>
 *   <li>{@code YamlConfiguration#loadConfiguration(File)}（静态）：配置加载耗时（入参 {@code File}）。</li>
 *   <li>{@code SimplePluginManager#registerEvents}：事件注册耗时（第二参 {@code Plugin}）。</li>
 *   <li>{@code SimpleCommandMap#register}：命令注册耗时（第三参 {@code Command}）。</li>
 * </ul>
 *
 * <p><b>插桩字节码对 {@link ProbeAgentBridge} 的引用必须能在 bootstrap ClassLoader 上解析</b>：注入点位于
 * {@code SimplePluginManager} / {@code LibraryLoader} / {@code CraftServer} 等<b>服务器类</b>，由服务器/Paper
 * 的 ClassLoader 加载，<b>看不到</b> system ClassLoader 上的 {@code ProbeAgentBridge}。因此
 * {@link ProbeAgent#bootstrap} 在注册本转换器<b>之前</b>先经 {@link BootstrapBridgeInstaller} 把数据桥挂到
 * bootstrap ClassLoader；运行期注入字节码里的 {@code ProbeAgentBridge} 符号引用便经双亲委派命中 bootstrap 那一份。
 *
 * <p>本类对 ASM 的引用在打包阶段会被 TabooLib relocate 改写到
 * {@code top.wcpe.mc.plugin.serverprobe.agent.shadow.asm}，故运行期加载到的是 relocate 后的 ASM，不与服务器/
 * 其它插件自带 ASM 冲突。
 */
public final class StartupProfilingTransformer implements ClassFileTransformer {

    /** 数据桥的 JVM 内部名，供注入字节码调用其静态方法。 */
    private static final String BRIDGE_INTERNAL_NAME =
            "top/wcpe/mc/plugin/serverprobe/agent/ProbeAgentBridge";

    /** HTTP 外呼 hook：{@code sun.net.www.protocol.http.HttpURLConnection}(HTTPS 经其子类继承同一 getInputStream)。 */
    private static final String HTTP_URL_CONNECTION_CLASS = "sun/net/www/protocol/http/HttpURLConnection";

    /** TCP 外呼 hook：{@code java.net.Socket}。 */
    private static final String SOCKET_CLASS = "java/net/Socket";

    /**
     * 单个 hook 的不可变描述：目标类/方法/描述符 + 取名策略 + 时间线类型。
     *
     * <p>用配置对象承载各 hook 的差异点（取名来源、取名方法、类匹配模式、type 标签），使 {@link TimingAdvice}
     * 得以复用同一套计时插桩逻辑，避免为每个 hook 复制粘贴增强器。所有 hook 出口统一调用
     * {@link ProbeAgentBridge#recordSpan}，故不再需要逐 hook 的上报方法名。
     */
    private enum HookTarget {

        /** SimplePluginManager#enablePlugin：取入参 Plugin 的名字，type=enable。 */
        PLUGIN_ENABLE(
                "org/bukkit/plugin/SimplePluginManager",
                "enablePlugin",
                "(Lorg/bukkit/plugin/Plugin;)V",
                NameSource.ARG0,
                "org/bukkit/plugin/Plugin",
                true,
                "getName",
                false,
                "enable"),

        /** SimplePluginManager#loadPlugin：取返回值 Plugin 的名字（非 null 才记），type=load。 */
        PLUGIN_LOAD(
                "org/bukkit/plugin/SimplePluginManager",
                "loadPlugin",
                "(Ljava/io/File;)Lorg/bukkit/plugin/Plugin;",
                NameSource.RETURN_VALUE,
                "org/bukkit/plugin/Plugin",
                true,
                "getName",
                false,
                "load"),

        /**
         * LibraryLoader#createLoader（1.17+）：取入参 PluginDescriptionFile 的名字，type=library。
         *
         * <p>{@code PluginDescriptionFile} 是 <b>final 类</b>（非接口），其 {@code getName()} 须以
         * {@code invokevirtual} 调用——故 {@code interfaceCall} 为 {@code false}。
         */
        LIBRARY_LOAD(
                "org/bukkit/plugin/java/LibraryLoader",
                "createLoader",
                "(Lorg/bukkit/plugin/PluginDescriptionFile;)Ljava/lang/ClassLoader;",
                NameSource.ARG0,
                "org/bukkit/plugin/PluginDescriptionFile",
                false,
                "getName",
                false,
                "library"),

        /**
         * CraftServer#createWorld：取入参 WorldCreator 的名字，type=worldCreate。
         *
         * <p>className 取后缀 {@code /CraftServer}，按 {@code endsWith} 匹配（{@code suffixMatch=true}），
         * 兼容含/不含版本号的两种包名，且不波及同包其它类。
         */
        WORLD_CREATE(
                "/CraftServer",
                "createWorld",
                "(Lorg/bukkit/WorldCreator;)Lorg/bukkit/World;",
                NameSource.ARG0,
                "org/bukkit/WorldCreator",
                false,
                "name",
                true,
                "worldCreate"),

        /** YamlConfiguration#loadConfiguration（静态）：取入参 File 的名字，type=configLoad。 */
        CONFIG_LOAD(
                "org/bukkit/configuration/file/YamlConfiguration",
                "loadConfiguration",
                "(Ljava/io/File;)Lorg/bukkit/configuration/file/YamlConfiguration;",
                NameSource.ARG0,
                "java/io/File",
                false,
                "getName",
                false,
                "configLoad"),

        /** SimplePluginManager#registerEvents：取第二参 Plugin 的名字，type=eventRegister。 */
        EVENT_REGISTER(
                "org/bukkit/plugin/SimplePluginManager",
                "registerEvents",
                "(Lorg/bukkit/event/Listener;Lorg/bukkit/plugin/Plugin;)V",
                NameSource.ARG1,
                "org/bukkit/plugin/Plugin",
                true,
                "getName",
                false,
                "eventRegister"),

        /** SimpleCommandMap#register：取第三参 Command 的名字，type=commandRegister。Command 是抽象类，invokevirtual。 */
        COMMAND_REGISTER(
                "org/bukkit/command/SimpleCommandMap",
                "register",
                "(Ljava/lang/String;Ljava/lang/String;Lorg/bukkit/command/Command;)Z",
                NameSource.ARG2,
                "org/bukkit/command/Command",
                false,
                "getName",
                false,
                "commandRegister");

        /** 取名来源：入参索引或方法返回值。 */
        private enum NameSource {
            /** 从方法第 0 个参数对象上调用取名方法。 */
            ARG0,
            /** 从方法第 1 个参数对象上调用取名方法。 */
            ARG1,
            /** 从方法第 2 个参数对象上调用取名方法。 */
            ARG2,
            /** 从方法返回值对象上调用取名方法（需在出口对返回值做 null 保护）。 */
            RETURN_VALUE
        }

        /** 目标类 JVM 内部名（'/' 分隔）；{@code suffixMatch} 时为后缀（如 {@code /CraftServer}）。 */
        private final String className;
        /** 目标方法名。 */
        private final String methodName;
        /** 目标方法描述符。 */
        private final String descriptor;
        /** 取名来源。 */
        private final NameSource nameSource;
        /** 承载取名方法的对象类型 JVM 内部名（入参或返回值的声明类型）。 */
        private final String nameHolderInternalName;
        /** 取名方法是否为接口方法：true → {@code invokeinterface}，false → {@code invokevirtual}。 */
        private final boolean interfaceCall;
        /** 在 nameHolder 上调用的取名方法名（如 "getName"、"name"）。 */
        private final String nameMethodName;
        /** 是否对 className 做后缀匹配（用于含版本号包名的 CraftServer）。 */
        private final boolean suffixMatch;
        /** 时间线事件类型标识（如 "enable"、"load"），传给 {@link ProbeAgentBridge#recordSpan}。 */
        private final String timelineType;

        HookTarget(String className, String methodName, String descriptor, NameSource nameSource,
                   String nameHolderInternalName, boolean interfaceCall,
                   String nameMethodName, boolean suffixMatch, String timelineType) {
            this.className = className;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.nameSource = nameSource;
            this.nameHolderInternalName = nameHolderInternalName;
            this.interfaceCall = interfaceCall;
            this.nameMethodName = nameMethodName;
            this.suffixMatch = suffixMatch;
            this.timelineType = timelineType;
        }

        /** 该 hook 是否命中给定类名。 */
        boolean matchesClass(String className) {
            return suffixMatch ? className.endsWith(this.className) : this.className.equals(className);
        }

        /** 根据 nameSource 返回对应的参数索引（仅 ARG* 有效）。 */
        int argIndex() {
            switch (nameSource) {
                case ARG1: return 1;
                case ARG2: return 2;
                default: return 0;
            }
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
            // COMPUTE_FRAMES + COMPUTE_MAXS：交由 ASM 重算栈映射帧与最大栈/局部表，对插桩这类小改动最稳妥。
            // 必须用 FrameSafeClassWriter（而非裸 ClassWriter）规避 getCommonSuperClass 因类型不可加载抛异常，
            // 否则插桩被降级丢弃（详见 FrameSafeClassWriter 文档）。
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
     * <p>{@link ClassWriter#COMPUTE_FRAMES} 合并栈帧引用类型时要求最近公共父类，ASM 默认实现以
     * {@link Class#forName} 加载二者。被改写的服务器类方法体可能引用对本 {@code ClassWriter} 加载器不可见的
     * 第三方类型（如库下载用的 Aether 类），默认实现会 {@code ClassNotFoundException} → 帧计算失败 → 整个插桩
     * 被降级丢弃。两道防线：① 委派给被改写类自身的 {@link ClassLoader} 解析；② 仍失败则退化返回
     * {@code java/lang/Object}（合法上界，字节码合法且能通过校验），绝不再抛异常。
     */
    private static final class FrameSafeClassWriter extends ClassWriter {

        /** 被改写类自身的 ClassLoader（可能为 {@code null}，表示 bootstrap ClassLoader）。 */
        private final ClassLoader classLoader;

        FrameSafeClassWriter(ClassReader classReader, ClassLoader classLoader) {
            super(classReader, COMPUTE_FRAMES | COMPUTE_MAXS);
            this.classLoader = classLoader;
        }

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

    /** 是否存在命中该类名的 hook（含启动期 hook 与运行期外呼 hook）。 */
    private static boolean hasHookForClass(String className) {
        if (className == null) {
            return false;
        }
        if (HTTP_URL_CONNECTION_CLASS.equals(className) || SOCKET_CLASS.equals(className)) {
            return true;
        }
        for (HookTarget hook : HookTarget.values()) {
            if (hook.matchesClass(className)) {
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
            // 运行期外呼 hook(始终开启,由数据桥自身的开关控制是否记录):
            // HttpURLConnection.getInputStream → 完整 HTTP 记录;Socket.connect → 原始 TCP 记录。
            if (HTTP_URL_CONNECTION_CLASS.equals(className)
                    && "getInputStream".equals(name) && "()Ljava/io/InputStream;".equals(descriptor)) {
                return new ConnectionAdvice(mv, access, name, descriptor, true, "httpExit", "java/net/HttpURLConnection");
            }
            if (SOCKET_CLASS.equals(className)
                    && "connect".equals(name) && "(Ljava/net/SocketAddress;I)V".equals(descriptor)) {
                return new ConnectionAdvice(mv, access, name, descriptor, false, "recordSocketConnect", "java/net/Socket");
            }
            // 启动期 hook：在本类的 HookTarget 中查找命中当前方法者；命中即套计时增强器，否则透传。
            for (HookTarget hook : HookTarget.values()) {
                if (hook.matchesClass(className) && hook.matchesMethod(name, descriptor)) {
                    return new TimingAdvice(mv, access, name, descriptor, hook);
                }
            }
            return mv;
        }
    }

    /**
     * 通用计时增强器：入口存 nanoTime，出口取 nanoTime 并按 {@link HookTarget} 配置上报数据桥。
     *
     * <p>入口与出口各取一次原始 {@code System.nanoTime()}，<b>不在字节码里做毫秒除法</b>——精度损失留给数据桥
     * 按需换算，时间线由此保留纳秒分辨率。{@link AdviceAdapter#onMethodExit(int)} 覆盖<b>所有</b>出口（正常
     * RETURN 与异常 ATHROW）。对 {@code RETURN_VALUE} 取名的 hook（loadPlugin）：异常出口跳过；正常返回时用
     * 局部变量中转返回值做非 null 判断后取名（避免 dup 跨分支导致栈帧不一致）。
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
            // 出口处取真实结束时刻（纳秒）。无论正常/异常出口都先取，存入局部供后续使用（异常路径仅多一次廉价取数）。
            invokeStatic(Type.getType(System.class),
                    new org.objectweb.asm.commons.Method("nanoTime", "()J"));
            int endNanosLocal = newLocal(Type.LONG_TYPE);
            storeLocal(endNanosLocal, Type.LONG_TYPE);
            if (hook.nameSource == HookTarget.NameSource.RETURN_VALUE) {
                onReturnValueExit(opcode, endNanosLocal);
            } else {
                onArgExit(endNanosLocal);
            }
        }

        /**
         * 入参取名（enablePlugin / createLoader / registerEvents / 等）：所有出口路径都记录。
         *
         * @param endNanosLocal 出口结束时刻局部变量槽位
         */
        private void onArgExit(int endNanosLocal) {
            // recordSpan(type, name, startNanos, endNanos);
            push(hook.timelineType);
            loadArg(hook.argIndex());
            invokeNameGetter();
            loadLocal(startNanosLocal, Type.LONG_TYPE);
            loadLocal(endNanosLocal, Type.LONG_TYPE);
            invokeRecordSpan();
        }

        /**
         * 返回值取名（loadPlugin）：仅正常返回且返回非 null 时记录，异常出口跳过。
         *
         * <p>用局部变量中转返回值，全程从局部读取做判空/取名/重新压回，两条分支到 {@code ARETURN} 前栈状态天然一致。
         *
         * @param opcode        出口字节码（{@code ARETURN}/{@code ATHROW} 等）
         * @param endNanosLocal 出口结束时刻局部变量槽位
         */
        private void onReturnValueExit(int opcode, int endNanosLocal) {
            // 异常出口：返回值未定，直接跳过（不动栈顶异常对象，保证异常正常抛出）。
            if (opcode != ARETURN) {
                return;
            }
            // 把待返回的 Plugin 从栈顶存入局部变量，栈清空；后续从局部读取，避免 dup 跨分支。
            Type pluginType = Type.getObjectType(hook.nameHolderInternalName);
            int retLocal = newLocal(pluginType);
            storeLocal(retLocal, pluginType);

            // if (ret != null) { recordSpan(type, ret.getName(), startNanos, endNanos); }
            loadLocal(retLocal, pluginType);
            Label skip = new Label();
            ifNull(skip); // 消费这份引用，仅做判空
            push(hook.timelineType);
            loadLocal(retLocal, pluginType);
            invokeNameGetter();
            loadLocal(startNanosLocal, Type.LONG_TYPE);
            loadLocal(endNanosLocal, Type.LONG_TYPE);
            invokeRecordSpan();
            mark(skip);

            // 把返回值重新压回栈顶，交给原 ARETURN 返回；两条分支到此栈均为 [ret]。
            loadLocal(retLocal, pluginType);
        }

        /**
         * 在栈顶对象上调用取名方法（如 {@code getName()}/{@code name()}），返回 {@code String}。
         *
         * <p>接口（{@code Plugin}）走 {@code invokeinterface}，类（{@code PluginDescriptionFile}/{@code Command}/
         * {@code File}/{@code WorldCreator}）走 {@code invokevirtual}，二者不可混用否则生成非法字节码。
         */
        private void invokeNameGetter() {
            org.objectweb.asm.commons.Method getName =
                    new org.objectweb.asm.commons.Method(hook.nameMethodName, "()Ljava/lang/String;");
            Type holder = Type.getObjectType(hook.nameHolderInternalName);
            if (hook.interfaceCall) {
                invokeInterface(holder, getName);
            } else {
                invokeVirtual(holder, getName);
            }
        }

        /** 调用数据桥 {@code recordSpan(String, String, long, long)}（栈：type, name, startNanos, endNanos）。 */
        private void invokeRecordSpan() {
            invokeStatic(Type.getObjectType(BRIDGE_INTERNAL_NAME),
                    new org.objectweb.asm.commons.Method("recordSpan",
                            "(Ljava/lang/String;Ljava/lang/String;JJ)V"));
        }
    }

    /**
     * 运行期外呼增强器：入口存 nanoTime(并可选调 {@code httpEnter}),出口把 {@code this} 与起始时刻交给数据桥。
     *
     * <p>注入<b>极简</b>(仅 {@code 取时 + 可选 httpEnter + 出口 invokestatic(this, start)}),所有读取连接/socket
     * 信息与记录的逻辑都在数据桥里且全程 {@code try/catch} 兜底——保证即便监控逻辑出错也<b>绝不破坏</b>应用的网络调用。
     * 出口覆盖正常返回与异常({@link AdviceAdapter#onMethodExit(int)}),失败的外呼亦被记录。
     */
    private static final class ConnectionAdvice extends AdviceAdapter {

        /** 是否在入口调用 {@code ProbeAgentBridge.httpEnter()}(仅 HttpURLConnection 需要,用于 socket 去重/递归保护)。 */
        private final boolean callHttpEnter;
        /** 出口上报的数据桥方法名({@code httpExit} 或 {@code recordSocketConnect})。 */
        private final String exitMethod;
        /** 出口方法首参的对象类型 JVM 内部名({@code java/net/HttpURLConnection} 或 {@code java/net/Socket})。 */
        private final String holderInternalName;
        /** 入口 nanoTime 局部变量槽位。 */
        private int startNanosLocal;

        ConnectionAdvice(MethodVisitor methodVisitor, int access, String name, String descriptor,
                         boolean callHttpEnter, String exitMethod, String holderInternalName) {
            super(Opcodes.ASM9, methodVisitor, access, name, descriptor);
            this.callHttpEnter = callHttpEnter;
            this.exitMethod = exitMethod;
            this.holderInternalName = holderInternalName;
        }

        @Override
        protected void onMethodEnter() {
            invokeStatic(Type.getType(System.class),
                    new org.objectweb.asm.commons.Method("nanoTime", "()J"));
            startNanosLocal = newLocal(Type.LONG_TYPE);
            storeLocal(startNanosLocal, Type.LONG_TYPE);
            if (callHttpEnter) {
                invokeStatic(Type.getObjectType(BRIDGE_INTERNAL_NAME),
                        new org.objectweb.asm.commons.Method("httpEnter", "()V"));
            }
        }

        @Override
        protected void onMethodExit(int opcode) {
            // bridge.exitMethod(this, startNanos);  —— 不依赖也不破坏出口栈顶(返回值/异常对象原位保留)
            loadThis();
            loadLocal(startNanosLocal, Type.LONG_TYPE);
            invokeStatic(Type.getObjectType(BRIDGE_INTERNAL_NAME),
                    new org.objectweb.asm.commons.Method(exitMethod, "(L" + holderInternalName + ";J)V"));
        }
    }
}
