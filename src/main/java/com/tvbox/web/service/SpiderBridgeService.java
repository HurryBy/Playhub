package com.tvbox.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tvbox.web.model.SiteDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SpiderBridgeService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${app.spider-bridge-url:}")
    private String bridgeUrl;

    public SpiderBridgeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean enabled() {
        return StringUtils.hasText(bridgeUrl);
    }

    public JsonNode home(SiteDefinition site, boolean filter) {
        return call("home", site, mapOf("filter", filter));
    }

    public JsonNode category(SiteDefinition site, String tid, String pg, boolean filter, Map<String, String> extend) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tid", tid);
        body.put("pg", pg);
        body.put("filter", filter);
        body.put("extend", extend == null ? new LinkedHashMap<>() : extend);
        return call("category", site, body);
    }

    public JsonNode detail(SiteDefinition site, String id) {
        return call("detail", site, mapOf("id", id));
    }

    public JsonNode search(SiteDefinition site, String wd, boolean quick) {
        return call("search", site, mapOf("wd", wd, "quick", quick));
    }

    public JsonNode play(SiteDefinition site, String flag, String id, List<String> vipFlags) {
        return call("play", site, mapOf("flag", flag, "id", id, "vipFlags", vipFlags));
    }

    private JsonNode call(String action, SiteDefinition site, Map<String, Object> payload) {
        if (!enabled()) {
            throw new IllegalStateException("Spider Bridge 未配置，无法回退执行");
        }
        String base = bridgeUrl.endsWith("/") ? bridgeUrl.substring(0, bridgeUrl.length() - 1) : bridgeUrl;
        String url = base + "/spider/" + action;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("site", site);
        body.putAll(payload);

        try {
            String requestBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "tvbox-web-bridge/1.0")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Bridge 请求失败, status=" + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (Exception ex) {
            throw new IllegalStateException("Bridge 调用失败: " + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> mapOf(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length - 1; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }
}
