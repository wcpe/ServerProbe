package top.wcpe.mc.plugin.serverprobe.bungee.forensics

import net.md_5.bungee.api.connection.ProxiedPlayer
import net.md_5.bungee.api.event.PlayerDisconnectEvent
import net.md_5.bungee.api.event.ServerConnectedEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * BungeeNettyForensicsListener 的行为测试。
 *
 * <h2>为什么不靠反射</h2>
 * <p>以前这里 `getDeclaredField("forensics")` 断言注入字段的类型、再用 `declaredMethods` 扫方法参数类型。
 * 前者只证明「字段声明了这个类型」——把 `forensics.attach(...)` 删掉照样通过；
 * 后者扫的是「有没有某个方法收这个事件」，同样碰不到任何实际行为。</p>
 *
 * <h2>现在测什么</h2>
 * <p>直接构造 BungeeCord 的真实事件对象交给监听器，再看录制型生命周期替身收到了什么：
 * 后端连接完成 → 那个玩家被 attach；断开 → 那个玩家被 detach。
 * 两处都断言「收到的就是事件里那个玩家、且只一次」，把「处理了别的对象」也挡在门外。</p>
 *
 * <h2>“不得在 PostLogin 阶段附着”怎么守</h2>
 * <p>这是句**否定命题**（“没有某个方法”），行为入口调不到它；但“有没有引用 `PostLoginEvent` 这个类型”
 * 在编译产物里是确定的：只要真的写了监听方法，那个类型的内部名 `net/md_5/bungee/api/event/PostLoginEvent`
 * 就必然出现在 class 常量池里。所以直接读字节码断言它**不在**——不是反射，也不读源码文本
 * （注释里提到这个词不会造成假失败）。
 *
 * <p>它真正的后果是：初始管线在桥接阶段被替换，导致后端连接完成后无法附着、桥接重连拿不到载荷。
 * 这层由 `network-bungee` E2E 场景在真机守（机器人必须完成一次真实重连并采到第二条载荷）。</p>
 */
class BungeeNettyForensicsIocContractTest {

    private lateinit var lifecycle: RecordingLifecycle

    @BeforeEach
    fun installLifecycle() {
        lifecycle = RecordingLifecycle()
        // 监听器是 object 且该字段是 IOC 注入位；测试直接放入替身，观察它的真实调用。
        BungeeNettyForensicsListener.forensics = lifecycle
    }

    @Test
    @DisplayName("后端连接完成后 attach 事件里的那个玩家")
    fun `后端连接完成后附着玩家`() {
        val player = fakePlayer("玩家甲")

        BungeeNettyForensicsListener.onServerConnected(ServerConnectedEvent(player, null))

        assertEquals(1, lifecycle.attached.size, "一次后端连接事件必须只附着一次，实际=${lifecycle.attached}")
        assertSame(player, lifecycle.attached.single(), "附着的必须是事件里那个玩家，不能是别的对象")
    }

    @Test
    @DisplayName("玩家断开时 detach 事件里的那个玩家")
    fun `断开时拆除玩家`() {
        val player = fakePlayer("玩家乙")

        BungeeNettyForensicsListener.onDisconnect(PlayerDisconnectEvent(player))

        assertEquals(1, lifecycle.detached.size, "一次断开事件必须只拆除一次，实际=${lifecycle.detached}")
        assertSame(player, lifecycle.detached.single(), "拆除的必须是事件里那个玩家")
    }

    @Test
    @DisplayName("attach 与 detach 各走各的，互不串台")
    fun `附着与拆除互不串台`() {
        val joined = fakePlayer("玩家丙")
        val quitted = fakePlayer("玩家丁")

        BungeeNettyForensicsListener.onServerConnected(ServerConnectedEvent(joined, null))
        BungeeNettyForensicsListener.onDisconnect(PlayerDisconnectEvent(quitted))

        assertSame(joined, lifecycle.attached.single(), "附着只应发生一次，且是连接的那个玩家")
        assertSame(quitted, lifecycle.detached.single(), "拆除只应发生一次，且是断开的那个玩家")
    }

