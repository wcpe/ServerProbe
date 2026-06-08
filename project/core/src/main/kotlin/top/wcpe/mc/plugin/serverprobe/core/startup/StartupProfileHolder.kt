package top.wcpe.mc.plugin.serverprobe.core.startup

import top.wcpe.mc.plugin.serverprobe.api.model.StartupProfile
import top.wcpe.taboolib.ioc.annotation.Service

/**
 * 最近一次启动画像的持有者。
 *
 * 仅保存"最近一次"启动画像(M1 一次启动产出一份):由平台监听器在服务器就绪时写入([set]),
 * 由只读 API 读取([get])。作为单例 IOC [Service],写方与读方经注入共享同一实例。
 *
 * 写发生于启动就绪事件(单次),读贯穿运行期且可能跨线程,故以 `@Volatile` 保证可见性。
 * 注:P7 落盘后,历史对比从文件读取;本持有者只负责内存中"最近一份"的快速访问。
 */
@Service
class StartupProfileHolder {

    /** 最近一次启动画像;尚未产出时为 null。 */
    @Volatile
    private var latest: StartupProfile? = null

    /**
     * 最近一次启动相对上一次的对比摘要(单行中文文案);首次启动或无上一份时为 null。
     *
     * 由启动监听器在装配阶段复用 [StartupComparator.summary] 的结果写入(零重复计算),由只读 API 读取。
     * 与 [latest] 同样跨线程访问,以 `@Volatile` 保证可见性。
     */
    @Volatile
    var comparisonSummary: String? = null

    /**
     * 写入最近一次启动画像。
     *
     * @param profile 启动画像。
     */
    fun set(profile: StartupProfile) {
        latest = profile
    }

    /**
     * 读取最近一次启动画像。
     *
     * @return 最近一次启动画像;无记录时为 null。
     */
    fun get(): StartupProfile? = latest
}
