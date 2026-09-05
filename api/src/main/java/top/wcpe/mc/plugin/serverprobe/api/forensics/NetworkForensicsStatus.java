package top.wcpe.mc.plugin.serverprobe.api.forensics;

/** 网络包取证服务的运行状态。 */
@lombok.Value
@lombok.Builder(toBuilder = true)
public class NetworkForensicsStatus {
    /** SQLite 取证是否可用。 */
    boolean available;
    /** 有界队列满而主动丢弃的记录数。 */
    long droppedRecords;
    /** 不可用时的中文原因；可用时为 null。 */
    String unavailableReason;

    /** 构造明确的不可用状态。 */
    public static NetworkForensicsStatus unavailable(String reason) {
        return NetworkForensicsStatus.builder()
            .available(false)
            .droppedRecords(0L)
            .unavailableReason(reason)
            .build();
    }

    /** 供 Kotlin 与 JavaBean 消费方统一读取可用性。 */
    public boolean getAvailable() {
        return available;
    }
}
