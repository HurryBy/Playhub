package com.tvbox.web.service;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tvbox.web.model.ConfigPayload;
import com.tvbox.web.model.SiteDefinition;
import android.app.Activity;
import android.app.Application;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

@Service
public class JarSpiderService implements DisposableBean, InitializingBean {

    private static final AtomicInteger SPIDER_THREAD_COUNTER = new AtomicInteger();

    private static final Pattern PLAY_URL_PREFIX_PATTERN = Pattern.compile("^[^,，]{1,30}[,，](.+)$");
    private static final String RUNTIME_PATCH_VERSION = "compat-20260423g";
    private static final String DEX_PATCH_VERSION = "compat-20260421a";
    private static final String GLOBAL_SESSION_KEY = "__global__";

    private final ConfigService configService;
    private final ObjectMapper objectMapper;
    private final SpiderBridgeService spiderBridgeService;
    private final ScriptSpiderService scriptSpiderService;
    private final AppPathsService appPathsService;

    @Value("${app.dex2jar-command:}")
    private String dex2jarCommand;

    @Value("${server.port:18080}")
    private String serverPort;

    @Value("${app.spider-operation-timeout-seconds:25}")
    private long spiderOperationTimeoutSeconds;

    @Value("${app.spider-operation-max-concurrency:4}")
    private int spiderOperationMaxConcurrency;

    @Value("${app.spider-operation-queue-capacity:8}")
    private int spiderOperationQueueCapacity;

    private final ConcurrentHashMap<String, URLClassLoader> classLoaderMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> spiderMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Path> dexConvertedJarMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Path> runtimePreparedJarMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Method> proxyMethodMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> spiderRuntimeKeyMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> siteRuntimeKeyMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> recentProxyRuntimeKeyMap = new ConcurrentHashMap<>();
    private ExecutorService spiderOperationExecutor;
    private ThreadPoolExecutor spiderOperationThreadPool;
    private final Application androidApplication = new Application();
    private final Activity androidContext = new Activity();

    public JarSpiderService(ConfigService configService,
                            ObjectMapper objectMapper,
                            SpiderBridgeService spiderBridgeService,
                            ScriptSpiderService scriptSpiderService,
                            AppPathsService appPathsService) {
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.spiderBridgeService = spiderBridgeService;
        this.scriptSpiderService = scriptSpiderService;
        this.appPathsService = appPathsService;
    }

    @Override
    public void afterPropertiesSet() {
        int defaultConcurrency = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        int concurrency = Math.max(1, spiderOperationMaxConcurrency > 0 ? spiderOperationMaxConcurrency : defaultConcurrency);
        int queueCapacity = Math.max(1, spiderOperationQueueCapacity);
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueCapacity);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                30L,
                TimeUnit.SECONDS,
                queue,
                runnable -> {
                    Thread thread = new Thread(runnable, "tvbox-spider-op-" + SPIDER_THREAD_COUNTER.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(false);
        this.spiderOperationThreadPool = executor;
        this.spiderOperationExecutor = executor;
    }

    private String normalizeSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId.trim() : GLOBAL_SESSION_KEY;
    }

    private String siteRuntimeKey(String sessionId, String siteKey) {
        return normalizeSessionId(sessionId) + "|" + siteKey;
    }

    private void rememberRecentProxyRuntimeKey(String sessionId, String runtimeKey) {
        if (StringUtils.hasText(runtimeKey)) {
            recentProxyRuntimeKeyMap.put(normalizeSessionId(sessionId), runtimeKey);
        }
    }

    private String recentProxyRuntimeKey(String sessionId) {
        return recentProxyRuntimeKeyMap.getOrDefault(normalizeSessionId(sessionId), "");
    }

    public JsonNode home(String sessionId, SiteDefinition site, boolean filter) {
        if (scriptSpiderService.supports(site)) {
            return scriptSpiderService.home(site, filter);
        }
        return executeWithFallback(site,
                () -> executeSpiderOperation(sessionId, site, "home", spider -> loadHomeNode(spider, site, filter)),
                () -> spiderBridgeService.home(site, filter),
                true);
    }

    public JsonNode home(SiteDefinition site, boolean filter) {
        return home(null, site, filter);
    }

    public JsonNode homeLocal(SiteDefinition site, boolean filter) {
        if (scriptSpiderService.supports(site)) {
            return scriptSpiderService.home(site, filter);
        }
        return executeWithFallback(site,
                () -> executeSpiderOperation(null, site, "home", spider -> loadHomeNode(spider, site, filter)),
                () -> null,
                false);
    }

    private JsonNode loadHomeNode(Object spider, SiteDefinition site, boolean filter) {
        JsonNode home = readJson(invokeHomeContent(spider, filter));
        if (isHomeEmpty(home) && filter) {
            home = readJson(invokeHomeContent(spider, false));
        }
        ObjectNode merged = normalizeHomeObject(home);
        boolean needsVideoList = !merged.has("list") || !merged.get("list").isArray() || merged.get("list").isEmpty();
        if (isHomeEmpty(home) || needsVideoList) {
            JsonNode videoNode = readJson(invokeHomeVideoContent(spider));
            if (videoNode != null && videoNode.isObject() && videoNode.has("list") && videoNode.get("list").isArray()) {
                merged.set("list", videoNode.get("list"));
            } else if (videoNode != null && videoNode.isArray()) {
                merged.set("list", videoNode);
            }
            home = merged;
        }

        ObjectNode normalized = normalizeHomeObject(home);
        ensureHomeClassesFromSite(site, normalized);
        if (!normalized.has("list") || !normalized.get("list").isArray()) {
            normalized.set("list", JsonNodeFactory.instance.arrayNode());
        }
        return normalized;
    }

    private boolean isHomeEmpty(JsonNode node) {
        if (node == null) {
            return true;
        }
        if (!node.isObject()) {
            return node.isEmpty();
        }
        boolean hasList = node.has("list") && node.get("list").isArray() && node.get("list").size() > 0;
        boolean hasClass = node.has("class") && node.get("class").isArray() && node.get("class").size() > 0;
        return !hasList && !hasClass;
    }

    private ObjectNode normalizeHomeObject(JsonNode node) {
        if (node != null && node.isObject()) {
            return ((ObjectNode) node).deepCopy();
        }
        return JsonNodeFactory.instance.objectNode();
    }

    private void ensureHomeClassesFromSite(SiteDefinition site, ObjectNode home) {
        if (home.has("class") && home.get("class").isArray() && home.get("class").size() > 0) {
            return;
        }
        ArrayNode classes = JsonNodeFactory.instance.arrayNode();
        if (site.getCategories() != null) {
            for (String category : site.getCategories()) {
                if (!StringUtils.hasText(category)) {
                    continue;
                }
                ObjectNode row = JsonNodeFactory.instance.objectNode();
                row.put("type_id", category);
                row.put("type_name", category);
                classes.add(row);
            }
        }
        home.set("class", classes);
    }

    public JsonNode category(String sessionId, SiteDefinition site, String tid, String pg, boolean filter, Map<String, String> extend) {
        if (scriptSpiderService.supports(site)) {
            return scriptSpiderService.category(site, tid, pg, filter, extend);
        }
        return executeWithFallback(site,
                () -> executeSpiderOperation(sessionId, site, "category", spider -> loadCategoryNode(spider, tid, pg, filter, extend)),
                () -> spiderBridgeService.category(site, tid, pg, filter, extend),
                true);
    }

    public JsonNode category(SiteDefinition site, String tid, String pg, boolean filter, Map<String, String> extend) {
        return category(null, site, tid, pg, filter, extend);
    }

    public JsonNode categoryLocal(SiteDefinition site, String tid, String pg, boolean filter, Map<String, String> extend) {
        if (scriptSpiderService.supports(site)) {
            return scriptSpiderService.category(site, tid, pg, filter, extend);
        }
        return executeWithFallback(site,
                () -> executeSpiderOperation(null, site, "category", spider -> loadCategoryNode(spider, tid, pg, filter, extend)),
                () -> null,
                false);
    }

    public JsonNode detail(String sessionId, SiteDefinition site, String id) {
        if (scriptSpiderService.supports(site)) {
            return scriptSpiderService.detail(site, id);
        }
        return executeWithFallback(site,
                () -> executeSpiderOperation(sessionId, site, "detail", spider -> readJson(invokeDetailContent(spider, id))),
                () -> spiderBridgeService.detail(site, id),
                true);
    }

    public JsonNode detail(SiteDefinition site, String id) {
        return detail(null, site, id);
    }

    public JsonNode detailLocal(SiteDefinition site, String id) {
        if (scriptSpiderService.supports(site)) {
            return scriptSpiderService.detail(site, id);
        }
        return executeWithFallback(site,
                () -> executeSpiderOperation(null, site, "detail", spider -> readJson(invokeDetailContent(spider, id))),
                () -> null,
                false);
    }

    public JsonNode search(String sessionId, SiteDefinition site, String wd, boolean quick) {
        if (scriptSpiderService.supports(site)) {
            return scriptSpiderService.search(site, wd, quick);
        }
        return executeWithFallback(site,
                () -> executeSpiderOperation(sessionId, site, "search", spider -> readJson(invokeSearchContent(spider, wd, quick))),
                () -> spiderBridgeService.search(site, wd, quick),
                true);
    }

    public JsonNode search(SiteDefinition site, String wd, boolean quick) {
        return search(null, site, wd, quick);
    }

    public JsonNode searchLocal(SiteDefinition site, String wd, boolean quick) {
        if (scriptSpiderService.supports(site)) {
            return scriptSpiderService.search(site, wd, quick);
        }
        return executeWithFallback(site,
                () -> executeSpiderOperation(null, site, "search", spider -> readJson(invokeSearchContent(spider, wd, quick))),
                () -> null,
                false);
    }

    public JsonNode play(String sessionId, SiteDefinition site, String flag, String id, List<String> vipFlags) {
        String normalizedId = sanitizePlayableUrl(id);
        if (scriptSpiderService.supports(site)) {
            return scriptSpiderService.play(site, flag, normalizedId, vipFlags);
        }
        JsonNode node = executeWithFallback(site,
                () -> executeSpiderOperation(sessionId, site, "play", spider -> readJson(invokePlayerContent(spider, flag, normalizedId, vipFlags))),
                () -> spiderBridgeService.play(site, flag, normalizedId, vipFlags),
                true);
        if (node != null && node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (!obj.has("flag")) {
                obj.put("flag", flag);
            }
            if (!obj.has("key")) {
                obj.put("key", normalizedId);
            }
            if (obj.has("url") && obj.get("url").isTextual()) {
                obj.put("url", sanitizePlayableUrl(obj.get("url").asText()));
            }
        }
        return finalizePlayNode(site, node, flag, normalizedId);
    }

    public JsonNode play(SiteDefinition site, String flag, String id, List<String> vipFlags) {
        return play(null, site, flag, id, vipFlags);
    }

    public JsonNode playLocal(SiteDefinition site, String flag, String id, List<String> vipFlags) {
        String normalizedId = sanitizePlayableUrl(id);
        if (scriptSpiderService.supports(site)) {
            return scriptSpiderService.play(site, flag, normalizedId, vipFlags);
        }
        JsonNode node = executeWithFallback(site,
                () -> executeSpiderOperation(null, site, "play", spider -> readJson(invokePlayerContent(spider, flag, normalizedId, vipFlags))),
                () -> null,
                false);
        if (node != null && node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (!obj.has("flag")) {
                obj.put("flag", flag);
            }
            if (!obj.has("key")) {
                obj.put("key", normalizedId);
            }
            if (obj.has("url") && obj.get("url").isTextual()) {
                obj.put("url", sanitizePlayableUrl(obj.get("url").asText()));
            }
        }
        return finalizePlayNode(site, node, flag, normalizedId);
    }

