package com.example.diagnostic;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@SpringBootApplication
public class DiagnosticTool implements CommandLineRunner, ApplicationContextAware {

    private static ApplicationContext context;
    private static Environment env;
    private static final List<DiagnosticResult> lastResults = new ArrayList<>();

    public static void main(String[] args) {
        SpringApplication.run(DiagnosticTool.class, args);
    }

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        context = ctx;
    }

    @Autowired
    public void setEnv(Environment environment) {
        env = environment;
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println();
        System.out.println("========================================");
        System.out.println("  Java 环境诊断工具 v1.0.0 (Windows版)");
        System.out.println("========================================");
        System.out.println();

        List<DiagnosticResult> results = new ArrayList<>();

        // 按顺序构建检测项
        Map<String, DiagnosticCheck> checks = new LinkedHashMap<>();
        checks.put("JDK 版本", this::checkJDK);
        checks.put("Maven 安装", this::checkMaven);
        checks.put("Spring Boot 运行时", this::checkSpringBoot);
        checks.put("嵌入式 Tomcat", this::checkTomcat);
        checks.put("HTTP 端点", this::checkHttpEndpoint);
        checks.put("MySQL 连接", this::checkMySQL);

        int index = 1;
        int total = checks.size();
        for (Map.Entry<String, DiagnosticCheck> entry : checks.entrySet()) {
            String name = entry.getKey();
            System.out.printf("[%d/%d] %s", index++, total, padRight(name, 28));
            System.out.flush();

            DiagnosticResult result;
            try {
                result = entry.getValue().run();
            } catch (Exception e) {
                result = new DiagnosticResult(name, false, "异常: " + e.getMessage(), 0);
            }
            results.add(result);
            lastResults.clear();
            lastResults.addAll(results);

            // 输出结果
            if (result.passed()) {
                System.out.println(" \033[32m通过\033[0m (" + result.durationMs() + "ms)");
            } else {
                System.out.println(" \033[31m失败\033[0m (" + result.durationMs() + "ms)");
            }
            if (result.detail() != null && !result.detail().isEmpty()) {
                System.out.println("       " + result.detail().replace("\n", "\n       "));
            }
            System.out.println();
        }

        // 汇总
        long passedCount = results.stream().filter(DiagnosticResult::passed).count();
        long failedCount = results.stream().filter(r -> !r.passed()).count();

        System.out.println("========================================");
        System.out.printf("  结果: %d 通过, %d 失败%n", passedCount, failedCount);
        System.out.println("========================================");
        System.out.println();
        System.out.println("Web 端点已就绪: http://127.0.0.1:" + getPort() + "/diagnostic");
        System.out.println("按 Ctrl+C 退出");
        System.out.println();
    }

    // ==================== 6 项检测 ====================

    private DiagnosticResult checkJDK() {
        long start = System.currentTimeMillis();
        String version = System.getProperty("java.version");
        String vendor = System.getProperty("java.vendor");
        String home = System.getProperty("java.home");
        String arch = System.getProperty("os.arch");
        String osName = System.getProperty("os.name");

        String detail = String.format("Java %s (%s)%n       Home: %s%n       系统: %s (%s)",
                version, vendor, home, osName, arch);
        long duration = System.currentTimeMillis() - start;
        return new DiagnosticResult("JDK 版本", true, detail, duration);
    }

    private DiagnosticResult checkMaven() {
        long start = System.currentTimeMillis();
        try {
            // Windows 需要用 cmd /c 来执行
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "mvn --version");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "GBK"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }

            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            long duration = System.currentTimeMillis() - start;

            if (!finished) {
                process.destroyForcibly();
                return new DiagnosticResult("Maven 安装", false, "超时(15秒) - Maven 命令无响应", duration);
            }

            String out = output.toString();
            if (process.exitValue() == 0 && out.contains("Apache Maven")) {
                // 取第一行，例如 "Apache Maven 3.9.6 (...)"
                String firstLine = out.lines().findFirst().orElse(out).trim();
                return new DiagnosticResult("Maven 安装", true, firstLine, duration);
            } else {
                return new DiagnosticResult("Maven 安装", false,
                        out.isEmpty() ? "Maven 未安装或不在 PATH 中" : out.trim(), duration);
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return new DiagnosticResult("Maven 安装", false,
                    "未找到 mvn 命令，请确认 Maven 已安装并加入 PATH 环境变量", duration);
        }
    }

    private DiagnosticResult checkSpringBoot() {
        long start = System.currentTimeMillis();
        String version = SpringBootVersion.getVersion();
        boolean running = context != null && context.isRunning();

        String profiles = env != null
                ? String.join(", ", env.getActiveProfiles().length > 0
                    ? env.getActiveProfiles()
                    : new String[]{"default"})
                : "unknown";

        String detail = String.format("Spring Boot %s, Profile: %s, 上下文: %s",
                version, profiles, running ? "运行中" : "未运行");
        long duration = System.currentTimeMillis() - start;
        return new DiagnosticResult("Spring Boot 运行时", running && version != null, detail, duration);
    }

    private DiagnosticResult checkTomcat() {
        long start = System.currentTimeMillis();
        String port = getPort();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(port)), 3000);
            long duration = System.currentTimeMillis() - start;
            return new DiagnosticResult("嵌入式 Tomcat", true,
                    "Tomcat 正在监听 127.0.0.1:" + port, duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return new DiagnosticResult("嵌入式 Tomcat", false,
                    "端口 " + port + " 连接失败: " + e.getMessage(), duration);
        }
    }

    private DiagnosticResult checkHttpEndpoint() {
        long start = System.currentTimeMillis();
        String port = getPort();
        String url = "http://127.0.0.1:" + port + "/diagnostic";

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");

            int status = conn.getResponseCode();
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            conn.disconnect();

            long duration = System.currentTimeMillis() - start;
            boolean ok = status == 200 && body.toString().contains("{");
            return new DiagnosticResult("HTTP 端点", ok,
                    "GET /diagnostic -> " + status + " " +
                    (ok ? "OK (JSON 响应正常)" : "失败 (非JSON响应)"), duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            return new DiagnosticResult("HTTP 端点", false,
                    "请求失败: " + e.getMessage(), duration);
        }
    }

    private DiagnosticResult checkMySQL() {
        long start = System.currentTimeMillis();
        // 从命令行参数或环境变量读取 MySQL 配置
        String host = getArgOrEnv("mysql.host", "MYSQL_HOST", "127.0.0.1");
        String port = getArgOrEnv("mysql.port", "MYSQL_PORT", "3306");
        String database = getArgOrEnv("mysql.database", "MYSQL_DATABASE", "mysql");
        String user = getArgOrEnv("mysql.user", "MYSQL_USER", null);
        String password = getArgOrEnv("mysql.password", "MYSQL_PASSWORD", "");

        // 未配置用户名则跳过
        if (user == null || user.isEmpty()) {
            long duration = System.currentTimeMillis() - start;
            return new DiagnosticResult("MySQL 连接", false,
                    "跳过 - 未配置 MySQL 凭据。请通过参数配置：--mysql.user=root --mysql.password=xxx", duration);
        }

        String jdbcUrl = String.format(
                "jdbc:mysql://%s:%s/%s?connectTimeout=5000&socketTimeout=5000&useSSL=false&allowPublicKeyRetrieval=true",
                host, port, database);

        try {
            // 显式加载驱动
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            long duration = System.currentTimeMillis() - start;
            return new DiagnosticResult("MySQL 连接", false,
                    "MySQL JDBC 驱动未找到: " + e.getMessage(), duration);
        }

        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT VERSION()")) {

            String mysqlVersion = rs.next() ? rs.getString(1) : "unknown";
            long duration = System.currentTimeMillis() - start;
            return new DiagnosticResult("MySQL 连接", true,
                    String.format("连接成功! MySQL %s (%s@%s:%s/%s)",
                            mysqlVersion, user, host, port, database), duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            String msg = e.getMessage();
            // 简化常见错误信息
            if (msg.contains("Access denied")) {
                msg = "访问被拒绝 - 用户名或密码错误";
            } else if (msg.contains("CommunicationsException") || msg.contains("Connection refused")) {
                msg = "连接被拒绝 - MySQL 服务未启动或端口不可达 (" + host + ":" + port + ")";
            } else if (msg.contains("Unknown database")) {
                msg = "数据库 '" + database + "' 不存在";
            }
            return new DiagnosticResult("MySQL 连接", false, msg, duration);
        }
    }

    // ==================== 工具方法 ====================

    private String getPort() {
        if (env != null) {
            String port = env.getProperty("local.server.port");
            if (port != null) return port;
            port = env.getProperty("server.port");
            if (port != null) return port;
        }
        return "8080";
    }

    private String getArgOrEnv(String argKey, String envKey, String defaultValue) {
        // 先从命令行参数读取
        if (env != null) {
            String value = env.getProperty(argKey);
            if (value != null && !value.isEmpty()) return value;
        }
        // 再从系统属性读取
        String sysProp = System.getProperty(argKey);
        if (sysProp != null && !sysProp.isEmpty()) return sysProp;
        // 最后从环境变量读取
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isEmpty()) return envVal;
        return defaultValue;
    }

    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }

    // ==================== 内部类型 ====================

    @FunctionalInterface
    interface DiagnosticCheck {
        DiagnosticResult run();
    }

    record DiagnosticResult(String name, boolean passed, String detail, long durationMs) {
    }

    // ==================== Web 端点 ====================

    @RestController
    class WebController {

        @GetMapping("/diagnostic")
        public Map<String, Object> getDiagnosticResults() {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            response.put("platform", "Windows");
            response.put("port", getPort());

            long passed = lastResults.stream().filter(DiagnosticResult::passed).count();
            long failed = lastResults.stream().filter(r -> !r.passed()).count();
            response.put("passedCount", passed);
            response.put("failedCount", failed);
            response.put("overallStatus", failed > 0 ? "FAIL" : "ALL_PASS");

            List<Map<String, Object>> list = new ArrayList<>();
            for (DiagnosticResult r : lastResults) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", r.name());
                item.put("passed", r.passed());
                item.put("detail", r.detail());
                item.put("durationMs", r.durationMs());
                list.add(item);
            }
            response.put("results", list);
            return response;
        }

        @GetMapping("/")
        public String home() {
            return "<html><body style='font-family:sans-serif;padding:40px;'>" +
                   "<h1>Java 环境诊断工具 (Windows版)</h1>" +
                   "<p>诊断端点: <a href='/diagnostic'>/diagnostic</a></p>" +
                   "</body></html>";
        }
    }
}
