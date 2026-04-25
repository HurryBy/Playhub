package com.tvbox.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvbox.web.model.ConfigPayload;
import com.tvbox.web.model.SiteDefinition;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ConfigService {

    private static final Pattern WRAPPED_BASE64_PATTERN = Pattern.compile("[A-Za-z0-9]{8}\\*\\*");
    private static final String PK_SEPARATOR = ";pk;";
    private static final String CLAN_PREFIX = "clan://";
    private static final String GLOBAL_SESSION_KEY = "__global__";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AppPathsService appPathsService;
    private final Map<String, ConfigSessionState> sessionStateMap = new ConcurrentHashMap<>();

    private volatile Path cacheDir;

    @Value("${app.cache-dir:.cache/tvbox}")
    private String cacheDirConfig;

    public ConfigService(ObjectMapper objectMapper, AppPathsService appPathsService) {
        this.objectMapper = objectMapper;
        this.appPathsService = appPathsService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @PostConstruct
    public void init() throws IOException {
        Path configured = Paths.get(cacheDirConfig);
        this.cacheDir = configured.isAbsolute()
                ? configured.normalize()
                : appPathsService.resolveFromBase(cacheDirConfig);
        Files.createDirectories(cacheDir);
    }

    public synchronized ConfigPayload loadConfig(String rawUrl) {
        return loadConfig(null, rawUrl);
    }

    public synchronized ConfigPayload loadConfig(String sessionId, String rawUrl) {
        ResolvedConfigTarget target = resolveConfigTarget(rawUrl);
        String text = fetchText(target.fetchUrl());
        text = decodeWrappedIfNeeded(text, target.configKey());
        text = fixContentPath(target.fetchUrl(), text);
        try {
            ConfigPayload parsed = objectMapper.readValue(text, ConfigPayload.class);
            ConfigSessionState state = stateFor(sessionId);
            state.siteMap.clear();
            if (parsed.getSites() != null) {
                int index = 0;
                for (SiteDefinition site : parsed.getSites()) {
                    String uid = "s" + index++;
                    site.setUid(uid);
                    state.siteMap.put(uid, site);

                    if (StringUtils.hasText(site.getKey()) && !state.siteMap.containsKey(site.getKey())) {
                        state.siteMap.put(site.getKey(), site);
                    }
                    if (StringUtils.hasText(site.getApi()) && !state.siteMap.containsKey(site.getApi())) {
                        state.siteMap.put(site.getApi(), site);
                    }
                    if (StringUtils.hasText(site.getName()) && !state.siteMap.containsKey(site.getName())) {
                        state.siteMap.put(site.getName(), site);
                    }
                }
            }
            state.payload = parsed;
            state.configUrl = target.originalUrl();
            return parsed;
        } catch (Exception ex) {
            throw new IllegalStateException("配置解析失败: " + ex.getMessage(), ex);
        }
    }

    public ConfigPayload getPayload() {
        return getPayload(null);
    }

    public ConfigPayload getPayload(String sessionId) {
        return stateFor(sessionId).payload;
    }

    public String getConfigUrl() {
        return getConfigUrl(null);
    }

    public String getConfigUrl(String sessionId) {
        return stateFor(sessionId).configUrl;
    }

    public Optional<SiteDefinition> getSite(String key) {
        return getSite(null, key);
    }

    public Optional<SiteDefinition> getSite(String sessionId, String key) {
        return Optional.ofNullable(stateFor(sessionId).siteMap.get(key));
    }

    public Path getCacheDir() {
        return cacheDir;
    }

    public Map<String, Object> summary() {
        return summary(null);
    }

    public Map<String, Object> summary(String sessionId) {
        ConfigSessionState current = stateFor(sessionId);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("loaded", current.payload != null);
        res.put("url", current.configUrl);
        if (current.payload == null) {
            res.put("sites", 0);
            res.put("parses", 0);
            res.put("flags", 0);
            return res;
        }
        res.put("sites", current.payload.getSites() == null ? 0 : current.payload.getSites().size());
        res.put("parses", current.payload.getParses() == null ? 0 : current.payload.getParses().size());
        res.put("flags", current.payload.getFlags() == null ? 0 : current.payload.getFlags().size());
        return res;
    }

    private ConfigSessionState stateFor(String sessionId) {
        return sessionStateMap.computeIfAbsent(normalizeSessionId(sessionId), key -> new ConfigSessionState());
    }

    private String normalizeSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId.trim() : GLOBAL_SESSION_KEY;
    }

    private String fetchText(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "okhttp/3.15")
                    .header("Accept", "application/json,text/plain,*/*")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            throw new IllegalStateException("拉取配置失败: status=" + response.statusCode());
        } catch (Exception ex) {
            throw new IllegalStateException("拉取配置失败: " + ex.getMessage(), ex);
        }
    }

    private ResolvedConfigTarget resolveConfigTarget(String rawUrl) {
        if (!StringUtils.hasText(rawUrl)) {
            throw new IllegalArgumentException("配置地址不能为空");
        }
        String original = rawUrl.trim();
        String configUrl = original;
        String configKey = null;

        if (configUrl.contains(PK_SEPARATOR)) {
            String[] parts = configUrl.split(Pattern.quote(PK_SEPARATOR), 2);
            configUrl = parts[0];
            configKey = parts.length > 1 ? parts[1].trim() : null;
        }

        String fetchUrl = normalizeHttpLikeUrl(configUrl);
        if (fetchUrl.startsWith(CLAN_PREFIX)) {
            fetchUrl = clanToAddress(fetchUrl);
        }
        return new ResolvedConfigTarget(original, fetchUrl, configKey);
    }

    private String normalizeHttpLikeUrl(String rawUrl) {
        String url = rawUrl.trim();
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith(CLAN_PREFIX)) {
            return url;
        }
        return "http://" + url;
    }

    private String decodeWrappedIfNeeded(String content, String configKey) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (isJson(trimmed)) {
            return trimmed;
        }

        Matcher matcher = WRAPPED_BASE64_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            String encoded = trimmed.substring(trimmed.indexOf(matcher.group()) + 10);
            try {
                String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
                if (isJson(decoded)) {
                    return decoded;
                }
                trimmed = decoded.trim();
            } catch (Exception ignore) {
                return trimmed;
            }
        }

        try {
            if (trimmed.startsWith("2423")) {
                String data = trimmed.substring(trimmed.indexOf("2324") + 4, trimmed.length() - 26);
                String lowerHex = new String(toBytes(trimmed), StandardCharsets.UTF_8).toLowerCase();
                String key = rightPad(extractBetween(lowerHex, "$#", "#$"), '0', 16);
                String iv = rightPad(lowerHex.substring(Math.max(0, lowerHex.length() - 13)), '0', 16);
                String decoded = decrypt("AES/CBC/PKCS5Padding", data, key, iv);
                if (isJson(decoded)) {
                    return decoded;
                }
            } else if (StringUtils.hasText(configKey)) {
                String decoded = decrypt("AES/ECB/PKCS5Padding", trimmed, rightPad(configKey, '0', 16), null);
                if (isJson(decoded)) {
                    return decoded;
                }
            }
        } catch (Exception ignore) {
        }

        return trimmed;
    }

    private String fixContentPath(String url, String content) {
        if (content == null) {
            return "";
        }
        if (url.startsWith(CLAN_PREFIX)) {
            content = clanContentFix(clanToAddress(url), content);
        }
        if (!content.contains("\"./")) {
            return content;
        }
        String base = url.substring(0, url.lastIndexOf('/') + 1);
        return content.replace("./", base);
    }

    private String clanToAddress(String clanUrl) {
        if (clanUrl.startsWith("clan://localhost/")) {
            return clanUrl.replace("clan://localhost/", "http://127.0.0.1:9978/file/");
        }
        String link = clanUrl.substring(CLAN_PREFIX.length());
        int end = link.indexOf('/');
        if (end < 0) {
            throw new IllegalArgumentException("无效的 clan 配置地址: " + clanUrl);
        }
        return "http://" + link.substring(0, end) + "/file/" + link.substring(end + 1);
    }

    private String clanContentFix(String resolvedClanUrl, String content) {
        int fileIndex = resolvedClanUrl.indexOf("/file/");
        if (fileIndex < 0) {
            return content;
        }
        String prefix = resolvedClanUrl.substring(0, fileIndex + 6);
        return content.replace(CLAN_PREFIX, prefix);
    }

    private boolean isJson(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        try {
            objectMapper.readTree(value);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    private String extractBetween(String content, String left, String right) {
        int leftIndex = content.indexOf(left);
        int rightIndex = content.indexOf(right);
        if (leftIndex < 0 || rightIndex < 0 || rightIndex <= leftIndex + left.length()) {
            return "";
        }
        return content.substring(leftIndex + left.length(), rightIndex);
    }

    private String rightPad(String text, char ch, int length) {
        String value = text == null ? "" : text.trim();
        if (value.length() >= length) {
            return value.substring(0, length);
        }
        StringBuilder builder = new StringBuilder(value);
        while (builder.length() < length) {
            builder.append(ch);
        }
        return builder.toString();
    }

    private byte[] toBytes(String src) {
        int len = src.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(src.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private String decrypt(String transformation, String data, String key, String iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(transformation);
        SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
        if (iv == null) {
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
        } else {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
        }
        return new String(cipher.doFinal(toBytes(data)), StandardCharsets.UTF_8);
    }

    private record ResolvedConfigTarget(String originalUrl, String fetchUrl, String configKey) {
    }

    private static final class ConfigSessionState {
        private final Map<String, SiteDefinition> siteMap = new ConcurrentHashMap<>();
        private volatile ConfigPayload payload;
        private volatile String configUrl;
    }
}