    private JsonNode executeWithFallback(SiteDefinition site, Supplier<JsonNode> localExecutor, Supplier<JsonNode> bridgeExecutor, boolean allowBridgeFallback) {
        try {
            return localExecutor.get();
        } catch (IllegalStateException ex) {
            if (allowBridgeFallback && spiderBridgeService.enabled()) {
                return bridgeExecutor.get();
            }
            throw ex;
        }
    }

    private <T> T executeSpiderOperation(String sessionId, SiteDefinition site, String operationName, Function<Object, T> operation) {
        try {
            return runSpiderOperationWithTimeout(site, operationName, () -> operation.apply(getOrCreateSpider(sessionId, site, false)));
        } catch (Throwable ex) {
            if (!isJvmCompatibilityFailure(ex)) {
                throw new IllegalStateException("Spider 调用失败: " + ex.getMessage(), ex);
            }
            try {
                return runSpiderOperationWithTimeout(site, operationName, () -> operation.apply(getOrCreateSpider(sessionId, site, true)));
            } catch (Throwable retry) {
                if (retry instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Spider 调用失败: " + retry.getMessage(), retry);
            }
        }
    }

    private <T> T runSpiderOperationWithTimeout(SiteDefinition site, String operationName, Callable<T> callable) throws Exception {
        ExecutorService executor = spiderOperationExecutor;
        if (executor == null) {
            throw new IllegalStateException("Spider executor not initialized");
        }
        Future<T> future;
        try {
            future = executor.submit(callable);
        } catch (RejectedExecutionException ex) {
            throw new IllegalStateException("Spider executor busy: " + spiderExecutorStats(), ex);
        }
        try {
            long timeoutSeconds = Math.max(1L, spiderOperationTimeoutSeconds);
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            String siteLabel = firstNonBlank(site == null ? null : site.getName(),
                    site == null ? null : site.getKey(),
                    site == null ? null : site.getApi(),
                    "unknown");
            throw new IllegalStateException("Spider 调用超时(" + spiderOperationTimeoutSeconds + "s): " + siteLabel + " / " + operationName, ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Spider 调用失败: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Spider 调用被中断", ex);
        }
    }

    private Object getOrCreateSpider(String sessionId, SiteDefinition site) {
        return getOrCreateSpider(sessionId, site, false);
    }

    private Object getOrCreateSpider(String sessionId, SiteDefinition site, boolean forceJvmCompatible) {
        if (!StringUtils.hasText(site.getApi())) {
            throw new IllegalArgumentException("site.api 为空");
        }
        if (site.getApi().endsWith(".js") || site.getApi().contains(".js?")) {
            throw new IllegalStateException("当前站点为 JS Spider，请配置 app.spider-bridge-url 走桥接执行");
        }
        String spiderKey = buildSpiderCacheKey(sessionId, site, forceJvmCompatible);
        String knownRuntimeKey = spiderRuntimeKeyMap.get(spiderKey);
        if (StringUtils.hasText(knownRuntimeKey)) {
            bindSiteRuntimeKey(sessionId, site, knownRuntimeKey);
        }
        Object cached = spiderMap.get(spiderKey);
        if (cached != null) {
            return cached;
        }

        Path jarPath = resolveJar(sessionId, site);
        String className = "com.github.catvod.spider." + site.getApi().replace("csp_", "");
        Path loadPath = jarPath;
        if (isDexOnlyJar(jarPath)) {
            loadPath = ensureDexJarConverted(jarPath, className);
        }
        Path chosenPath = forceJvmCompatible ? ensureJvmCompatibleJar(loadPath) : loadPath;
        Object spider = instantiateSpider(chosenPath, className, site);
        String runtimeKey = chosenPath.toString();
        bindSiteRuntimeKey(sessionId, site, runtimeKey);
        spiderMap.put(spiderKey, spider);
        spiderRuntimeKeyMap.put(spiderKey, runtimeKey);
        return spider;
    }

    private String buildSpiderKey(String sessionId, SiteDefinition site) {
        return md5(String.join("|",
                normalizeSessionId(sessionId),
                configService.getConfigUrl(sessionId) == null ? "" : configService.getConfigUrl(sessionId),
                site.getUid() == null ? "" : site.getUid(),
                site.getKey() == null ? "" : site.getKey(),
                site.getApi() == null ? "" : site.getApi(),
                site.getJar() == null ? "" : site.getJar(),
                site.getExtText()));
    }

    private String buildSpiderCacheKey(String sessionId, SiteDefinition site, boolean forceJvmCompatible) {
        return buildSpiderKey(sessionId, site) + (forceJvmCompatible ? "|jvm" : "|orig");
    }

    private void bindSiteRuntimeKey(String sessionId, SiteDefinition site, String runtimeKey) {
        if (!StringUtils.hasText(runtimeKey) || site == null) {
            return;
        }
        rememberRecentProxyRuntimeKey(sessionId, runtimeKey);
        putSiteRuntimeKey(sessionId, site.getUid(), runtimeKey);
        putSiteRuntimeKey(sessionId, site.getKey(), runtimeKey);
        putSiteRuntimeKey(sessionId, site.getApi(), runtimeKey);
        putSiteRuntimeKey(sessionId, site.getName(), runtimeKey);
    }

    private void putSiteRuntimeKey(String sessionId, String key, String runtimeKey) {
        if (StringUtils.hasText(key)) {
            siteRuntimeKeyMap.put(siteRuntimeKey(sessionId, key), runtimeKey);
        }
    }

    private Path ensureDexJarConverted(Path jarPath, String requiredClassName) {
        return dexConvertedJarMap.computeIfAbsent(jarPath.toString(), key -> {
            try {
                Path dexDir = configService.getCacheDir().resolve("dex-jars");
                Files.createDirectories(dexDir);
                String token = md5(DEX_PATCH_VERSION + "|" + jarPath.toAbsolutePath() + "|" + Files.getLastModifiedTime(jarPath).toMillis());
                Path outputJar = dexDir.resolve(token + ".jar");
                if (Files.exists(outputJar)
                        && Files.size(outputJar) > 0
                        && jarContainsClass(outputJar, requiredClassName)) {
                    patchConvertedJarIfNeeded(outputJar);
                    return outputJar;
                }
                Files.deleteIfExists(outputJar);

                Path dexFile = extractPrimaryDex(jarPath);
                try {
                    runDex2JarCommand(dexFile, outputJar);
                } finally {
                    Files.deleteIfExists(dexFile);
                }

                if (!Files.exists(outputJar) || Files.size(outputJar) == 0) {
                    throw new IllegalStateException("dex 转换后 jar 为空");
                }
                if (!jarContainsClass(outputJar, requiredClassName)) {
                    Files.deleteIfExists(outputJar);
                    throw new IllegalStateException("dex 转换结果缺少目标类: " + requiredClassName);
                }
                patchConvertedJarIfNeeded(outputJar);
                return outputJar;
            } catch (Exception ex) {
                throw new IllegalStateException("Spider JAR 为 Android dex 格式，本地转换失败: " + ex.getMessage() + "。可配置 app.dex2jar-command 或 app.spider-bridge-url", ex);
            }
        });
    }

    private Path ensureJvmCompatibleJar(Path jarPath) {
        String cacheKey = buildRuntimeJarCacheKey(jarPath);
        return runtimePreparedJarMap.computeIfAbsent(cacheKey, key -> {
            try {
                Path runtimeDir = configService.getCacheDir().resolve("runtime-jars");
                Files.createDirectories(runtimeDir);
                Path outputJar = runtimeDir.resolve(md5(key) + ".jar");
                if (Files.exists(outputJar) && Files.size(outputJar) > 0) {
                    return outputJar;
                }
                rewriteJarForJvm(jarPath, outputJar);
                return outputJar;
            } catch (Exception ex) {
                throw new IllegalStateException("Spider JAR JVM 兼容处理失败: " + ex.getMessage(), ex);
            }
        });
    }

    private Object instantiateSpider(Path jarPath, String className, SiteDefinition site) {
        try {
            URLClassLoader loader = ensureClassLoader(jarPath);
            Class<?> spiderClass = loader.loadClass(className);
            Object spider = spiderClass.getDeclaredConstructor().newInstance();
            initSpiderIfPossible(spiderClass, spider, site);
            initSpiderApiIfPossible(spiderClass, spider);
            patchSpiderStateIfNeeded(spider, site);
            warmUpSpiderIfNeeded(spider, site);
            return spider;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Throwable ex) {
            Throwable root = unwrapRootCause(ex);
            String detail = root == null ? ex.getClass().getName() : root.getClass().getName();
            String message = root == null ? ex.getMessage() : root.getMessage();
            if (StringUtils.hasText(message)) {
                detail = detail + ": " + message;
            }
            throw new IllegalStateException("创建 Spider 失败: " + className + " -> " + detail, ex);
        }
    }

    private boolean isJvmCompatibilityFailure(Throwable ex) {
        Throwable root = unwrapRootCause(ex);
        while (root != null) {
            if (root instanceof VerifyError
                    || root instanceof ClassFormatError
                    || root instanceof UnsupportedClassVersionError) {
                return true;
            }
            String message = root.getMessage();
            if (StringUtils.hasText(message)) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("stackmap frame")
                        || lower.contains("illegal modifiers")
                        || lower.contains("bad type on operand stack")
                        || lower.contains("type on operand stack")
                        || lower.contains("inconsistent stackmap")
                        || lower.contains("expecting a stackmap frame")) {
                    return true;
                }
            }
            root = root.getCause();
        }
        return false;
    }

    private String buildRuntimeJarCacheKey(Path jarPath) {
        try {
            return RUNTIME_PATCH_VERSION
                    + "|"
                    + jarPath.toAbsolutePath()
                    + "|"
                    + Files.getLastModifiedTime(jarPath).toMillis()
                    + "|"
                    + Files.size(jarPath);
        } catch (IOException ex) {
            return RUNTIME_PATCH_VERSION + "|" + jarPath.toAbsolutePath();
        }
    }

    private void rewriteJarForJvm(Path sourceJar, Path outputJar) throws IOException {
        Path parent = outputJar.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tempJar = Files.createTempFile(parent, "runtime-", ".jar");
        try (JarFile jarFile = new JarFile(sourceJar.toFile());
             URLClassLoader resolveLoader = new URLClassLoader(new URL[]{sourceJar.toUri().toURL()}, Thread.currentThread().getContextClassLoader());
             BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(tempJar));
             JarOutputStream jarOut = new JarOutputStream(out)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || isSignatureEntry(entry.getName())) {
                    continue;
                }

