package top.wcpe.mc.plugin.serverprobe.e2e.network;

import top.wcpe.mc.plugin.serverprobe.ServerProbeApi;
import top.wcpe.mc.plugin.serverprobe.api.ProbeReadApi;
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketPage;
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketQuery;
import top.wcpe.mc.plugin.serverprobe.api.forensics.NetworkPacketRecord;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;

/** 三种平台共用的 FR11 真实验收断言与权威结果文件写入器。 */
public final class NetworkForensicsE2eSupport {

    private static final String RESULT_FILE = "MC_TESTKIT_E2E_RESULT_FILE";
    private static final String TOKEN = "network-forensics-e2e";
    private static final int DEFAULT_RETRIES = 30;

    private NetworkForensicsE2eSupport() {
    }

    /** 仅在目标场景中等待数据落库并验证 FR8、Web 和 Prometheus 三个出口。 */
    public static void verifyIfRequested(String scenario, String platformPrefix, int metricsPort, int webPort) {
        verifyIfRequested(scenario, platformPrefix, metricsPort, webPort, DEFAULT_RETRIES);
    }

    /** 代理重连场景可传入更长轮询窗口，仍只以当前服务端真实查询结果判定。 */
    public static void verifyIfRequested(String scenario, String platformPrefix, int metricsPort, int webPort, int retries) {
        verifyIfRequested(scenario, platformPrefix, metricsPort, webPort, retries, null, null);
    }

    /** 代理场景按预期白名单包类型查询，避免高频出站包挤出单页结果。 */
    public static void verifyIfRequested(
        String scenario,
        String platformPrefix,
        int metricsPort,
        int webPort,
        int retries,
        String expectedPacketType,
        String expectedChannel
    ) {
        if (!scenario.equals(System.getenv("MC_TESTKIT_E2E_SCENARIO"))) return;
        String resultPath = System.getenv(RESULT_FILE);
        if (resultPath == null || resultPath.trim().isEmpty()) return;
        try {
            verifyEventually(platformPrefix, metricsPort, webPort, retries, expectedPacketType, expectedChannel);
            writeResult(new File(resultPath), true, "FR11 网络取证真实验收通过");
        } catch (Exception exception) {
            writeResult(new File(resultPath), false, exception.getMessage());
        }
    }

    private static void verifyEventually(String platformPrefix, int metricsPort, int webPort, int retries) throws Exception {
        verifyEventually(platformPrefix, metricsPort, webPort, retries, null, null);
    }

    private static void verifyEventually(
        String platformPrefix,
        int metricsPort,
        int webPort,
        int retries,
        String expectedPacketType,
        String expectedChannel
    ) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < retries; attempt++) {
            try {
                verify(platformPrefix, metricsPort, webPort, expectedPacketType, expectedChannel);
                return;
            } catch (Exception exception) {
                lastFailure = exception;
                Thread.sleep(1_000L);
            }
        }
        throw new IllegalStateException("等待 FR11 取证数据超时：" + lastFailure.getMessage(), lastFailure);
    }

    private static void verify(
        String platformPrefix,
        int metricsPort,
        int webPort,
        String expectedPacketType,
        String expectedChannel
    ) throws Exception {
        ProbeReadApi api = ServerProbeApi.INSTANCE.read();
        require(api != null, "ServerProbe FR8 API 尚未就绪");
        require(api.networkForensicsStatus().getAvailable(), "SQLite 取证存储未就绪");

        long now = System.currentTimeMillis();
        NetworkPacketPage page = api.queryNetworkPackets(NetworkPacketQuery.builder()
            .sinceMs(now - 120_000L)
            .untilMs(now)
            .packetType(expectedPacketType)
            .limit(100)
            .build());
        List<NetworkPacketRecord> records = page.getRecords();
        require(!records.isEmpty(), "FR8 未查询到真实协议记录");
        require(records.stream().anyMatch(record -> record.getPacketType().startsWith(platformPrefix)), "未记录 " + platformPrefix + " 包类型");
        require(records.stream().anyMatch(record -> "127.0.0.1".equals(record.getIp())), "FR8 未返回完整回环 IP");
        require(records.stream().anyMatch(record ->
            record.getPayloadCaptured() && (expectedChannel == null || expectedChannel.equals(record.getChannel()))
        ), "白名单 Plugin Message 载荷未保存");
        require(records.stream().anyMatch(record -> record.getPayloadTruncated() && record.getOriginalLength() > 16_384), "未验证协议合法载荷的前缀截断");
        require(records.stream().anyMatch(record -> record.getPayloadSha256() != null && record.getPayloadSha256().matches("[0-9a-f]{64}")), "未保留完整载荷 SHA-256");

        String metrics = get("http://127.0.0.1:" + metricsPort + "/metrics");
        require(metrics.contains("serverprobe_network_packet_type_packets"), "Prometheus 未导出包类型计数");
        require(metrics.contains("serverprobe_network_masked_ip_packets"), "Prometheus 未导出脱敏 IP Top100");
        require(!metrics.contains("127.0.0.1"), "Prometheus 泄露完整 IP");

        String packetTypeQuery = expectedPacketType == null ? "" : "&packetType=" + expectedPacketType;
        String web = get("http://127.0.0.1:" + webPort + "/network-forensics?sinceMs=" + (now - 120_000L) + "&untilMs=" + now + "&limit=100" + packetTypeQuery);
        require(web.contains("127.0.0.1"), "Web 未返回完整 IP");
        require(web.contains("serverprobe:test"), "Web 未返回 Plugin Message 频道");
        require(web.contains("payloadBase64") || web.contains("载荷"), "Web 未返回白名单载荷字段");
    }

    private static String get(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestProperty("Authorization", "Bearer " + TOKEN);
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        try {
            require(connection.getResponseCode() == 200, "HTTP 查询失败：" + connection.getResponseCode());
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            try (InputStream input = connection.getInputStream()) {
                for (int count; (count = input.read(chunk)) >= 0; ) buffer.write(chunk, 0, count);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static void writeResult(File result, boolean passed, String message) {
        try {
            File parent = result.getParentFile();
            if (parent != null) parent.mkdirs();
            Properties properties = new Properties();
            // status=PASS 是 mc-testkit 官方 ResultReader 的契约键；passed 兼容历史结果文件格式。
            properties.setProperty("status", passed ? "PASS" : "FAIL");
            properties.setProperty("passed", Boolean.toString(passed));
            properties.setProperty("message", message == null ? "未知错误" : message);
            File temporary = new File(result.getPath() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary.toPath())) {
                properties.store(output, "FR11 网络取证真实验收结果");
            }
            Files.move(temporary.toPath(), result.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            // 结果文件无法落盘时，由 mc-testkit 的超时机制判定失败。
        }
    }

    private static void require(boolean expression, String message) {
        if (!expression) throw new IllegalStateException(message);
    }
}
