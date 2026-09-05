package top.wcpe.mc.plugin.serverprobe.api.forensics;

/** 数据包相对服务器进程的传输方向。 */
public enum PacketDirection {
    /** 客户端或后端进入当前进程。 */
    INGRESS,
    /** 当前进程发往客户端或后端。 */
    EGRESS
}
