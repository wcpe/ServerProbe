package top.wcpe.mc.plugin.serverprobe.velocity.forensics

import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.proxy.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * Velocity 网络取证的行为测试。
 *
 * <h2>为什么不靠反射</h2>
 * <p>以前这里 `getDeclaredField("forensics")` 断言注入字段的类型、再断言契约是接口且服务实现了它。
 * 「字段声明了什么类型」和「类实现了什么接口」都是编译器已经保证的事实，把
 * `forensics.attach(...)` 删掉、把 `detach` 写成空方法，这些断言照样绿。</p>
 *
 * <h2>现在测什么</h2>
 * <p>构造 Velocity 的真实事件对象交给监听器，再看录制型生命周期替身收到了什么：
 * PostLogin → 那个玩家被 attach；Disconnect → 那个玩家被 detach。
 * 服务实现契约也改成「当成契约对象用一下」来证明，而不是对类型元数据做断言。</p>
 *
 * <p>注：Velocity 侧就用 `PostLoginEvent` 是**对的**（此时 Player 已关联 MinecraftConnection）；
 * BungeeCord 侧才必须等到 `ServerConnectedEvent`——两边时机不同，不能一概而论。</p>
 */
class VelocityNettyForensicsIocContractTest {

    private lateinit var lifecycle: RecordingLifecycle

    @BeforeEach
    fun installLifecycle() {
        lifecycle = RecordingLifecycle()
        // 监听器是 object 且该字段是 IOC 注入位；测试直接放入替身，观察它的真实调用。
        VelocityNettyForensicsListener.forensics = lifecycle
    }

    @Test
    @DisplayName("登录完成后 attach 事件里的那个玩家")
    fun `登录完成后附着玩家`() {
        val player = fakePlayer("玩家甲")

        VelocityNettyForensicsListener.onPostLogin(PostLoginEvent(player))

        assertEquals(1, lifecycle.attached.size, "一次登录事件必须只附着一次，实际=${lifecycle.attached}")
        assertSame(player, lifecycle.attached.single(), "附着的必须是事件里那个玩家，不能是别的对象")
    }

    @Test
    @DisplayName("断连时 detach 事件里的那个玩家")
    fun `断连时拆除玩家`() {
        val player = fakePlayer("玩家乙")

        VelocityNettyForensicsListener.onDisconnect(
            DisconnectEvent(player, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN),
        )

        assertEquals(1, lifecycle.detached.size, "一次断连事件必须只拆除一次，实际=${lifecycle.detached}")
        assertSame(player, lifecycle.detached.single(), "拆除的必须是事件里那个玩家")
    }

    @Test
    @DisplayName("attach 与 detach 各走各的，互不串台")
    fun `附着与拆除互不串台`() {
        val loggedIn = fakePlayer("玩家丙")
        val disconnected = fakePlayer("玩家丁")

        VelocityNettyForensicsListener.onPostLogin(PostLoginEvent(loggedIn))
        VelocityNettyForensicsListener.onDisconnect(
            DisconnectEvent(disconnected, DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN),
        )

        assertSame(loggedIn, lifecycle.attached.single(), "附着只应发生一次，且是登录的那个玩家")
        assertSame(disconnected, lifecycle.detached.single(), "拆除只应发生一次，且是断连的那个玩家")
    }

    @Test
    @DisplayName("取证服务实现本平台的生命周期契约，且未启动时接入/拆除都安全跳过")
    fun `服务实现生命周期契约`() {
        // 赋给契约类型即证明实现关系：类型不匹配时这段代码编译不过。
        val service: VelocityNettyForensicsLifecycle = VelocityNettyForensicsService()

        // 真实行为：未启动（channels 尚未建立）时 attach/detach 必须静默跳过，不得抛异常。
        val result = runCatching {
            service.attach(fakePlayer("玩家戊"))
            service.detach(fakePlayer("玩家戊"))
        }

        assertTrue(result.isSuccess, "未启动时接入玩家必须静默跳过，实际=${result.exceptionOrNull()}")
    }

    /** 录制型生命周期替身：只记下收到的对象，不做任何平台动作。 */
    private class RecordingLifecycle : VelocityNettyForensicsLifecycle {

        val attached = mutableListOf<Any>()
        val detached = mutableListOf<Any>()

        override fun attach(player: Any) {
            attached += player
        }

        override fun detach(player: Any) {
            detached += player
        }
    }

    /** Velocity 玩家接口替身：接口方法较多，用 JDK 动态代理生成，只实现本用例会用到的几个。 */
    private fun fakePlayer(username: String): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getUsername" -> username
            "toString" -> username
            "equals" -> false
            "hashCode" -> System.identityHashCode(username)
            else -> null
        }
    } as Player
}
