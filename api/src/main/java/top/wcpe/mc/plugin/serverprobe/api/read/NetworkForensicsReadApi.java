package top.wcpe.mc.plugin.serverprobe.api.read;

import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkForensicsStatus;
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketPage;
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketQuery;

/**
 * 网络包取证只读能力(子能力)。
 *
 * 本接口是**网络包取证**一类只读能力:分页查询取证记录、读取取证服务状态。
 * 取证记录由平台适配器写入(不在本接口内),本接口只做受限读取。
 *
 * 消费者一般不必直接依赖本接口,而是经聚合接口
 * {@link top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi} 或运行期门面
 * `top.wcpe.mc.plugin.serverprobe.api.ServerProbeAPI#read()` 取得同一实例。
 *
 * 本接口**只读**:仅暴露查询能力,不暴露任何写入/控制能力。
 */
public interface NetworkForensicsReadApi {

    /**
     * 查询网络包取证记录。
     *
     * 查询必须提供有限时间范围且每页最多 100 条；实现可读 SQLite，调用方应在异步上下文调用。
     * 默认返回空页，以保持既有第三方实现本接口时的二进制与源码兼容。
     *
     * @param query 时间范围、过滤条件与游标。
     * @return 一页取证记录；取证不可用或无记录时为空页。
     */
    default NetworkPacketPage queryNetworkPackets(NetworkPacketQuery query) {
        return NetworkPacketPage.empty();
    }

    /**
     * 获取网络包取证服务状态。
     *
     * 默认明确报告未启用，保持既有第三方实现本接口时的向后兼容。
     *
     * @return 当前可用性、队列丢弃数和不可用原因。
     */
    default NetworkForensicsStatus networkForensicsStatus() {
        return NetworkForensicsStatus.unavailable("数据包取证未启用");
    }
}
