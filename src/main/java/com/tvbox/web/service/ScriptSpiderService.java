package com.tvbox.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tvbox.web.model.SiteDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class ScriptSpiderService implements InitializingBean {

    private final AppPathsService appPathsService;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${app.node-command:}")
    private String nodeCommand;

    @Value("${app.python-command:}")
    private String pythonCommand;

    @Value("${server.port:18080}")
    private String serverPort;

    @Value("${app.script-timeout-seconds:90}")
    private long scriptTimeoutSeconds;

    @Value("${app.script-max-concurrency:2}")
    private int scriptMaxConcurrency;

    @Value("${app.script-admission-timeout-seconds:3}")
    private long scriptAdmissionTimeoutSeconds;

    private volatile String resolvedNodeCommand;
    private volatile String resolvedPythonCommand;
    private Semaphore scriptSlots;

    public ScriptSpiderService(AppPathsService appPathsService,
                               ConfigService configService,
                               ObjectMapper objectMapper) {
        this.appPathsService = appPathsService;
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public void afterPropertiesSet() {
        int defaultConcurrency = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors()));
        int concurrency = Math.max(1, scriptMaxConcurrency > 0 ? scriptMaxConcurrency : defaultConcurrency);
        this.scriptSlots = new Semaphore(concurrency, true);
    }

    public boolean supports(SiteDefinition site) {
        if (site == null || !StringUtils.hasText(site.getApi())) {
            return false;
        }
        String api = site.getApi().toLowerCase();
        return api.endsWith(".js") || api.contains(".js?") || api.endsWith(".py") || api.contains(".py?");
    }

    public JsonNode home(SiteDefinition site, boolean filter) {
        JsonNode home = execute(site, "home", Map.of("filter", filter));
        if (hasVideoList(home)) {
            return home;
        }
        JsonNode homeVod = execute(site, "homeVod", Map.of());
        if (!hasVideoList(homeVod)) {
            return home;
        }

        ObjectNode merged = home != null && home.isObject()
                ? ((ObjectNode) home).deepCopy()
                : objectMapper.createObjectNode();
        if (homeVod.isObject() && homeVod.has("list")) {
            merged.set("list", homeVod.get("list"));
        } else if (homeVod.isArray()) {
            merged.set("list", homeVod);
        }
        return merged;
    }

    public JsonNode category(SiteDefinition site, String tid, String pg, boolean filter, Map<String, String> extend) {
        return execute(site, "category", Map.of(
                "tid", tid == null ? "" : tid,
                "pg", pg == null ? "1" : pg,
                "filter", filter,
                "extend", extend == null ? Map.of() : extend
        ));
    }

    public JsonNode detail(SiteDefinition site, String id) {
        return execute(site, "detail", Map.of("id", id == null ? "" : id));
    }

    public JsonNode search(SiteDefinition site, String wd, boolean quick) {
        return execute(site, "search", Map.of(
                "wd", wd == null ? "" : wd,
                "quick", quick
        ));
    }

    public JsonNode play(SiteDefinition site, String flag, String id, List<String> vipFlags) {
        return execute(site, "play", Map.of(
                "flag", flag == null ? "" : flag,
                "id", id == null ? "" : id,
                "vipFlags", vipFlags == null ? List.of() : vipFlags
        ));
    }

    private JsonNode execute(SiteDefinition site, String action, Map<String, Object> args) {
        String api = site.getApi();
        String lowerApi = api.toLowerCase();
        if (lowerApi.endsWith(".py") || lowerApi.contains(".py?")) {
            return executePython(site, action, args);
        }
        return executeNode(site, action, args);
    }

    private JsonNode executeNode(SiteDefinition site, String action, Map<String, Object> args) {
        try {
            Path runtimePath = prepareJsRuntime(site.getApi());
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("action", action);
            payload.put("runtimePath", runtimePath.toAbsolutePath().toString());
            payload.put("runtimeUrl", site.getApi());
            payload.put("ext", site.getExtText());
            payload.put("siteKey", site.getKey() == null ? "" : site.getKey());
            payload.put("proxyUrl", proxyUrl());
            payload.put("storageDir", configService.getCacheDir().resolve("js-local").toAbsolutePath().toString());
            payload.set("args", objectMapper.valueToTree(args));
            String output = runProcess(List.of(
                    resolveNodeCommand(),
                    resolveProjectScript("js_spider_runner.mjs").toAbsolutePath().toString()
            ), payload);
            return readJson(output);
        } catch (Exception ex) {
            throw new IllegalStateException("JS Spider failed: action=" + action
                    + ", api=" + site.getApi()
                    + ", " + safeMessage(ex), ex);
        }
    }

    private JsonNode executePython(SiteDefinition site, String action, Map<String, Object> args) {
        try {
            Path scriptPath = preparePythonScript(site.getApi());
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("action", action);
            payload.put("scriptPath", scriptPath.toAbsolutePath().toString());
            payload.put("ext", site.getExtText());
            payload.put("siteKey", site.getKey() == null ? "" : site.getKey());
            payload.put("proxyUrl", proxyUrl());
            payload.set("args", objectMapper.valueToTree(args));
            String output = runProcess(List.of(
                    resolvePythonCommand(),
                    resolveProjectScript("py_spider_runner.py").toAbsolutePath().toString()
            ), payload);
            return readJson(output);
        } catch (Exception ex) {
            throw new IllegalStateException("PY Spider failed: action=" + action
                    + ", api=" + site.getApi()
                    + ", " + safeMessage(ex), ex);
        }
    }

    private Path prepareJsRuntime(String runtimeUrl) throws IOException, InterruptedException {
        Path runtimeDir = configService.getCacheDir().resolve("js-runtime").resolve(md5(parentOf(runtimeUrl)));
        Files.createDirectories(runtimeDir);
        Files.writeString(runtimeDir.resolve("package.json"), "{\n  \"type\": \"module\"\n}\n", StandardCharsets.UTF_8);

        Path runtimePath = runtimeDir.resolve(fileNameOf(runtimeUrl));
        cacheRemoteText(runtimeUrl, runtimePath);

        URI runtimeUri = URI.create(runtimeUrl);
        URI coreUri = runtimeUri.resolve("./drpy-core-lite.min.js");
        Path corePath = runtimeDir.resolve("drpy-core-lite.min.js");
        try {
            cacheRemoteText(coreUri.toString(), corePath);
        } catch (Exception ignore) {
            // Some older drpy runtimes do not ship drpy-core-lite. The JS runner
            // now falls back to import-graph localization instead of requiring it.
        }
        return runtimePath;
    }

    private Path preparePythonScript(String scriptUrl) throws IOException, InterruptedException {
        Path scriptDir = configService.getCacheDir().resolve("py-runtime");
        Files.createDirectories(scriptDir);
        Path scriptPath = scriptDir.resolve(md5(scriptUrl) + ".py");
        cacheRemoteText(scriptUrl, scriptPath);
        return scriptPath;
    }

    private void cacheRemoteText(String url, Path target) throws IOException, InterruptedException {
        if (Files.exists(target) && Files.size(target) > 0) {
            return;
        }
        Files.createDirectories(target.getParent());
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "okhttp/3.15")
                .header("Accept", "*/*")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("script download failed, status=" + response.statusCode() + ", url=" + url);
        }
        Files.write(target, response.body());
    }

    private String runProcess(List<String> command, JsonNode payload) throws IOException, InterruptedException {
        Semaphore slots = scriptSlots;
        if (slots == null) {
            throw new IllegalStateException("script slots not initialized");
        }
        boolean acquired;
        try {
            acquired = slots.tryAcquire(Math.max(1L, scriptAdmissionTimeoutSeconds), TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        }
        if (!acquired) {
            throw new IllegalStateException("script executor busy");
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(appPathsService.getBaseDir().toFile());
        processBuilder.environment().put("PYTHONUTF8", "1");
        processBuilder.environment().put("PYTHONIOENCODING", "UTF-8");
        processBuilder.environment().put("TVBOX_APP_BASE", appPathsService.getBaseDir().toString());
        try {
            Process process = processBuilder.start();
            ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
            Thread stdoutThread = pumpStream(process.getInputStream(), stdoutBuffer);
            Thread stderrThread = pumpStream(process.getErrorStream(), stderrBuffer);

            try {
                try (OutputStream outputStream = process.getOutputStream()) {
                    outputStream.write(objectMapper.writeValueAsBytes(payload));
                }

                boolean finished = process.waitFor(scriptTimeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    destroyProcessTree(process);
                    process.waitFor(5, TimeUnit.SECONDS);
                    throw new IllegalStateException("script timeout");
                }

                stdoutThread.join(3000);
                stderrThread.join(3000);
                String stdout = stdoutBuffer.toString(StandardCharsets.UTF_8).trim();
                String stderr = stderrBuffer.toString(StandardCharsets.UTF_8).trim();

                if (process.exitValue() != 0) {
                    String detail = firstNonBlank(stderr, stdout);
                    if (!StringUtils.hasText(detail)) {
                        detail = "exitCode=" + process.exitValue();
                    } else {
                        detail = "exitCode=" + process.exitValue() + ", " + detail;
                    }
                    throw new IllegalStateException(detail);
                }
                if (!StringUtils.hasText(stdout)) {
                    String detail = "exitCode=" + process.exitValue() + ", stdout empty";
                    if (StringUtils.hasText(stderr)) {
                        detail += ", stderr=" + stderr;
                    }
                    throw new IllegalStateException(detail);
                }
                return stdout;
            } finally {
                if (process.isAlive()) {
                    destroyProcessTree(process);
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
        } finally {
            slots.release();
        }
    }

    private boolean hasVideoList(JsonNode node) {
        if (node == null) {
            return false;
        }
        if (node.isArray()) {
            return !node.isEmpty();
        }
        return node.has("list") && node.get("list").isArray() && !node.get("list").isEmpty();
    }

    private Thread pumpStream(InputStream inputStream, ByteArrayOutputStream outputStream) {
        Thread thread = new Thread(() -> {
            try (InputStream in = inputStream) {
                in.transferTo(outputStream);
            } catch (IOException ignored) {
            }
        }, "script-spider-stream");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private JsonNode readJson(String json) {
        try {
            if (!StringUtils.hasText(json)) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("raw", json == null ? "" : json);
            node.put("error", "response is not valid JSON");
            return node;
        }
    }

    private Path resolveProjectScript(String filename) {
        Path scriptPath = appPathsService.resolveExisting(Path.of("scripts", filename).toString());
        if (!Files.exists(scriptPath)) {
            throw new IllegalStateException("script runner missing: " + scriptPath.toAbsolutePath()
                    + " (baseDir=" + appPathsService.getBaseDir() + ")");
        }
        return scriptPath;
    }

    private String resolveNodeCommand() {
        String current = resolvedNodeCommand;
        if (StringUtils.hasText(current)) {
            return current;
        }
        String resolved = resolveExecutable(nodeCommand, List.of("node", "nodejs"), "Node.js");
        resolvedNodeCommand = resolved;
        return resolved;
    }

    private String resolvePythonCommand() {
        String current = resolvedPythonCommand;
        if (StringUtils.hasText(current)) {
            return current;
        }
        List<String> fallbacks = isWindows()
                ? List.of("python", "python3")
                : List.of("python3", "python");
        String resolved = resolveExecutable(pythonCommand, fallbacks, "Python");
        resolvedPythonCommand = resolved;
        return resolved;
    }

    private String resolveExecutable(String configured, List<String> fallbacks, String label) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (StringUtils.hasText(configured)) {
            candidates.add(configured.trim());
        }
        candidates.addAll(fallbacks);
        for (String candidate : candidates) {
            if (isUsableCommand(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no usable " + label + " executable found, tried: " + String.join(", ", candidates));
    }

    private boolean isUsableCommand(String command) {
        if (!StringUtils.hasText(command)) {
            return false;
        }
        String trimmed = command.trim();
        if (looksLikePath(trimmed)) {
            Path path = Path.of(trimmed);
            if (!path.isAbsolute()) {
                path = appPathsService.resolveExisting(trimmed);
            }
            return Files.exists(path);
        }
        return isCommandOnPath(trimmed);
    }

    private boolean isCommandOnPath(String command) {
        List<String> checkCommand = isWindows()
                ? List.of("cmd", "/c", "where " + command)
                : List.of("sh", "-c", "command -v " + shellQuote(command));
        ProcessBuilder processBuilder = new ProcessBuilder(checkCommand);
        processBuilder.directory(appPathsService.getBaseDir().toFile());
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            try (InputStream in = process.getInputStream()) {
                in.readAllBytes();
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ignore) {
            return false;
        }
    }

    private boolean isWindows() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        return osName.contains("win");
    }

    private boolean looksLikePath(String command) {
        return command.contains("/")
                || command.contains("\\")
                || command.startsWith(".")
                || command.endsWith(".exe")
                || command.endsWith(".cmd")
                || command.endsWith(".bat")
                || command.endsWith(".sh");
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private String fileNameOf(String url) {
        String path = URI.create(url).getPath();
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private String parentOf(String url) {
        String path = URI.create(url).toString();
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(0, idx + 1) : path;
    }

    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : digest) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ex) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private String proxyUrl() {
        return "http://127.0.0.1:" + serverPort + "/proxy";
    }

    private void destroyProcessTree(Process process) {
        if (process == null) {
            return;
        }
        try {
            process.toHandle().descendants().forEach(handle -> {
                try {
                    handle.destroyForcibly();
                } catch (Exception ignore) {
                }
            });
        } catch (Exception ignore) {
        }
        try {
            process.destroyForcibly();
        } catch (Exception ignore) {
        }
    }

    private String safeMessage(Exception ex) {
        if (ex == null) {
            return "unknown";
        }
        if (StringUtils.hasText(ex.getMessage())) {
            return ex.getMessage();
        }
        Throwable cause = ex.getCause();
        if (cause != null && StringUtils.hasText(cause.getMessage())) {
            return cause.getClass().getName() + ": " + cause.getMessage();
        }
        return ex.getClass().getName();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }
}
