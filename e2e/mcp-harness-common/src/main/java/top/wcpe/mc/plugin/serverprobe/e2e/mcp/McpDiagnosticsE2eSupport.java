package top.wcpe.mc.plugin.serverprobe.e2e.mcp;

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
import java.util.Properties;

/** 四个平台共用的 FR14 MCP HTTP JSON-RPC 验收客户端。 */
public final class McpDiagnosticsE2eSupport {

    private static final String SCENARIO_ENV = "MC_TESTKIT_E2E_SCENARIO";
    private static final String RESULT_ENV = "MC_TESTKIT_E2E_RESULT_FILE";
    private static final String PORT_ENV = "SERVERPROBE_MCP_PORT";
    private static final int RETRIES = 30;

    private McpDiagnosticsE2eSupport() {
    }

    /** 在插件加载后等待 MCP 启动，并验证协议握手、工具清单、原生状态与平台命令。 */
    public static void verifyPlatform(String scenario, String command) {
        if (!scenario.equals(System.getenv(SCENARIO_ENV))) return;
        String resultPath = System.getenv(RESULT_ENV);
        if (resultPath == null || resultPath.trim().isEmpty()) return;
        try {
            verifyEventually(command);
            writeResult(new File(resultPath), true, "FR14 MCP 平台命令与原生状态验收通过");
        } catch (Exception exception) {
            writeResult(new File(resultPath), false, failureMessage(exception));
        }
    }

    /** 供 Paper Java21 harness 在通用验证之后追加结构化 Arthas 验收。 */
    public static void verifyPlatformAndModernArthas(String scenario, String command, ModernArthasProbe probe) {
        if (!scenario.equals(System.getenv(SCENARIO_ENV))) return;
        String resultPath = System.getenv(RESULT_ENV);
        if (resultPath == null || resultPath.trim().isEmpty()) return;
        try {
            verifyEventually(command);
            probe.verify(new Client(port()));
            writeResult(new File(resultPath), true, "FR14 MCP、平台命令与 Arthas 4.3.2 验收通过");
        } catch (Exception exception) {
            writeResult(new File(resultPath), false, failureMessage(exception));
        }
    }

    /** Java8 场景只接受已提取 3.1.1 运行时后的真实类搜索命令。 */
    public static void verifyJava8ClassSearch(String scenario, File pluginDirectory, String classPattern) {
        if (!scenario.equals(System.getenv(SCENARIO_ENV))) return;
        String resultPath = System.getenv(RESULT_ENV);
        if (resultPath == null || resultPath.trim().isEmpty()) return;
        try {
            verifyEventually("list");
            File core = new File(pluginDirectory, "mcp-workspace/arthas/3.1.1/arthas-core.jar");
            require(core.isFile(), "Java8 未提取 Arthas 3.1.1 runtime");
            Client client = new Client(port());
            String taskId = client.task("arthas_execute", "{\"command\":\"sc " + json(classPattern) + "\",\"timeoutMillis\":5000}");
            String output = client.awaitSuccess(taskId, "sc");
            require(output.contains(classPattern.replace("*", "")), "Arthas 3.1.1 类搜索未返回目标类");
            writeResult(new File(resultPath), true, "FR14 Java8 Arthas 3.1.1 类搜索验收通过");
        } catch (Exception exception) {
            writeResult(new File(resultPath), false, failureMessage(exception));
        }
    }

