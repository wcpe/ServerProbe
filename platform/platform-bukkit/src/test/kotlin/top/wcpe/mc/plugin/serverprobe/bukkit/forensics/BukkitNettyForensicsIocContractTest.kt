package top.wcpe.mc.plugin.serverprobe.bukkit.forensics

import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * BukkitNettyForensicsListener 的行为测试。
 *
 * <h2>为什么不靠反射</h2>
 * <p>以前这里只做 `getDeclaredField("forensics")` + 类型名比对：它只能证明
 * 「字段声明成了这个接口」——把 `forensics.attach(...)` 删掉、把 `onQuit` 写成空方法，这条断言照样绿。</p>
 *
 * <h2>现在测什么</h2>
 * <p>直接构造 Bukkit 的真实事件对象交给监听器，看录制型生命周期替身收到了什么：
 * 玩家加入 → attach 事件里那个玩家；玩家离开 → detach 事件里那个玩家。
 * 两处都断言「收到的就是事件里那个玩家、且只一次」。</p>
 */
class BukkitNettyForensicsIocContractTest {

    private lateinit var lifecycle: RecordingLifecycle

    @BeforeEach
    fun installLifecycle() {
        lifecycle = RecordingLifecycle()
        // 监听器是 object 且该字段是 IOC 注入位；测试直接放入替身，观察它的真实调用。
        BukkitNettyForensicsListener.forensics = lifecycle
    }

    @Test
    @DisplayName("玩家加入时 attach 事件里的那个玩家")
    fun `加入时附着玩家`() {
        val player = fakePlayer("玩家甲")

        BukkitNettyForensicsListener.onJoin(PlayerJoinEvent(player, "玩家甲 加入了服务器"))

        assertEquals(1, lifecycle.attached.size, "一次加入事件必须只附着一次，实际=${lifecycle.attached}")
        assertSame(player, lifecycle.attached.single(), "附着的必须是事件里那个玩家，不能是别的对象")
    }

    @Test
    @DisplayName("玩家离开时 detach 事件里的那个玩家")
    fun `离开时拆除玩家`() {
        val player = fakePlayer("玩家乙")

        BukkitNettyForensicsListener.onQuit(PlayerQuitEvent(player, "玩家乙 离开了服务器"))

        assertEquals(1, lifecycle.detached.size, "一次离开事件必须只拆除一次，实际=${lifecycle.detached}")
        assertSame(player, lifecycle.detached.single(), "拆除的必须是事件里那个玩家")
    }

    @Test
    @DisplayName("attach 与 detach 各走各的，互不串台")
    fun `附着与拆除互不串台`() {
        val joined = fakePlayer("玩家丙")
        val quitted = fakePlayer("玩家丁")

        BukkitNettyForensicsListener.onJoin(PlayerJoinEvent(joined, ""))
        BukkitNettyForensicsListener.onQuit(PlayerQuitEvent(quitted, ""))

        assertSame(joined, lifecycle.attached.single(), "附着只应发生一次，且是加入的那个玩家")
        assertSame(quitted, lifecycle.detached.single(), "拆除只应发生一次，且是离开的那个玩家")
    }

    /** 录制型生命周期替身：只记下收到的玩家，不做任何平台动作。 */
    private class RecordingLifecycle : BukkitNettyForensicsLifecycle {

        val attached = mutableListOf<Player>()
        val detached = mutableListOf<Player>()

        override fun attach(player: Player) {
            attached += player
        }

        override fun detach(player: Player) {
            detached += player
        }
    }

    /** Bukkit Player 接口替身：接口方法极多，用 JDK 动态代理生成，只实现本用例会用到的几个。 */
    private fun fakePlayer(name: String): Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getName" -> name
            "toString" -> name
            "equals" -> false
            "hashCode" -> System.identityHashCode(name)
            else -> null
        }
    } as Player
}