                JarEntry targetEntry = new JarEntry(entry.getName());
                if (entry.getTime() > 0) {
                    targetEntry.setTime(entry.getTime());
                }
                jarOut.putNextEntry(targetEntry);
                try (InputStream in = jarFile.getInputStream(entry)) {
                    if (entry.getName().endsWith(".class")) {
                        jarOut.write(rewriteClassForJvm(in.readAllBytes(), resolveLoader));
                    } else {
                        in.transferTo(jarOut);
                    }
                }
                jarOut.closeEntry();
            }
        } catch (Throwable ex) {
            Files.deleteIfExists(tempJar);
            throw ex;
        }
        Files.move(tempJar, outputJar, StandardCopyOption.REPLACE_EXISTING);
    }

    private boolean isSignatureEntry(String entryName) {
        if (!StringUtils.hasText(entryName)) {
            return false;
        }
        String upper = entryName.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        String fileName = upper.substring("META-INF/".length());
        return fileName.endsWith(".SF")
                || fileName.endsWith(".RSA")
                || fileName.endsWith(".DSA")
                || fileName.startsWith("SIG-");
    }

    private byte[] rewriteClassForJvm(byte[] classBytes, ClassLoader resolveLoader) {
        Throwable lastFailure = null;
        int[] readerFlags = {ClassReader.SKIP_FRAMES, 0, ClassReader.EXPAND_FRAMES};
        boolean[] writerModes = {false, true};
        for (int readerFlag : readerFlags) {
            for (boolean copyReaderPool : writerModes) {
                try {
                    return rewriteClassForJvm(classBytes, resolveLoader, copyReaderPool, readerFlag);
                } catch (Throwable ex) {
                    lastFailure = ex;
                }
            }
        }
        if (lastFailure != null) {
            lastFailure.printStackTrace();
        }
        return classBytes;
    }

    private byte[] rewriteClassForJvm(byte[] classBytes, ClassLoader resolveLoader, boolean copyReaderPool, int readerFlag) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = copyReaderPool
                    ? new SafeFrameClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, resolveLoader)
                    : new SafeFrameClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, resolveLoader);
            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                private boolean interfaceClass;
                private String className;
                private String superClassName;
                private boolean hasNoArgConstructor;
                private final Set<String> constructorDescriptors = new HashSet<>();

                @Override
                public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                    className = name;
                    superClassName = superName;
                    interfaceClass = (access & Opcodes.ACC_INTERFACE) != 0;
                    super.visit(Math.max(version, Opcodes.V1_8), access, name, signature, superName, interfaces);
                }

                @Override
                public org.springframework.asm.MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if ("<init>".equals(name) && "()V".equals(descriptor)) {
                        hasNoArgConstructor = true;
                    }
                    if ("<init>".equals(name)) {
                        constructorDescriptors.add(descriptor);
                    }
                    if (interfaceClass) {
                        access &= ~Opcodes.ACC_BRIDGE;
                    }
                    if ("com/github/catvod/spider/merge/A0/zl".equals(className)
                            && "<init>".equals(name)
                            && "()V".equals(descriptor)) {
                        org.springframework.asm.MethodVisitor replacement = super.visitMethod(access, name, descriptor, signature, exceptions);
                        emitZlNoArgConstructor(replacement);
                        return null;
                    }
                    if ("com/github/catvod/spider/merge/A0/em".equals(className)
                            && "<init>".equals(name)
                            && "(Ljava/lang/String;Ljava/lang/String;)V".equals(descriptor)) {
                        org.springframework.asm.MethodVisitor replacement = super.visitMethod(access, name, descriptor, signature, exceptions);
                        emitEmStringConstructor(replacement);
                        return null;
                    }
                    org.springframework.asm.MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    String replacementInternalName = runtimeObjectReplacement(className, name, descriptor);
                    Type methodReturnType = Type.getReturnType(descriptor);
                    String methodReturnInternalName = methodReturnType.getSort() == Type.OBJECT ? methodReturnType.getInternalName() : null;
                    boolean constructorMethod = "<init>".equals(name);
                    boolean rewriteFirstOjTokenizer = "com/github/catvod/spider/merge/A0/oj".equals(className)
                            && "a".equals(name)
                            && "(Ljava/lang/String;)Lcom/github/catvod/spider/merge/A0/em;".equals(descriptor);
                    boolean rewriteZlStateConstructor = "com/github/catvod/spider/merge/A0/zl".equals(className)
                            && "<init>".equals(name)
                            && "()V".equals(descriptor);
                    return new org.springframework.asm.MethodVisitor(Opcodes.ASM9, delegate) {
                        private final Deque<String> pendingNewTypes = new ArrayDeque<>();
                        private int wkNewCount;

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            if (opcode == Opcodes.NEW) {
                                String resolvedType = type;
                                if ("java/lang/Object".equals(type) && StringUtils.hasText(replacementInternalName)) {
                                    resolvedType = replacementInternalName;
                                } else if ("java/util/ArrayList".equals(type)
                                        && "com/github/catvod/spider/merge/A0/rp".equals(methodReturnInternalName)) {
                                    resolvedType = methodReturnInternalName;
                                } else if (rewriteFirstOjTokenizer && "com/github/catvod/spider/merge/A0/wk".equals(type)) {
                                    wkNewCount += 1;
                                    if (wkNewCount == 1) {
                                        resolvedType = "com/github/catvod/spider/merge/A0/w";
                                    } else if (wkNewCount == 2) {
                                        resolvedType = "com/github/catvod/spider/merge/A0/um";
                                    }
                                } else if (rewriteZlStateConstructor && "com/github/catvod/spider/merge/A0/wk".equals(type)) {
                                    resolvedType = "com/github/catvod/spider/merge/A0/um";
                                }
                                pendingNewTypes.push(resolvedType);
                                super.visitTypeInsn(opcode, resolvedType);
                                return;
                            }
                            super.visitTypeInsn(opcode, type);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor, boolean isInterfaceMethod) {
                            if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(methodName)) {
                                if (constructorMethod
                                        && pendingNewTypes.isEmpty()
                                        && "java/lang/Object".equals(owner)
                                        && "()V".equals(methodDescriptor)
                                        && StringUtils.hasText(superClassName)
                                        && !"java/lang/Object".equals(superClassName)) {
                                    super.visitMethodInsn(opcode, superClassName, methodName, methodDescriptor, false);
                                    return;
                                }
                                if (constructorMethod
                                        && pendingNewTypes.isEmpty()
                                        && "()V".equals(methodDescriptor)
                                        && StringUtils.hasText(superClassName)
                                        && !className.equals(owner)
                                        && !superClassName.equals(owner)) {
                                    super.visitMethodInsn(opcode, superClassName, methodName, methodDescriptor, false);
                                    return;
                                }
                                if (StringUtils.hasText(replacementInternalName)
                                        && "java/lang/Object".equals(owner)
                                        && "()V".equals(methodDescriptor)) {
                                    super.visitMethodInsn(opcode, replacementInternalName, methodName, methodDescriptor, false);
                                    if (!pendingNewTypes.isEmpty()) {
                                        pendingNewTypes.pop();
                                    }
                                    return;
                                }
                                if (!pendingNewTypes.isEmpty()) {
                                    String pendingType = pendingNewTypes.peek();
                                    if (StringUtils.hasText(pendingType)) {
                                        if (!pendingType.equals(owner)) {
                                            super.visitMethodInsn(opcode, pendingType, methodName, methodDescriptor, false);
                                            pendingNewTypes.pop();
                                            return;
                                        }
                                        pendingNewTypes.pop();
                                    }
                                }
                            }
                            if (opcode == Opcodes.INVOKEVIRTUAL
                                    && "java/lang/Exception".equals(owner)
                                    && "printStackTrace".equals(methodName)
                                    && "()V".equals(methodDescriptor)) {
                                super.visitMethodInsn(opcode, "java/lang/Throwable", methodName, methodDescriptor, false);
                                return;
                            }
                            super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterfaceMethod);
                        }
                    };
                }

                @Override
                public void visitEnd() {
                    if ("com/github/catvod/spider/merge/A0/ut".equals(className) && !hasNoArgConstructor) {
                        emitDelegatingConstructor(super.cv, Opcodes.ACC_PUBLIC, "()V", "java/lang/Object", "()V");
                    }
                    if ("com/github/catvod/spider/merge/A0/fw".equals(className) && !hasNoArgConstructor) {
                        emitDelegatingConstructor(super.cv, Opcodes.ACC_PUBLIC, "()V", "java/lang/Object", "()V");
                    }
                    if ("com/github/catvod/spider/merge/A0/cn".equals(className) && !hasNoArgConstructor) {
                        emitDelegatingConstructor(super.cv, Opcodes.ACC_PUBLIC, "()V", "java/lang/Object", "()V");
                    }
                    if ("com/github/catvod/spider/merge/A0/rp".equals(className)) {
                        if (!constructorDescriptors.contains("()V")) {
                            emitDelegatingConstructor(super.cv, Opcodes.ACC_PUBLIC, "()V", "java/util/ArrayList", "()V");
                        }
                        if (!constructorDescriptors.contains("(I)V")) {
                            emitDelegatingConstructor(super.cv, Opcodes.ACC_PUBLIC, "(I)V", "java/util/ArrayList", "(I)V");
                        }
                        if (!constructorDescriptors.contains("(Ljava/util/Collection;)V")) {
                            emitDelegatingConstructor(super.cv, Opcodes.ACC_PUBLIC, "(Ljava/util/Collection;)V", "java/util/ArrayList", "(Ljava/util/Collection;)V");
                        }
                    }
                    if ("com/github/catvod/spider/merge/A0/w".equals(className) && !constructorDescriptors.contains("(ILcom/github/catvod/spider/merge/A0/zl;)V")) {
                        emitDelegatingConstructor(super.cv,
                                Opcodes.ACC_PUBLIC,
                                "(ILcom/github/catvod/spider/merge/A0/zl;)V",
                                "com/github/catvod/spider/merge/A0/wk",
                                "(ILcom/github/catvod/spider/merge/A0/zl;)V");
                    }
                    if ("com/github/catvod/spider/merge/A0/um".equals(className) && !constructorDescriptors.contains("(ILcom/github/catvod/spider/merge/A0/zl;)V")) {
                        emitDelegatingConstructor(super.cv,
                                Opcodes.ACC_PUBLIC,
                                "(ILcom/github/catvod/spider/merge/A0/zl;)V",
                                "com/github/catvod/spider/merge/A0/wk",
                                "(ILcom/github/catvod/spider/merge/A0/zl;)V");
                    }
                    super.visitEnd();
                }
            };
            reader.accept(visitor, readerFlag);
            return writer.toByteArray();
        } catch (Throwable ex) {
            throw new IllegalStateException("ASM rewrite failed (copyReaderPool=" + copyReaderPool + ", readerFlag=" + readerFlag + "): " + ex.getMessage(), ex);
        }
    }

    private void emitDelegatingConstructor(ClassVisitor visitor,
                                           int access,
                                           String descriptor,
                                           String superOwner,
                                           String superDescriptor) {
        org.springframework.asm.MethodVisitor ctor = visitor.visitMethod(access, "<init>", descriptor, null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        int localSlot = 1;
        for (Type argumentType : Type.getArgumentTypes(superDescriptor)) {
            ctor.visitVarInsn(argumentType.getOpcode(Opcodes.ILOAD), localSlot);
            localSlot += argumentType.getSize();
        }
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, superOwner, "<init>", superDescriptor, false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(Math.max(1, localSlot), localSlot);
        ctor.visitEnd();
    }

    private void emitZlNoArgConstructor(org.springframework.asm.MethodVisitor ctor) {
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitTypeInsn(Opcodes.NEW, "com/github/catvod/spider/merge/A0/um");
        ctor.visitInsn(Opcodes.DUP);
        ctor.visitInsn(Opcodes.ICONST_3);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "com/github/catvod/spider/merge/A0/um",
                "<init>",
                "(ILcom/github/catvod/spider/merge/A0/zl;)V",
                false);
        ctor.visitFieldInsn(Opcodes.PUTFIELD,
                "com/github/catvod/spider/merge/A0/zl",
                "u",
                "Lcom/github/catvod/spider/merge/A0/um;");

        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitInsn(Opcodes.ICONST_1);
        ctor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");
        ctor.visitInsn(Opcodes.DUP);
        ctor.visitInsn(Opcodes.ICONST_0);
        ctor.visitInsn(Opcodes.ACONST_NULL);
        ctor.visitInsn(Opcodes.AASTORE);
        ctor.visitFieldInsn(Opcodes.PUTFIELD,
                "com/github/catvod/spider/merge/A0/zl",
                "ag",
                "[Ljava/lang/String;");

        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
    }

    private void emitEmStringConstructor(org.springframework.asm.MethodVisitor ctor) {
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitLdcInsn("#root");
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.GETSTATIC,
                "com/github/catvod/spider/merge/A0/vz",
                "a",
                "Lcom/github/catvod/spider/merge/A0/vz;");
        ctor.visitMethodInsn(Opcodes.INVOKESTATIC,
                "com/github/catvod/spider/merge/A0/ad",
                "m",
                "(Ljava/lang/String;Ljava/lang/String;Lcom/github/catvod/spider/merge/A0/vz;)Lcom/github/catvod/spider/merge/A0/ad;",
                false);
        ctor.visitVarInsn(Opcodes.ALOAD, 2);
        ctor.visitInsn(Opcodes.ACONST_NULL);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "com/github/catvod/spider/merge/A0/ed",
                "<init>",
                "(Lcom/github/catvod/spider/merge/A0/ad;Ljava/lang/String;Lcom/github/catvod/spider/merge/A0/qh;)V",
                false);

        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitTypeInsn(Opcodes.NEW, "com/github/catvod/spider/merge/A0/ut");
        ctor.visitInsn(Opcodes.DUP);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/github/catvod/spider/merge/A0/ut", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.DUP);
        ctor.visitFieldInsn(Opcodes.GETSTATIC,
                "com/github/catvod/spider/merge/A0/ff",
                "b",
                "Lcom/github/catvod/spider/merge/A0/ff;");
        ctor.visitFieldInsn(Opcodes.PUTFIELD,
                "com/github/catvod/spider/merge/A0/ut",
                "a",
                "Lcom/github/catvod/spider/merge/A0/ff;");
        ctor.visitInsn(Opcodes.DUP);
        ctor.visitFieldInsn(Opcodes.GETSTATIC,
                "com/github/catvod/spider/merge/A0/abz",
                "a",
                "Ljava/nio/charset/Charset;");
        ctor.visitFieldInsn(Opcodes.PUTFIELD,
                "com/github/catvod/spider/merge/A0/ut",
                "b",
                "Ljava/nio/charset/Charset;");
        ctor.visitInsn(Opcodes.DUP);
        ctor.visitInsn(Opcodes.ICONST_1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD,
                "com/github/catvod/spider/merge/A0/ut",
                "c",
                "Z");
        ctor.visitInsn(Opcodes.DUP);
        ctor.visitInsn(Opcodes.ICONST_1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD,
                "com/github/catvod/spider/merge/A0/ut",
                "d",
                "I");
        ctor.visitInsn(Opcodes.DUP);
        ctor.visitIntInsn(Opcodes.BIPUSH, 30);
        ctor.visitFieldInsn(Opcodes.PUTFIELD,
                "com/github/catvod/spider/merge/A0/ut",
                "e",
                "I");
        ctor.visitInsn(Opcodes.DUP);
        ctor.visitInsn(Opcodes.ICONST_1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD,
                "com/github/catvod/spider/merge/A0/ut",
                "f",
                "I");
        ctor.visitFieldInsn(Opcodes.PUTFIELD,
                "com/github/catvod/spider/merge/A0/em",
                "a",
                "Lcom/github/catvod/spider/merge/A0/ut;");

        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitInsn(Opcodes.ICONST_1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD,
                "com/github/catvod/spider/merge/A0/em",
                "am",
                "I");

        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitTypeInsn(Opcodes.NEW, "com/github/catvod/spider/merge/A0/xa");
        ctor.visitInsn(Opcodes.DUP);
        ctor.visitTypeInsn(Opcodes.NEW, "com/github/catvod/spider/merge/A0/zl");
        ctor.visitInsn(Opcodes.DUP);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/github/catvod/spider/merge/A0/zl", "<init>", "()V", false);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/github/catvod/spider/merge/A0/xa", "<init>", "(Lcom/github/catvod/spider/merge/A0/zl;)V", false);
        ctor.visitFieldInsn(Opcodes.PUTFIELD,
                "com/github/catvod/spider/merge/A0/em",
                "al",
                "Lcom/github/catvod/spider/merge/A0/xa;");

        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
    }

    private String runtimeObjectReplacement(String className, String methodName, String descriptor) {
        if (className != null
                && className.startsWith("com/github/catvod/spider/")
                && !className.startsWith("com/github/catvod/spider/merge/")) {
            if ("categoryContent".equals(methodName)
                    && "(Ljava/lang/String;Ljava/lang/String;ZLjava/util/HashMap;)Ljava/lang/String;".equals(descriptor)) {
                return "com/github/catvod/spider/merge/c/c";
            }
            if ("detailContent".equals(methodName)
                    && "(Ljava/util/List;)Ljava/lang/String;".equals(descriptor)) {
                return "com/github/catvod/spider/merge/c/e";
            }
            if ("playerContent".equals(methodName)
                    && "(Ljava/lang/String;Ljava/lang/String;Ljava/util/List;)Ljava/lang/String;".equals(descriptor)) {
                return "com/github/catvod/spider/merge/c/c";
            }
        }
        if ("com/github/catvod/spider/merge/A0/jo".equals(className)
                && "n".equals(methodName)
                && "(Lcom/github/catvod/spider/merge/A0/aam;ILjava/lang/String;IIIII)Lcom/github/catvod/spider/merge/A0/to;".equals(descriptor)) {
            return "com/github/catvod/spider/merge/A0/to";
        }
        if ("com/github/catvod/spider/AppDrama".equals(className)
                && "categoryContent".equals(methodName)
                && "(Ljava/lang/String;Ljava/lang/String;ZLjava/util/HashMap;)Ljava/lang/String;".equals(descriptor)) {
            return "com/github/catvod/spider/merge/c/c";
        }
        if ("com/github/catvod/spider/AppDrama".equals(className)
                && "detailContent".equals(methodName)
                && "(Ljava/util/List;)Ljava/lang/String;".equals(descriptor)) {
            return "com/github/catvod/spider/merge/c/e";
        }
        if ("com/github/catvod/spider/AppDrama".equals(className)
                && "playerContent".equals(methodName)
                && "(Ljava/lang/String;Ljava/lang/String;Ljava/util/List;)Ljava/lang/String;".equals(descriptor)) {
            return "com/github/catvod/spider/merge/c/c";
        }
        if ("com/github/catvod/spider/merge/c/c".equals(className)
                && "i".equals(methodName)
                && "(Ljava/util/List;)Ljava/lang/String;".equals(descriptor)) {
            return "com/github/catvod/spider/merge/c/c";
        }
        if ("com/github/catvod/spider/merge/A0/rf".equals(className) && "<clinit>".equals(methodName)) {
            return "com/github/catvod/spider/merge/A0/rf";
        }
        if ("com/github/catvod/spider/merge/A0/wj".equals(className) && "<clinit>".equals(methodName)) {
            return "com/github/catvod/spider/merge/A0/wj";
        }
        if ("com/github/catvod/spider/merge/A0/abr".equals(className) && "<clinit>".equals(methodName)) {
            return "com/github/catvod/spider/merge/A0/cn";
        }
        return null;
    }

    private void runDex2JarCommand(Path dexFile, Path outputJar) throws IOException, InterruptedException {
        String command = adaptDex2JarCommand(dex2jarCommand);
        if (!StringUtils.hasText(command)) {
            throw new IllegalStateException("未配置 app.dex2jar-command");
        }
        command = command
                .replace("{input}", quoteShellPath(dexFile))
                .replace("{output}", quoteShellPath(outputJar));

        ProcessBuilder processBuilder;
        if (isWindows()) {
            processBuilder = new ProcessBuilder("cmd", "/c", command);
        } else {
            processBuilder = new ProcessBuilder("sh", "-c", command);
        }
        processBuilder.directory(appPathsService.getBaseDir().toFile());
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        String output;
        try (InputStream in = process.getInputStream()) {
            output = new java.io.BufferedReader(new java.io.InputStreamReader(in))
                    .lines()
                    .collect(Collectors.joining(System.lineSeparator()));
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("dex2jar 命令执行失败(exit=" + exitCode + "): " + output);
        }
    }

    private String adaptDex2JarCommand(String configured) {
        String trimmed = configured == null ? "" : configured.trim();
        if (!StringUtils.hasText(trimmed) || "__AUTO__".equalsIgnoreCase(trimmed)) {
            return bundledDex2JarCommand();
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (isWindows() && lower.contains("d2j-dex2jar.sh")) {
            return bundledDex2JarCommand();
        }
        if (!isWindows() && lower.contains("d2j-dex2jar.bat")) {
            return bundledDex2JarCommand();
        }
        return trimmed;
    }

    private String bundledDex2JarCommand() {
        String fileName = isWindows() ? "d2j-dex2jar.bat" : "d2j-dex2jar.sh";
        Path scriptPath = appPathsService.resolveExisting(Path.of("tools", "dex-tools-v2.4", fileName).toString());
        String quotedScript = quoteShellPath(scriptPath);
        if (isWindows()) {
            return quotedScript + " {input} -o {output} --force";
        }
        return "sh " + quotedScript + " {input} -o {output} --force";
    }

    private boolean isWindows() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        return osName.contains("win");
    }

    @Override
    public void destroy() {
        if (spiderOperationExecutor != null) {
            spiderOperationExecutor.shutdownNow();
        }
    }

    private String spiderExecutorStats() {
        ThreadPoolExecutor executor = spiderOperationThreadPool;
        if (executor == null) {
            return "executor=uninitialized";
        }
        return "active=" + executor.getActiveCount()
                + ", pool=" + executor.getPoolSize()
                + ", queued=" + executor.getQueue().size()
                + ", max=" + executor.getMaximumPoolSize();
    }

    private String quoteShellPath(Path path) {
        return "\"" + path.toAbsolutePath() + "\"";
    }

    private Path extractPrimaryDex(Path jarPath) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry dexEntry = jarFile.getJarEntry("classes.dex");
            if (dexEntry == null) {
                throw new IllegalStateException("未找到 classes.dex");
            }
            Path dexFile = Files.createTempFile("tvbox-spider-", ".dex");
            try (InputStream in = jarFile.getInputStream(dexEntry)) {
                Files.copy(in, dexFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return dexFile;
        }
    }

    private boolean isDexOnlyJar(Path jarPath) {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            boolean hasDex = false;
            boolean hasClass = false;
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".dex")) {
                    hasDex = true;
                }
                if (name.endsWith(".class")) {
                    hasClass = true;
                    break;
                }
            }
            return hasDex && !hasClass;
        } catch (Exception ex) {
            return false;
        }
    }

    private void initSpiderIfPossible(Class<?> spiderClass, Object spider, SiteDefinition site) {
        String ext = site.getExtText();
        if (spider instanceof Spider typedSpider) {
            try {
                typedSpider.init(androidContext, ext);
                return;
            } catch (Throwable ignore) {
            }
            try {
                typedSpider.init(androidApplication, ext);
                return;
            } catch (Throwable ignore) {
            }
        }
        for (Method method : spiderClass.getMethods()) {
            if (!"init".equals(method.getName())) {
                continue;
            }
            try {
                if (tryInvokeInitMethod(method, spider, ext)) {
                    return;
                }
            } catch (Exception ignore) {
            }
        }

        try {
            Method m2 = spiderClass.getMethod("init", Object.class, String.class);
            if (tryInvokeInitMethod(m2, spider, ext)) {
                return;
            }
        } catch (Exception ignore) {
        }

        try {
            Method m1 = spiderClass.getMethod("init", Object.class);
            tryInvokeInitMethod(m1, spider, ext);
        } catch (Exception ignore) {
        }
    }

    private void initSpiderApiIfPossible(Class<?> spiderClass, Object spider) {
        SpiderApi spiderApi = new SpiderApi(localBaseAddress(), serverPort);
        for (Method method : spiderClass.getMethods()) {
            if (!"initApi".equals(method.getName()) || method.getParameterCount() != 1) {
                continue;
            }
            try {
                Class<?> parameterType = method.getParameterTypes()[0];
                if (parameterType.isAssignableFrom(SpiderApi.class) || parameterType == Object.class) {
                    method.invoke(spider, spiderApi);
                    return;
                }
            } catch (Exception ignore) {
            }
        }
    }

    private void patchSpiderStateIfNeeded(Object spider, SiteDefinition site) {
        if (spider == null || site == null || !StringUtils.hasText(site.getApi())) {
            return;
        }
        if ("csp_PanWebShare".equals(site.getApi()) || "csp_PanWebShareQY".equals(site.getApi())) {
            patchPanWebShareHost(spider, site);
            return;
        }
        if ("csp_Nox".equals(site.getApi())) {
            patchNoxRuntimeState(spider);
            return;
        }
        if ("csp_AppDrama".equals(site.getApi())) {
            patchAppDramaState(spider, site);
        }
    }

    private void warmUpSpiderIfNeeded(Object spider, SiteDefinition site) {
        if (!(spider instanceof Spider typedSpider) || site == null || !StringUtils.hasText(site.getJar())) {
            return;
        }
        try {
            typedSpider.homeContent(false);
        } catch (Throwable ignore) {
        }
    }

    private void patchPanWebShareHost(Object spider, SiteDefinition site) {
        try {
            java.lang.reflect.Field field = spider.getClass().getDeclaredField("f");
            field.setAccessible(true);
            Object current = field.get(spider);
            if (current instanceof String text && StringUtils.hasText(text)) {
                return;
            }
            String fallback = firstPanSite(site.getExt());
            if (StringUtils.hasText(fallback)) {
                field.set(spider, fallback);
            }
        } catch (Exception ignore) {
        }
    }

    private void patchNoxRuntimeState(Object spider) {
        for (String fieldName : List.of("G9", "OA")) {
            try {
                java.lang.reflect.Field field = spider.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(spider);
                if (value == null) {
                    field.set(spider, "");
                }
            } catch (Exception ignore) {
            }
        }
    }

    private String firstPanSite(JsonNode extNode) {
        if (extNode == null || extNode.isNull()) {
            return null;
        }
        JsonNode siteNode = extNode.get("site");
        if (siteNode == null || siteNode.isNull()) {
            return null;
        }
        if (siteNode.isTextual()) {
            String text = siteNode.asText();
            return StringUtils.hasText(text) ? text.trim() : null;
        }
        if (siteNode.isArray()) {
            for (JsonNode item : siteNode) {
                if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                    return item.asText().trim();
                }
            }
        }
        return null;
    }

    private void patchAppDramaState(Object spider, SiteDefinition site) {
        String host = resolveAppDramaHost(site);
        if (StringUtils.hasText(host) && !StringUtils.hasText(readStringField(spider, "d"))) {
            reinitAppDramaWithResolvedHost(spider, site, host);
        }
        String publicKey = textOf(site.getExt(), "publicKey");
        String dataKey = textOf(site.getExt(), "dataKey");
        String dataIv = textOf(site.getExt(), "dataIv");
        setFieldIfBlankOrInvalidHost(spider, "a", host);
        setFieldIfBlank(spider, "b", publicKey);
        setStringArrayFieldIfMissing(spider, "f", new String[]{dataKey, dataIv});
    }

    private void reinitAppDramaWithResolvedHost(Object spider, SiteDefinition site, String host) {
        if (spider == null || site == null || !StringUtils.hasText(host)) {
            return;
        }
        try {
            ObjectNode extNode = site.getExt() != null && site.getExt().isObject()
                    ? ((ObjectNode) site.getExt()).deepCopy()
                    : JsonNodeFactory.instance.objectNode();
            extNode.put("host", host);
            String extText = objectMapper.writeValueAsString(extNode);
            initSpiderIfPossible(spider.getClass(), spider, siteWithExt(site, extText));
        } catch (Exception ignore) {
        }
    }

    private SiteDefinition siteWithExt(SiteDefinition original, String extText) throws IOException {
        SiteDefinition copy = new SiteDefinition();
        copy.setUid(original.getUid());
        copy.setKey(original.getKey());
        copy.setName(original.getName());
        copy.setType(original.getType());
        copy.setApi(original.getApi());
        copy.setSearchable(original.getSearchable());
        copy.setQuickSearch(original.getQuickSearch());
        copy.setFilterable(original.getFilterable());
        copy.setPlayUrl(original.getPlayUrl());
        copy.setJar(original.getJar());
        copy.setPlayerType(original.getPlayerType());
        copy.setClickSelector(original.getClickSelector());
        copy.setCategories(new ArrayList<>(original.getCategories()));
        copy.setExt(objectMapper.readTree(extText));
        return copy;
    }

    private String resolveAppDramaHost(SiteDefinition site) {
        String host = normalizeHostUrl(textOf(site.getExt(), "host"));
        if (StringUtils.hasText(host)) {
            return host;
        }

        String siteUrl = textOf(site.getExt(), "site");
        if (!StringUtils.hasText(siteUrl)) {
            return null;
        }

        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(siteUrl).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "okhttp/3.15");
            connection.connect();
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) {
                return null;
            }
            try (InputStream inputStream = connection.getInputStream()) {
                JsonNode node = objectMapper.readTree(inputStream);
                return normalizeHostUrl(firstNonBlank(
                        textOf(node, "domain"),
                        textOf(node, "host"),
                        textOf(node, "url")));
            }
        } catch (Exception ignore) {
            return null;
        }
    }

    private void setFieldIfBlank(Object target, String fieldName, String value) {
        if (target == null || !StringUtils.hasText(value)) {
            return;
        }
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object current = field.get(target);
            if (current instanceof String text && StringUtils.hasText(text)) {
                return;
            }
            field.set(target, value.trim());
        } catch (Exception ignore) {
        }
    }

    private void setFieldIfBlankOrInvalidHost(Object target, String fieldName, String value) {
        if (target == null || !StringUtils.hasText(value)) {
            return;
        }
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object current = field.get(target);
            if (current instanceof String text && startsWithPlayableScheme(text.trim())) {
                return;
            }
            field.set(target, value.trim());
        } catch (Exception ignore) {
        }
    }

    private String readStringField(Object target, String fieldName) {
        if (target == null || !StringUtils.hasText(fieldName)) {
            return null;
        }
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(target);
            if (value instanceof String text && StringUtils.hasText(text)) {
                return text.trim();
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private void setStringArrayFieldIfMissing(Object target, String fieldName, String[] values) {
        if (target == null || !StringUtils.hasText(fieldName) || values == null || values.length == 0) {
            return;
        }
        boolean hasAnyValue = false;
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                hasAnyValue = true;
                break;
            }
        }
        if (!hasAnyValue) {
            return;
        }
        try {
            java.lang.reflect.Field field = findDeclaredField(target.getClass(), fieldName, String[].class);
            field.setAccessible(true);
            Object currentValue = field.get(target);
            if (currentValue instanceof String[] current && current.length >= values.length) {
                boolean complete = true;
                for (int i = 0; i < values.length; i++) {
                    if (!StringUtils.hasText(values[i])) {
                        continue;
                    }
                    if (!StringUtils.hasText(current[i])) {
                        complete = false;
                        break;
                    }
                }
                if (complete) {
                    return;
                }
            }
            String[] patched = new String[values.length];
            if (currentValue instanceof String[] current) {
                System.arraycopy(current, 0, patched, 0, Math.min(current.length, patched.length));
            }
            for (int i = 0; i < values.length; i++) {
                if (!StringUtils.hasText(values[i])) {
                    continue;
                }
                if (!StringUtils.hasText(patched[i])) {
                    patched[i] = values[i].trim();
                }
            }
            field.set(target, patched);
        } catch (Exception ignore) {
        }
    }

    private java.lang.reflect.Field findDeclaredField(Class<?> type, String fieldName, Class<?> expectedType) throws NoSuchFieldException {
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            if (!field.getName().equals(fieldName)) {
                continue;
            }
            if (expectedType == null || field.getType().equals(expectedType)) {
                return field;
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + fieldName + ":" + (expectedType == null ? "*" : expectedType.getName()));
    }

    private String normalizeHostUrl(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String host = value.trim();
        if (host.startsWith("http://") || host.startsWith("https://")) {
            return host;
        }
        if (host.startsWith("//")) {
            return "https:" + host;
        }
        if (host.startsWith("/")) {
            return null;
        }
        return "http://" + host;
    }

    private String textOf(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !StringUtils.hasText(fieldName)) {
            return null;
        }
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull() || !field.isTextual()) {
            return null;
        }
        String text = field.asText();
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String localBaseAddress() {
        return "http://127.0.0.1:" + serverPort + "/";
    }

    private void patchConvertedJarIfNeeded(Path jarPath) {
        try (java.nio.file.FileSystem jarFs = FileSystems.newFileSystem(URI.create("jar:" + jarPath.toUri()), Map.of())) {
            patchClassAccessFlags(jarFs.getPath("/com/github/catvod/spider/merge/A0/wk.class"));
            patchInterfaceBridgeMethods(jarFs.getPath("/com/google/protobuf/MessageOrBuilder.class"));
            patchInterfaceBridgeMethods(jarFs.getPath("/com/google/protobuf/MessageLiteOrBuilder.class"));
        } catch (Exception ignore) {
        }
    }

    private void patchClassAccessFlags(Path classPath) throws IOException {
        if (classPath == null || !Files.exists(classPath)) {
            return;
        }
        byte[] classBytes = Files.readAllBytes(classPath);
        byte[] patched = clearAbstractFlag(classBytes);
        if (patched != null) {
            Files.write(classPath, patched);
        }
    }

    private void patchObjectBuilderClass(Path classPath, String replacementInternalName) throws IOException {
        if (classPath == null || !Files.exists(classPath) || !StringUtils.hasText(replacementInternalName)) {
            return;
        }
        byte[] classBytes = Files.readAllBytes(classPath);
        byte[] patched = patchNewObjectBuilder(classBytes, replacementInternalName);
        if (patched != null) {
            Files.write(classPath, patched);
        }
    }

    private void patchInterfaceBridgeMethods(Path classPath) throws IOException {
        if (classPath == null || !Files.exists(classPath)) {
            return;
        }
        byte[] classBytes = Files.readAllBytes(classPath);
        byte[] patched = clearInterfaceBridgeFlags(classBytes);
        if (patched != null) {
            Files.write(classPath, patched);
        }
    }

    private byte[] clearAbstractFlag(byte[] classBytes) {
        if (classBytes == null || classBytes.length < 12) {
            return null;
        }
        if (readU4(classBytes, 0) != 0xCAFEBABE) {
            return null;
        }
        int cpCount = readU2(classBytes, 8);
        int offset = 10;
        for (int index = 1; index < cpCount; index++) {
            int tag = classBytes[offset] & 0xFF;
            offset++;
            switch (tag) {
                case 1 -> {
                    int len = readU2(classBytes, offset);
                    offset += 2 + len;
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> offset += 4;
                case 5, 6 -> {
                    offset += 8;
                    index++;
                }
                case 7, 8, 16, 19, 20 -> offset += 2;
                case 15 -> offset += 3;
                default -> {
                    return null;
                }
            }
        }
        if (offset + 2 > classBytes.length) {
            return null;
        }
        int accessFlags = readU2(classBytes, offset);
        if ((accessFlags & 0x0400) == 0) {
            return null;
        }
        byte[] patched = classBytes.clone();
        accessFlags &= ~0x0400;
        patched[offset] = (byte) ((accessFlags >>> 8) & 0xFF);
        patched[offset + 1] = (byte) (accessFlags & 0xFF);
        return patched;
    }

    private byte[] clearInterfaceBridgeFlags(byte[] classBytes) {
        if (classBytes == null || classBytes.length < 12) {
            return null;
        }
        if (readU4(classBytes, 0) != 0xCAFEBABE) {
            return null;
        }

        int cpCount = readU2(classBytes, 8);
        int offset = 10;
        for (int index = 1; index < cpCount; index++) {
            int tag = classBytes[offset] & 0xFF;
            offset++;
            switch (tag) {
                case 1 -> {
                    int len = readU2(classBytes, offset);
                    offset += 2 + len;
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> offset += 4;
                case 5, 6 -> {
                    offset += 8;
                    index++;
                }
                case 7, 8, 16, 19, 20 -> offset += 2;
                case 15 -> offset += 3;
                default -> {
                    return null;
                }
            }
        }

        if ((readU2(classBytes, offset) & Opcodes.ACC_INTERFACE) == 0) {
            return null;
        }

        offset += 6;
        int interfacesCount = readU2(classBytes, offset);
        offset += 2 + interfacesCount * 2;

        int fieldsCount = readU2(classBytes, offset);
        offset += 2;
        for (int i = 0; i < fieldsCount; i++) {
            offset += 6;
            int attrCount = readU2(classBytes, offset);
            offset += 2;
            for (int j = 0; j < attrCount; j++) {
                offset += 2;
                int attrLen = readU4(classBytes, offset);
                offset += 4 + attrLen;
            }
        }

        int methodsCount = readU2(classBytes, offset);
        offset += 2;
        byte[] patched = null;
        boolean changed = false;
        for (int i = 0; i < methodsCount; i++) {
            int methodAccessOffset = offset;
            int methodAccess = readU2(classBytes, methodAccessOffset);
            if ((methodAccess & Opcodes.ACC_BRIDGE) != 0) {
                if (patched == null) {
                    patched = classBytes.clone();
                }
                writeU2(patched, methodAccessOffset, methodAccess & ~Opcodes.ACC_BRIDGE);
                changed = true;
            }

            offset += 6;
            int attrCount = readU2(classBytes, offset);
            offset += 2;
            for (int j = 0; j < attrCount; j++) {
                offset += 2;
                int attrLen = readU4(classBytes, offset);
                offset += 4 + attrLen;
            }
        }

        return changed ? patched : null;
    }

    private byte[] patchNewObjectBuilder(byte[] classBytes, String replacementInternalName) {
        if (classBytes == null || classBytes.length < 12 || !StringUtils.hasText(replacementInternalName)) {
            return null;
        }
        if (readU4(classBytes, 0) != 0xCAFEBABE) {
            return null;
        }

        int cpCount = readU2(classBytes, 8);
        int[] entryOffsets = new int[cpCount];
        int[] tags = new int[cpCount];
        int javaObjectUtf8Index = -1;
        int replacementUtf8Index = -1;
        int initNameIndex = -1;
        int initTypeIndex = -1;
        int offset = 10;

        for (int index = 1; index < cpCount; index++) {
            entryOffsets[index] = offset;
            int tag = classBytes[offset] & 0xFF;
            tags[index] = tag;
            offset++;
            switch (tag) {
                case 1 -> {
                    int len = readU2(classBytes, offset);
                    String text = readUtf8(classBytes, offset + 2, len);
                    if ("java/lang/Object".equals(text)) {
                        javaObjectUtf8Index = index;
                    } else if (replacementInternalName.equals(text)) {
                        replacementUtf8Index = index;
                    } else if ("<init>".equals(text)) {
                        initNameIndex = index;
                    } else if ("()V".equals(text)) {
                        initTypeIndex = index;
                    }
                    offset += 2 + len;
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> offset += 4;
                case 5, 6 -> {
                    offset += 8;
                    index++;
                }
                case 7, 8, 16, 19, 20 -> offset += 2;
                case 15 -> offset += 3;
                default -> {
                    return null;
                }
            }
        }

        if (javaObjectUtf8Index < 0 || replacementUtf8Index < 0 || initNameIndex < 0 || initTypeIndex < 0) {
            return null;
        }

        int objectClassIndex = -1;
        int replacementClassIndex = -1;
        int initNameAndTypeIndex = -1;
        int objectInitMethodRefIndex = -1;

        for (int index = 1; index < cpCount; index++) {
            int entryOffset = entryOffsets[index];
            if (entryOffset <= 0) {
                continue;
            }
            switch (tags[index]) {
                case 7 -> {
                    int nameIndex = readU2(classBytes, entryOffset + 1);
                    if (nameIndex == javaObjectUtf8Index) {
                        objectClassIndex = index;
                    } else if (nameIndex == replacementUtf8Index) {
                        replacementClassIndex = index;
                    }
                }
                case 12 -> {
                    int nameIndex = readU2(classBytes, entryOffset + 1);
                    int descriptorIndex = readU2(classBytes, entryOffset + 3);
                    if (nameIndex == initNameIndex && descriptorIndex == initTypeIndex) {
                        initNameAndTypeIndex = index;
                    }
                }
                case 10 -> {
                    int classIndex = readU2(classBytes, entryOffset + 1);
                    int nameAndTypeIndex = readU2(classBytes, entryOffset + 3);
                    if (classIndex == objectClassIndex && nameAndTypeIndex == initNameAndTypeIndex) {
                        objectInitMethodRefIndex = index;
                    }
                }
                default -> {
                }
            }
        }

        if (objectClassIndex < 0 || replacementClassIndex < 0 || initNameAndTypeIndex < 0 || objectInitMethodRefIndex < 0) {
            return null;
        }

        byte[] patched = classBytes.clone();
        int methodRefOffset = entryOffsets[objectInitMethodRefIndex];
        writeU2(patched, methodRefOffset + 1, replacementClassIndex);

        int oldClassHi = (objectClassIndex >>> 8) & 0xFF;
        int oldClassLo = objectClassIndex & 0xFF;
        int initRefHi = (objectInitMethodRefIndex >>> 8) & 0xFF;
        int initRefLo = objectInitMethodRefIndex & 0xFF;
        int newClassHi = (replacementClassIndex >>> 8) & 0xFF;
        int newClassLo = replacementClassIndex & 0xFF;

        boolean changed = false;
        for (int i = offset; i < patched.length - 6; i++) {
            if ((patched[i] & 0xFF) == 0xBB
                    && (patched[i + 1] & 0xFF) == oldClassHi
                    && (patched[i + 2] & 0xFF) == oldClassLo
                    && (patched[i + 3] & 0xFF) == 0x59
                    && (patched[i + 4] & 0xFF) == 0xB7
                    && (patched[i + 5] & 0xFF) == initRefHi
                    && (patched[i + 6] & 0xFF) == initRefLo) {
                patched[i + 1] = (byte) newClassHi;
                patched[i + 2] = (byte) newClassLo;
                changed = true;
            }
        }

        return changed ? patched : null;
    }

    private int readU2(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private int readU4(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private String readUtf8(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void writeU2(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) ((value >>> 8) & 0xFF);
        bytes[offset + 1] = (byte) (value & 0xFF);
    }

    private Path resolveJar(String sessionId, SiteDefinition site) {
        String jarSpec = site.getJar();
        if (!StringUtils.hasText(jarSpec)) {
            ConfigPayload payload = configService.getPayload(sessionId);
            if (payload != null) {
                jarSpec = payload.getSpider();
            }
        }
        if (!StringUtils.hasText(jarSpec)) {
            throw new IllegalStateException("未找到 spider jar 配置");
        }

        JarSpec resolvedSpec = parseJarSpec(jarSpec);
        String jarUrl = resolvedSpec.url();
        try {
            String hash = md5(jarUrl + "|" + resolvedSpec.expectedMd5() + "|" + resolvedSpec.imageWrapped());
            Path jarDir = configService.getCacheDir().resolve("jars");
            Files.createDirectories(jarDir);
            Path jarPath = jarDir.resolve(hash + ".jar");
            Path legacyJarPath = jarDir.resolve(md5(jarUrl) + ".jar");
            if (Files.exists(jarPath)
                    && Files.size(jarPath) > 0
                    && matchesExpectedMd5(jarPath, resolvedSpec.expectedMd5())
                    && isUsableSpiderJar(jarPath)) {
                return jarPath;
            }
            Files.deleteIfExists(jarPath);
            if (!jarPath.equals(legacyJarPath)
                    && Files.exists(legacyJarPath)
                    && Files.size(legacyJarPath) > 0
                    && matchesExpectedMd5(legacyJarPath, resolvedSpec.expectedMd5())
                    && isUsableSpiderJar(legacyJarPath)) {
                return legacyJarPath;
            }
            if (!jarPath.equals(legacyJarPath)) {
                Files.deleteIfExists(legacyJarPath);
            }

            boolean downloaded = downloadJar(resolvedSpec, jarPath);
            if (!downloaded && jarUrl.contains("/json/jar/")) {
                String fallbackUrl = jarUrl.replace("/json/jar/", "/jar/");
                downloaded = downloadJar(new JarSpec(fallbackUrl, resolvedSpec.expectedMd5(), resolvedSpec.imageWrapped()), jarPath);
            }
            if (!downloaded) {
                throw new IllegalStateException("下载 jar 失败, status=404");
            }
            if (!matchesExpectedMd5(jarPath, resolvedSpec.expectedMd5())) {
                Files.deleteIfExists(jarPath);
                throw new IllegalStateException("jar md5 mismatch: " + resolvedSpec.expectedMd5());
            }
            return jarPath;
        } catch (IOException ex) {
            throw new IllegalStateException("下载 jar 失败: " + ex.getMessage(), ex);
        }
    }

    private boolean downloadJar(JarSpec jarSpec, Path jarPath) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(jarSpec.url()).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent", "okhttp/3.15");
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return false;
            }
            try (InputStream inputStream = conn.getInputStream()) {
                byte[] body = inputStream.readAllBytes();
                byte[] jarBytes = jarSpec.imageWrapped() ? unwrapImageJar(body) : body;
                if (jarBytes == null || jarBytes.length == 0 || !looksLikeJar(jarBytes)) {
                    return false;
                }
                Files.createDirectories(jarPath.getParent());
                Path tempJar = Files.createTempFile(jarPath.getParent(), "spider-", ".jar");
                try {
                    Files.write(tempJar, jarBytes);
                    if (!isUsableSpiderJar(tempJar)) {
                        return false;
                    }
                    Files.move(tempJar, jarPath, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(tempJar);
                }
            }
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private JarSpec parseJarSpec(String jarSpec) {
        String spec = jarSpec == null ? "" : jarSpec.trim();
        String jarUrl = spec;
        String expectedMd5 = "";
        if (jarUrl.contains(";md5;")) {
            String[] parts = jarUrl.split(";md5;", 2);
            jarUrl = parts[0];
            expectedMd5 = parts.length > 1 ? parts[1].trim() : "";
        }
        boolean imageWrapped = jarUrl.startsWith("img+");
        jarUrl = jarUrl.replace("img+", "");
        while (jarUrl.startsWith(".")) {
            jarUrl = jarUrl.substring(1);
        }
        return new JarSpec(jarUrl, expectedMd5, imageWrapped);
    }

    private byte[] unwrapImageJar(byte[] body) {
        if (body == null || body.length == 0) {
            return new byte[0];
        }
        String text = new String(body, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("[A-Za-z0-9]{8}\\*\\*").matcher(text);
        if (matcher.find()) {
            String encoded = text.substring(text.indexOf(matcher.group()) + 10).trim();
            try {
                return Base64.getMimeDecoder().decode(encoded);
            } catch (Exception ignore) {
            }
        }
        return looksLikeJar(body) ? body : new byte[0];
    }

    private boolean looksLikeJar(byte[] body) {
        return body.length >= 4
                && body[0] == 'P'
                && body[1] == 'K'
                && body[2] == 3
                && body[3] == 4;
    }

    private boolean matchesExpectedMd5(Path jarPath, String expectedMd5) {
        if (!StringUtils.hasText(expectedMd5)) {
            return true;
        }
        try {
            return expectedMd5.trim().equalsIgnoreCase(fileMd5Hex(jarPath));
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isUsableSpiderJar(Path jarPath) {
        if (jarPath == null || !Files.exists(jarPath)) {
            return false;
        }
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            boolean hasDex = false;
            boolean hasClass = false;
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".dex")) {
                    hasDex = true;
                }
                if (name.endsWith(".class")) {
                    hasClass = true;
                }
                if (hasDex || hasClass) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean jarContainsClass(Path jarPath, String className) {
        if (!StringUtils.hasText(className) || !isUsableSpiderJar(jarPath)) {
            return false;
        }
        String entryName = className.replace('.', '/') + ".class";
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            return jarFile.getJarEntry(entryName) != null;
        } catch (Exception ex) {
            return false;
        }
    }

    private String fileMd5Hex(Path filePath) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream in = Files.newInputStream(filePath)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    md.update(buffer, 0, read);
                }
            }
        }
        byte[] digest = md.digest();
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            builder.append(Character.forDigit((b >>> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    private URLClassLoader ensureClassLoader(Path jarPath) {
        return classLoaderMap.computeIfAbsent(jarPath.toString(), k -> {
            try {
                URL url = jarPath.toUri().toURL();
                URLClassLoader loader = new URLClassLoader(new URL[]{url}, Thread.currentThread().getContextClassLoader());
                try {
                    initializeJarRuntime(loader, k);
                } catch (Throwable ex) {
                    System.err.println("Skip spider runtime bootstrap: " + ex.getMessage());
                }
                return loader;
            } catch (Exception ex) {
                throw new IllegalStateException("加载 jar 失败: " + ex.getMessage(), ex);
            }
        });
    }

    private void initializeJarRuntime(URLClassLoader loader, String loaderKey) {
        try {
            Class<?> initClass = loader.loadClass("com.github.catvod.spider.Init");
            for (Method method : initClass.getMethods()) {
                if (!"init".equals(method.getName()) || method.getParameterCount() != 1) {
                    continue;
                }
                if (tryInvokeInitMethod(method, null, null)) {
                    patchInitStaticsIfPossible(initClass);
                    break;
                }
            }
        } catch (ClassNotFoundException ignore) {
        } catch (Throwable ex) {
            throw new IllegalStateException("鍒濆鍖?Spider Init 澶辫触: " + ex.getMessage(), ex);
        }

        try {
            Class<?> proxyClass = loader.loadClass("com.github.catvod.spider.Proxy");
            Method proxyMethod = proxyClass.getMethod("proxy", Map.class);
            proxyMethodMap.put(loaderKey, proxyMethod);
        } catch (ClassNotFoundException ignore) {
        } catch (Throwable ex) {
            throw new IllegalStateException("鍔犺浇 Spider Proxy 澶辫触: " + ex.getMessage(), ex);
        }
    }

    private boolean tryInvokeInitMethod(Method method, Object target, String ext) {
        if (method == null) {
            return false;
        }
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length == 1) {
            for (Object candidate : initContextCandidates(paramTypes[0])) {
                try {
                    method.invoke(target, candidate);
                    return true;
                } catch (Exception ignore) {
                }
            }
            return false;
        }
        if (paramTypes.length == 2) {
            for (Object candidate : initContextCandidates(paramTypes[0])) {
                try {
                    method.invoke(target, candidate, ext);
                    return true;
                } catch (Exception ignore) {
                }
            }
        }
        return false;
    }

    private List<Object> initContextCandidates(Class<?> parameterType) {
        List<Object> candidates = new ArrayList<>();
        if (parameterType == null) {
            return candidates;
        }
        if (parameterType.isAssignableFrom(androidContext.getClass())
                || parameterType.isAssignableFrom(android.content.Context.class)
                || parameterType == Object.class) {
            candidates.add(androidContext);
        }
        if (parameterType.isAssignableFrom(androidApplication.getClass())
                || parameterType.isAssignableFrom(android.content.Context.class)
                || parameterType == Object.class) {
            candidates.add(androidApplication);
        }
        return candidates;
    }

    private void patchInitStaticsIfPossible(Class<?> initClass) {
        if (initClass == null) {
            return;
        }
        for (java.lang.reflect.Field field : initClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                continue;
            }
            try {
                Class<?> fieldType = field.getType();
                field.setAccessible(true);
                if (fieldType.isAssignableFrom(androidContext.getClass())
                        || fieldType.isAssignableFrom(android.content.Context.class)) {
                    if (field.get(null) == null) {
                        field.set(null, androidContext);
                    }
                    continue;
                }
                if (fieldType.isAssignableFrom(androidApplication.getClass()) && field.get(null) == null) {
                    field.set(null, androidApplication);
                }
            } catch (Exception ignore) {
            }
        }
    }

    private JsonNode loadCategoryNode(Object spider, String tid, String pg, boolean filter, Map<String, String> extend) {
        HashMap<String, String> ext = new HashMap<>();
        if (extend != null) {
            ext.putAll(extend);
        }

        RuntimeException firstFailure = null;
        try {
            JsonNode node = readJson(invokeCategoryContent(spider, tid, pg, filter, ext));
            if (isCategoryUsable(node) || !filter) {
                return node;
            }
        } catch (RuntimeException ex) {
            firstFailure = ex;
            if (!filter) {
                throw ex;
            }
        }

        try {
            return readJson(invokeCategoryContent(spider, tid, pg, false, ext));
        } catch (RuntimeException ex) {
            if (firstFailure != null) {
                ex.addSuppressed(firstFailure);
            }
            throw ex;
        }
    }

    private boolean isCategoryUsable(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        return node.has("list") && node.get("list").isArray() && node.get("list").size() > 0;
    }

    private String invokeStringAnyDetailed(Object target, String methodName, Object[]... candidateArgs) {
        Throwable last = null;
        Throwable preferred = null;
        List<String> attempts = new ArrayList<>();
        for (Object[] args : candidateArgs) {
            try {
                return invokeByArgs(target, methodName, args);
            } catch (Throwable ex) {
                last = ex;
                ex.printStackTrace();
                Throwable root = unwrapRootCause(ex);
                attempts.add(args.length + " -> " + root.getClass().getName() + (root.getMessage() == null ? "" : " - " + root.getMessage()));
                if (preferred == null || (isNoSuchMethod(unwrapRootCause(preferred)) && !isNoSuchMethod(root))) {
                    preferred = ex;
                }
            }
        }
        Throwable chosen = preferred != null ? preferred : last;
        Throwable root = unwrapRootCause(chosen);
        String rootType = root == null ? "unknown" : root.getClass().getName();
        String rootMessage = root == null ? "" : root.getMessage();
        String attemptText = attempts.isEmpty() ? "" : " | attempts=" + String.join("; ", attempts);
        throw new IllegalStateException(methodName + " 璋冪敤澶辫触: " + rootType + (rootMessage == null ? "" : " - " + rootMessage) + attemptText, chosen);
    }

    private String invokeHomeContent(Object spider, boolean filter) {
        if (spider instanceof Spider typedSpider) {
            return stringifyResult(typedSpider.homeContent(filter));
        }
        return invokeStringAnyDetailed(spider, "homeContent", new Object[]{filter}, new Object[]{});
    }

    private String invokeHomeVideoContent(Object spider) {
        if (spider instanceof Spider typedSpider) {
            return stringifyResult(typedSpider.homeVideoContent());
        }
        return invokeStringAnyDetailed(spider, "homeVideoContent", new Object[]{});
    }

    private String invokeCategoryContent(Object spider, String tid, String pg, boolean filter, HashMap<String, String> extend) {
        if (spider instanceof Spider typedSpider) {
            return stringifyResult(typedSpider.categoryContent(tid, pg, filter, extend));
        }
        return invokeStringAnyDetailed(spider, "categoryContent",
                new Object[]{tid, pg, filter, extend},
                new Object[]{tid, pg, filter, (Map<String, String>) extend},
                new Object[]{tid, pg, filter},
                new Object[]{tid, pg},
                new Object[]{tid});
    }

    private String invokeDetailContent(Object spider, String id) {
        List<String> ids = new ArrayList<>();
        ids.add(id);
        if (spider instanceof Spider typedSpider) {
            return stringifyResult(typedSpider.detailContent(ids));
        }
        return invokeStringAnyDetailed(spider, "detailContent", new Object[]{ids}, new Object[]{id});
    }

    private String invokeSearchContent(Object spider, String wd, boolean quick) {
        if (spider instanceof Spider typedSpider) {
            return stringifyResult(typedSpider.searchContent(wd, quick));
        }
        return invokeStringAnyDetailed(spider, "searchContent", new Object[]{wd, quick}, new Object[]{wd});
    }

    private String invokePlayerContent(Object spider, String flag, String id, List<String> vipFlags) {
        if (spider instanceof Spider typedSpider) {
            return stringifyResult(typedSpider.playerContent(flag, id, vipFlags));
        }
        return invokeStringAnyDetailed(spider, "playerContent", new Object[]{flag, id, vipFlags}, new Object[]{flag, id});
    }

    private String stringifyResult(Object value) {
        return value == null ? "{}" : String.valueOf(value);
    }

    public ProxyResponse proxy(String sessionId, Map<String, String> params) {
        Map<String, String> requestParams = params == null ? Map.of() : new HashMap<>(params);
        String runtimeKey = resolveProxyRuntimeKey(sessionId, requestParams);
        if (StringUtils.hasText(runtimeKey)) {
            ProxyResponse response = invokeProxy(runtimeKey, requestParams);
            if (response != null) {
                return response;
            }
        }
        return passthroughProxy(requestParams);
    }

    public ProxyResponse proxy(Map<String, String> params) {
        return proxy(null, params);
    }

    private String resolveProxyRuntimeKey(String sessionId, Map<String, String> params) {
        for (String keyName : List.of("site", "source", "sourceKey", "key", "uid", "api")) {
            String value = params.get(keyName);
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String cachedRuntimeKey = siteRuntimeKeyMap.get(siteRuntimeKey(sessionId, value));
            if (StringUtils.hasText(cachedRuntimeKey)) {
                rememberRecentProxyRuntimeKey(sessionId, cachedRuntimeKey);
                return cachedRuntimeKey;
            }

            SiteDefinition site = configService.getSite(sessionId, value).orElse(null);
            if (site != null) {
                getOrCreateSpider(sessionId, site);
                String runtimeKey = siteRuntimeKeyMap.get(siteRuntimeKey(sessionId, value));
                if (StringUtils.hasText(runtimeKey)) {
                    rememberRecentProxyRuntimeKey(sessionId, runtimeKey);
                    return runtimeKey;
                }
            }
        }
        return recentProxyRuntimeKey(sessionId);
    }

    private ProxyResponse invokeProxy(String runtimeKey, Map<String, String> params) {
        Method proxyMethod = proxyMethodMap.get(runtimeKey);
        if (proxyMethod == null) {
            return null;
        }
        try {
            Object value = proxyMethod.invoke(null, params);
            if (!(value instanceof Object[] response) || response.length < 3) {
                return null;
            }

            int status = response[0] instanceof Number number ? number.intValue() : 200;
            String contentType = response[1] instanceof String text && StringUtils.hasText(text)
                    ? text
                    : "application/octet-stream";
            Object payload = response[2];
            InputStream body;
            if (payload instanceof InputStream inputStream) {
                body = inputStream;
            } else if (payload instanceof byte[] bytes) {
                body = new ByteArrayInputStream(bytes);
            } else if (payload instanceof String text) {
                body = new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else {
                body = InputStream.nullInputStream();
            }
            return new ProxyResponse(status, contentType, body);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private ProxyResponse passthroughProxy(Map<String, String> params) {
        String target = firstNonBlank(params.get("url"), params.get("ext"));
        if (!StringUtils.hasText(target)) {
            return null;
        }

        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(target).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "okhttp/3.15");
            connection.connect();

            int status = connection.getResponseCode();
            InputStream inputStream = status >= 200 && status < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String contentType = connection.getContentType();
            if (!StringUtils.hasText(contentType)) {
                contentType = "application/octet-stream";
            }
            return new ProxyResponse(status, contentType, inputStream == null ? InputStream.nullInputStream() : inputStream);
        } catch (Exception ex) {
            throw new IllegalStateException("proxy passthrough 澶辫触: " + ex.getMessage(), ex);
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private Throwable unwrapRootCause(Throwable throwable) {
        if (throwable == null) {
            return new IllegalStateException("unknown");
        }
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    private boolean isNoSuchMethod(Throwable throwable) {
        return throwable instanceof NoSuchMethodException
                || (throwable != null
                && throwable.getMessage() != null
                && throwable.getMessage().contains("鏈壘鍒板吋瀹圭鍚"));
    }

    private String invokeStringAny(Object target, String methodName, Object[]... candidateArgs) {
        Throwable last = null;
        Throwable preferred = null;
        List<String> attempts = new ArrayList<>();
        for (Object[] args : candidateArgs) {
            try {
                return invokeByArgs(target, methodName, args);
            } catch (Throwable ex) {
                last = ex;
                Throwable root = unwrapRootCause(ex);
                attempts.add(args.length + " -> " + root.getClass().getName() + (root.getMessage() == null ? "" : " - " + root.getMessage()));
                if (preferred == null || (isNoSuchMethod(unwrapRootCause(preferred)) && !isNoSuchMethod(root))) {
                    preferred = ex;
                }
            }
        }
        Throwable chosen = preferred != null ? preferred : last;
        Throwable root = unwrapRootCause(chosen);
        String rootType = root == null ? "unknown" : root.getClass().getName();
        String rootMessage = root == null ? "" : root.getMessage();
        throw new IllegalStateException(methodName + " 调用失败: " + rootType + (rootMessage == null ? "" : " - " + rootMessage), last);
    }

    private String invokeByArgs(Object target, String methodName, Object[] args) throws Exception {
        try {
            for (Method method : target.getClass().getMethods()) {
                if (!methodName.equals(method.getName())) {
                    continue;
                }
                if (method.getParameterCount() != args.length) {
                    continue;
                }

                Object[] converted = convertArguments(method.getParameterTypes(), args);
                if (converted == null) {
                    continue;
                }

                Object result = method.invoke(target, converted);
                return result == null ? "{}" : String.valueOf(result);
            }
            throw new NoSuchMethodException("未找到兼容签名: " + methodName + "/" + args.length);
        } catch (Exception ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
    }

    private Object[] convertArguments(Class<?>[] paramTypes, Object[] args) {
        if (paramTypes.length != args.length) {
            return null;
        }
        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object value = convertArgument(paramTypes[i], args[i]);
            if (value == ConversionFailure.INSTANCE) {
                return null;
            }
            converted[i] = value;
        }
        return converted;
    }

    private Object convertArgument(Class<?> targetType, Object value) {
        if (value == null) {
            return targetType.isPrimitive() ? ConversionFailure.INSTANCE : null;
        }

        Class<?> boxedType = boxType(targetType);
        if (boxedType.isInstance(value)) {
            return value;
        }

        if (boxedType == String.class) {
            if (value instanceof List<?> list) {
                return list.isEmpty() ? "" : String.valueOf(list.get(0));
            }
            return String.valueOf(value);
        }

        if (List.class.isAssignableFrom(boxedType)) {
            if (value instanceof List<?>) {
                return value;
            }
            if (value instanceof String text) {
                List<String> list = new ArrayList<>();
                list.add(text);
                return list;
            }
            return ConversionFailure.INSTANCE;
        }

        if (Map.class.isAssignableFrom(boxedType)) {
            if (value instanceof Map<?, ?>) {
                return value;
            }
            return ConversionFailure.INSTANCE;
        }

        if (boxedType == Boolean.class) {
            if (value instanceof Boolean) {
                return value;
            }
            if (value instanceof String text) {
                return Boolean.parseBoolean(text);
            }
            return ConversionFailure.INSTANCE;
        }

        if (Number.class.isAssignableFrom(boxedType) && value instanceof Number num) {
            if (boxedType == Integer.class) {
                return num.intValue();
            }
            if (boxedType == Long.class) {
                return num.longValue();
            }
            if (boxedType == Double.class) {
                return num.doubleValue();
            }
            if (boxedType == Float.class) {
                return num.floatValue();
            }
            if (boxedType == Short.class) {
                return num.shortValue();
            }
            if (boxedType == Byte.class) {
                return num.byteValue();
            }
        }

        return boxedType.isAssignableFrom(value.getClass()) ? value : ConversionFailure.INSTANCE;
    }

    private Class<?> boxType(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private String sanitizePlayableUrl(String input) {
        if (!StringUtils.hasText(input)) {
            return input;
        }
        String url = input.trim();
        Matcher matcher = PLAY_URL_PREFIX_PATTERN.matcher(url);
        if (!matcher.matches()) {
            return url;
        }

        String candidate = matcher.group(1).trim();
        if (startsWithPlayableScheme(candidate)) {
            return candidate;
        }
        return url;
    }

    private JsonNode finalizePlayNode(SiteDefinition site, JsonNode node, String flag, String normalizedId) {
        ObjectNode output = node != null && node.isObject()
                ? ((ObjectNode) node).deepCopy()
                : JsonNodeFactory.instance.objectNode();
        if (!output.has("flag")) {
            output.put("flag", flag);
        }
        if (!output.has("key")) {
            output.put("key", normalizedId);
        }
        if (output.has("url") && output.get("url").isTextual()) {
            output.put("url", sanitizePlayableUrl(output.get("url").asText()));
        }
        if (hasUsablePlayTarget(output)) {
            return output;
        }

        ObjectNode fallback = buildEmbeddedPlayFallback(site, flag, normalizedId);
        return fallback != null ? fallback : output;
    }

    private boolean hasUsablePlayTarget(ObjectNode node) {
        if (node.has("url")
                && node.get("url").isTextual()
                && StringUtils.hasText(node.get("url").asText())
                && startsWithPlayableScheme(node.get("url").asText().trim())) {
            return true;
        }
        return node.has("parse")
                && node.get("parse").isNumber()
                && node.get("parse").asInt() == 1
                && node.has("playUrl")
                && node.get("playUrl").isTextual()
                && StringUtils.hasText(node.get("playUrl").asText());
    }

    private ObjectNode buildEmbeddedPlayFallback(SiteDefinition site, String flag, String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }

        String text = id.trim();
        if (startsWithPlayableScheme(text)) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("parse", 0);
            node.put("playUrl", "");
            node.put("url", sanitizePlayableUrl(text));
            node.put("flag", flag);
            node.put("key", text);
            return node;
        }

        int marker = text.indexOf("qiaoji");
        if (marker < 0) {
            return null;
        }

        String rawUrl = text.substring(0, marker).trim();
        String parserUrl = text.substring(marker + "qiaoji".length()).trim();
        if (parserUrl.endsWith("@end")) {
            parserUrl = parserUrl.substring(0, parserUrl.length() - 4).trim();
        }

        if (!StringUtils.hasText(rawUrl)) {
            return null;
        }

        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("flag", flag);
        node.put("key", text);
        node.put("url", sanitizePlayableUrl(rawUrl));

        if (startsWithPlayableScheme(rawUrl)) {
            node.put("parse", 0);
            node.put("playUrl", "");
            return node;
        }

        if (!StringUtils.hasText(parserUrl) && site != null && StringUtils.hasText(site.getPlayUrl())) {
            parserUrl = site.getPlayUrl().trim();
        }
        if (!StringUtils.hasText(parserUrl)) {
            return null;
        }

        node.put("parse", 1);
        node.put("playUrl", parserUrl);
        return node;
    }

    private static final class SafeFrameClassWriter extends ClassWriter {

        private final ClassLoader resolveLoader;

        private SafeFrameClassWriter(int flags, ClassLoader resolveLoader) {
            super(flags);
            this.resolveLoader = resolveLoader;
        }

        private SafeFrameClassWriter(ClassReader classReader, int flags, ClassLoader resolveLoader) {
            super(classReader, flags);
            this.resolveLoader = resolveLoader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1 == null || type2 == null) {
                return "java/lang/Object";
            }
            if (type1.equals(type2)) {
                return type1;
            }
            try {
                ClassLoader loader = resolveLoader == null ? Thread.currentThread().getContextClassLoader() : resolveLoader;
                Class<?> class1 = Class.forName(type1.replace('/', '.'), false, loader);
                Class<?> class2 = Class.forName(type2.replace('/', '.'), false, loader);
                if (class1.isAssignableFrom(class2)) {
                    return type1;
                }
                if (class2.isAssignableFrom(class1)) {
                    return type2;
                }
                if (class1.isInterface() || class2.isInterface()) {
                    return "java/lang/Object";
                }
                do {
                    class1 = class1.getSuperclass();
                } while (class1 != null && !class1.isAssignableFrom(class2));
                return class1 == null ? "java/lang/Object" : class1.getName().replace('.', '/');
            } catch (Throwable ignore) {
                return "java/lang/Object";
            }
        }
    }

    private boolean startsWithPlayableScheme(String text) {
        String lower = text.toLowerCase();
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("rtmp://")
                || lower.startsWith("rtsp://")
                || lower.startsWith("ftp://")
                || lower.startsWith("magnet:")
                || lower.startsWith("thunder:")
                || lower.startsWith("ed2k://")
                || lower.startsWith("//");
    }

    private enum ConversionFailure {
        INSTANCE
    }

    private JsonNode readJson(String json) {
        try {
            if (!StringUtils.hasText(json)) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("raw", json);
            node.put("error", "返回不是标准 JSON");
            return node;
        }
    }

    public record ProxyResponse(int status, String contentType, InputStream body) {
    }

    private record JarSpec(String url, String expectedMd5, boolean imageWrapped) {
    }

    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            return Integer.toHexString(text.hashCode());
        }
    }
}