    private static void verifyEventually(String command) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            try {
                verify(new Client(port()), command);
                return;
            } catch (Exception exception) {
                last = exception;
                Thread.sleep(1_000L);
            }
        }
        throw new IllegalStateException("等待 FR14 MCP 就绪超时：" + failureMessage(last), last);
    }

    private static void verify(Client client, String command) throws Exception {
        require(client.request("initialize", "{}").contains("protocolVersion"), "initialize 未返回协议版本");
        String listed = client.request("tools/list", "{}");
        require(listed.contains("server_status") && listed.contains("server_command"), "tools/list 缺少状态或平台命令工具");
        require(listed.contains("thread_top") && listed.contains("thread_dump") && listed.contains("thread_deadlocks"), "tools/list 缺少原生线程工具");
        require(client.call("server_status", "{}").contains("result"), "server_status 未返回结果");
        require(client.call("thread_top", "{}").contains("result"), "thread_top 未返回结果");
        require(client.call("thread_dump", "{}").contains("result"), "thread_dump 未返回结果");
        require(client.call("thread_deadlocks", "{}").contains("result"), "thread_deadlocks 未返回结果");
        require(client.call("diagnostic_bundle", "{}").contains("serverStatus"), "diagnostic_bundle 未汇总服务器状态");
        String response = client.call("server_command", "{\"command\":\"" + json(command) + "\"}");
        require(response.contains("success"), "server_command 未返回执行结果");
    }

    private static int port() {
        String value = System.getenv(PORT_ENV);
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            throw new IllegalStateException("缺少有效的 " + PORT_ENV);
        }
    }

    private static String failureMessage(Exception exception) {
        String message = exception == null ? "未知错误" : exception.getMessage();
        return exception == null ? message : exception.getClass().getSimpleName() + "：" + (message == null ? "未知错误" : message);
    }

    private static void writeResult(File result, boolean passed, String message) {
        try {
            File parent = result.getParentFile();
            if (parent != null) parent.mkdirs();
            Properties properties = new Properties();
            properties.setProperty("status", passed ? "PASS" : "FAIL");
            properties.setProperty("message", message);
            File temporary = new File(result.getPath() + ".tmp");
            OutputStream stream = Files.newOutputStream(temporary.toPath());
            try {
                properties.store(stream, "FR14 MCP 真实验收结果");
            } finally {
                stream.close();
            }
            Files.move(temporary.toPath(), result.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new IllegalStateException("无法写入 FR14 验收结果文件", exception);
        }
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    /** 现代运行时的结构化命令断言由 Bukkit harness 提供其本地目标方法。 */
    public interface ModernArthasProbe {
        void verify(Client client) throws Exception;
    }

    /** Java8 兼容的最小 JSON-RPC client；不依赖测试库或上游 MCP SDK。 */
    public static final class Client {
        private final int port;

        public Client(int port) {
            this.port = port;
        }

        public String request(String method, String params) throws IOException {
            return post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}");
        }

        public String call(String name, String arguments) throws IOException {
            return request("tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}");
        }

        public String task(String name, String arguments) throws Exception {
            String response = call(name, arguments);
            String plain = response.replace("\\", "");
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"taskId\"\\s*:\\s*\"([^\"]+)\"").matcher(plain);
            if (!matcher.find()) throw new IllegalStateException(name + " 未返回 taskId：" + limit(response));
            return matcher.group(1);
        }

        public String awaitSuccess(String taskId, String operation) throws Exception {
            String status = "";
            for (int attempt = 0; attempt < 50; attempt++) {
                Thread.sleep(100L);
                status = call("arthas_task_status", "{\"taskId\":\"" + json(taskId) + "\"}");
                if (status.contains("SUCCEEDED")) return call("arthas_task_output", "{\"taskId\":\"" + json(taskId) + "\"}");
                if (status.contains("FAILED") || status.contains("CANCELLED") || status.contains("TIMED_OUT")) {
                    throw new IllegalStateException("Arthas " + operation + " 执行失败：" + limit(status));
                }
            }
            call("arthas_task_cancel", "{\"taskId\":\"" + json(taskId) + "\"}");
            throw new IllegalStateException("Arthas " + operation + " 未在限定时间完成：" + limit(status));
        }

        private String post(String body) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/mcp").openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(2_000);
            connection.setReadTimeout(5_000);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(bytes);
            } finally {
                output.close();
            }
            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = read(input);
            connection.disconnect();
            if (code != 200) throw new IOException("MCP HTTP 状态=" + code + "，响应=" + limit(response));
            return response;
        }

        private static String read(InputStream input) throws IOException {
            if (input == null) return "";
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int count; (count = input.read(buffer)) >= 0; ) output.write(buffer, 0, count);
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                input.close();
            }
        }
    }

    private static String limit(String value) {
        return value.length() <= 512 ? value : value.substring(0, 512);
    }
}