    // 服务侧的 attach/detach 行为：未启动（channels 未建立）时必须静默跳过。
    // 这是本模块里唯一能真测的服务行为——start() 需要 ProbeConfig 与真实 ProxyServer，
    // 那条由 `network-bungee` E2E 场景在真实 BungeeCord 上跑。

    @Test
    @DisplayName("取证服务实现本平台的生命周期契约，且未启动时接入/拆除都安全跳过")
    fun `服务实现生命周期契约`() {
        // 赋给契约类型即证明实现关系：类型不匹配时这段代码编译不过。
        val service: BungeeNettyForensicsLifecycle = BungeeNettyForensicsService()
        val player = fakePlayer("玩家巳")

        // 真实行为：未执行 start() 时 channels 为 null，接入/拆除必须静默返回，不得抛 NPE。
        val result = runCatching {
            service.attach(player)
            service.detach(player)
        }

        assertTrue(result.isSuccess, "未启动时接入/拆除必须静默跳过，实际=${result.exceptionOrNull()}")
    }

    @Test
    @DisplayName("编译产物不引用 PostLoginEvent（否则初始管线会在桥接阶段被替换）")
    fun `不得在PostLogin阶段附着`() {
        val classFile = classesRoot().resolve(LISTENER_CLASS_PATH)
        assertTrue(classFile.isFile, "必须能定位到监听器的编译产物，实际=${classFile.absolutePath}")

        val bytes = classFile.readBytes()
        assertTrue(
            !contains(bytes, FORBIDDEN_EVENT_DESCRIPTOR.toByteArray(Charsets.UTF_8)),
            "不得引用 $FORBIDDEN_EVENT_DESCRIPTOR：那时初始管线还在桥接阶段，" +
                "替换它会导致后端连接完成后无法附着",
        )
        // 正向对照：换一个必须存在的类型，证明这次扫描的字节确实读到了（否则“没找到”毫无意义）。
        assertTrue(
            contains(bytes, REQUIRED_EVENT_DESCRIPTOR.toByteArray(Charsets.UTF_8)),
            "应当在同一个 class 里能找到 $REQUIRED_EVENT_DESCRIPTOR，否则扫描本身就没生效",
        )
    }

    /** 录制型生命周期替身：只记下收到的对象，不做任何平台动作。 */
    private class RecordingLifecycle : BungeeNettyForensicsLifecycle {

        val attached = mutableListOf<Any>()
        val detached = mutableListOf<Any>()

        override fun attach(player: Any) {
            attached += player
        }

        override fun detach(player: Any) {
            detached += player
        }
    }

    /** 本模块编译输出目录（从已加载类的 codesource 取，随构建目录变化）。 */
    private fun classesRoot(): java.io.File {
        val location = BungeeNettyForensicsListener::class.java.protectionDomain.codeSource.location
        return java.io.File(location.toURI())
    }

    /** 在字节数组里找一个 ASCII 子串。 */
    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }

    /** BungeeCord 玩家接口替身：接口方法众多，用 JDK 动态代理生成，只实现本用例会用到的几个。 */
    private fun fakePlayer(name: String): ProxiedPlayer = Proxy.newProxyInstance(
        ProxiedPlayer::class.java.classLoader,
        arrayOf(ProxiedPlayer::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getName" -> name
            "toString" -> name
            "equals" -> false
            "hashCode" -> System.identityHashCode(name)
            else -> null
        }
    } as ProxiedPlayer

    private companion object {
        /** 监听器在模块编译输出目录里的相对路径。 */
        const val LISTENER_CLASS_PATH =
            "top/wcpe/mc/plugin/serverprobe/bungee/forensics/BungeeNettyForensicsListener.class"

        /** 不得出现的事件类型（内部名形式）。 */
        const val FORBIDDEN_EVENT_DESCRIPTOR = "net/md_5/bungee/api/event/PostLoginEvent"

        /** 必须出现的对照类型（证明扫描真的读到了字节）。 */
        const val REQUIRED_EVENT_DESCRIPTOR = "net/md_5/bungee/api/event/ServerConnectedEvent"
    }
}
