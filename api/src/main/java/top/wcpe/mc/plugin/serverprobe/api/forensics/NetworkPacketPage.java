package top.wcpe.mc.plugin.serverprobe.api.forensics;

import java.util.Collections;
import java.util.List;

/** 网络包取证的游标分页结果，记录按捕获时间和数据库标识倒序返回。 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public class NetworkPacketPage {
    /** 当前页记录。 */
    List<NetworkPacketRecord> records;
    /** 下一页游标的捕获时间；无下一页时为 null。 */
    Long nextCursorCapturedAtMs;
    /** 下一页游标的数据库标识；无下一页时为 null。 */
    Long nextCursorId;
    /** 是否仍有下一页。 */
    boolean hasNextPage;

    /** 构造无记录的空页。 */
    public static NetworkPacketPage empty() {
        return NetworkPacketPage.builder()
            .records(Collections.emptyList())
            .nextCursorCapturedAtMs(null)
            .nextCursorId(null)
            .hasNextPage(false)
            .build();
    }

    /** 供 Kotlin 与 JavaBean 消费方统一读取下一页标志。 */
    public boolean getHasNextPage() {
        return hasNextPage;
    }
}
