package top.wcpe.mc.plugin.serverprobe.api.read;

import top.wcpe.mc.plugin.serverprobe.api.model.startup.StartupProfile;

/**
 * 启动画像只读能力(子能力)。
 *
 * 本接口是**启动画像**一类只读能力:最近一次启动画像、历史归档画像、
 * 以及最近一次启动相对上一次的对比摘要。数据来自启动监听器与落盘归档,不做任何写入。
 *
 * 消费者一般不必直接依赖本接口,而是经聚合接口
 * {@link top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi} 或运行期门面
 * `top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI#read()` 取得同一实例。
 *
 * 本接口**只读**:仅暴露查询能力,不暴露任何写入/控制能力。
 */
public interface StartupReadApi {

    /**
     * 获取最近一次的启动画像。
     *
     * @return 最近一次启动画像;无历史记录时为 null。
     */
    StartupProfile lastStartupProfile();

    /**
     * 读取**历史归档**的若干份启动画像(M2 扩面)。
     *
     * 从落盘存储后端读取历次启动画像归档,按时间由新到旧返回至多 {@code limit} 份。
     * **可能读盘**(本地文件后端会遍历归档目录并逐份反序列化),开销随归档份数增长;
     * 因此**调用方宜在异步上下文调用**,避免阻塞主线程(规范 R7)。
     * 无可用后端或无归档时返回空列表。
     *
     * @param limit 期望返回的最大份数;非正时返回空列表。
     * @return 历史启动画像列表(由新到旧);无数据或不支持时为空列表。
     */
    java.util.List<StartupProfile> historyStartupProfiles(int limit);

    /**
     * 获取最近一次启动相对上一次的对比摘要。
     *
     * 由启动监听器在服务器就绪时算出并写入内存(单行人类可读摘要:总时长变化 + 慢插件变化)。
     *
     * @return 最近一次启动相对上一次的对比摘要;首次启动或无上一份画像时为 null。
     */
    String lastStartupComparisonSummary();
}
