package com.tvbox.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tvbox.web.model.SiteDefinition;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Service
public class HttpSourceService {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpSourceService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public JsonNode request(SiteDefinition site, Map<String, String> params) {
        if (!StringUtils.hasText(site.getApi())) {
            throw new IllegalArgumentException("站点 api 为空");
        }
        URI uri = buildUri(site.getApi(), params);
        String body = doGet(uri);
        try {
            return objectMapper.readTree(body);
        } catch (Exception ex) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("raw", body);
            node.put("format", body != null && body.trim().startsWith("<") ? "xml" : "text");
            return node;
        }
    }

    private URI buildUri(String api, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(api);
        if (params != null) {
            params.forEach((k, v) -> {
                if (StringUtils.hasText(k) && v != null) {
                    builder.queryParam(k, v);
                }
            });
        }
        return builder.build().encode().toUri();
    }

    private String doGet(URI uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", "okhttp/3.15")
                    .header("Accept", "application/json,text/plain,*/*")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            throw new IllegalStateException("远端请求失败, status=" + response.statusCode());
        } catch (Exception ex) {
            throw new IllegalStateException("远端请求失败: " + ex.getMessage(), ex);
        }
    }
}
