package com.tvbox.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tvbox.web.model.ConfigPayload;
import com.tvbox.web.model.SiteDefinition;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TvboxFacadeService {

    private static final Pattern PLAY_URL_PREFIX_PATTERN = Pattern.compile("^[^,，]{1,30}[,，](.+)$");

    private final ConfigService configService;
    private final HttpSourceService httpSourceService;
    private final JarSpiderService jarSpiderService;

    public TvboxFacadeService(ConfigService configService,
                              HttpSourceService httpSourceService,
                              JarSpiderService jarSpiderService) {
        this.configService = configService;
        this.httpSourceService = httpSourceService;
        this.jarSpiderService = jarSpiderService;
    }

    public ConfigPayload load(String url) {
        return load(null, url);
    }

    public ConfigPayload load(String sessionId, String url) {
        return configService.loadConfig(sessionId, url);
    }

    public ConfigPayload getConfig() {
        return getConfig(null);
    }

    public ConfigPayload getConfig(String sessionId) {
        ConfigPayload payload = configService.getPayload(sessionId);
        if (payload == null) {
            throw new IllegalStateException("尚未加载配置，请先输入配置 URL");
        }
        return payload;
    }

    public SiteDefinition getSite(String key) {
        return getSite(null, key);
    }

    public SiteDefinition getSite(String sessionId, String key) {
        return configService.getSite(sessionId, key)
                .or(() -> getConfig(sessionId).getSites().stream()
                        .filter(site -> key.equals(site.getUid())
                                || key.equals(site.getKey())
                                || key.equals(site.getApi())
                                || key.equals(site.getName())
                                || key.equalsIgnoreCase(site.getApi()))
                        .findFirst())
                .orElseThrow(() -> new IllegalArgumentException("找不到站点: " + key));
    }

    public JsonNode home(String key, boolean filter) {
        return home(null, key, filter);
    }

    public JsonNode home(String sessionId, String key, boolean filter) {
        SiteDefinition site = getSite(sessionId, key);
        if (site.getType() == 3) {
            JsonNode home;
            try {
                home = jarSpiderService.home(sessionId, site, filter);
            } catch (Throwable ex) {
                home = JsonNodeFactory.instance.objectNode();
            }
            if (isHomeUsable(home)) {
                return home;
            }
            ObjectNode fallbackHome = JsonNodeFactory.instance.objectNode();
            fallbackHome.set("class", defaultClasses());
            for (String tid : List.of("1", "2", "3", "4", "5", "6")) {
                try {
                    JsonNode category = jarSpiderService.category(sessionId, site, tid, "1", true, Map.of());
                    List<JsonNode> list = extractList(category);
                    if (!list.isEmpty()) {
                        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
                        for (JsonNode item : list) {
                            arr.add(item);
                        }
                        fallbackHome.set("list", arr);
                        return fallbackHome;
                    }
                } catch (Throwable ignore) {
                }
            }
            fallbackHome.set("list", JsonNodeFactory.instance.arrayNode());
            return fallbackHome;
        }
        if (site.getType() == 4) {
            return httpSourceService.request(site, mapOf("ac", "detail", "filter", String.valueOf(filter)));
        }
        return httpSourceService.request(site, new LinkedHashMap<>());
    }

    public JsonNode category(String key, String tid, String pg, boolean filter, Map<String, String> extend) {
        return category(null, key, tid, pg, filter, extend);
    }

    public JsonNode category(String sessionId, String key, String tid, String pg, boolean filter, Map<String, String> extend) {
        SiteDefinition site = getSite(sessionId, key);
        if (site.getType() == 3) {
            return jarSpiderService.category(sessionId, site, tid, pg, filter, extend);
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("ac", site.getType() == 0 ? "videolist" : "detail");
        params.put("t", tid);
        params.put("pg", StringUtils.hasText(pg) ? pg : "1");
        if (extend != null) {
            params.putAll(extend);
        }
        return httpSourceService.request(site, params);
    }

    public JsonNode detail(String key, String id) {
        return detail(null, key, id);
    }

    public JsonNode detail(String sessionId, String key, String id) {
        SiteDefinition site = getSite(sessionId, key);
        if (site.getType() == 3) {
            return jarSpiderService.detail(sessionId, site, id);
        }
        return httpSourceService.request(site, mapOf("ac", site.getType() == 0 ? "videolist" : "detail", "ids", id));
    }

    public JsonNode search(String key, String wd, boolean quick) {
        return search(null, key, wd, quick);
    }

    public JsonNode search(String sessionId, String key, String wd, boolean quick) {
        SiteDefinition site = getSite(sessionId, key);
        if (site.getType() == 3) {
            return jarSpiderService.search(sessionId, site, wd, quick);
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("wd", wd);
        params.put("ac", "detail");
        if (site.getType() == 4) {
            params.put("quick", String.valueOf(quick));
        }
        return httpSourceService.request(site, params);
    }

    public JsonNode searchAll(String wd, boolean quick) {
        return searchAll(null, wd, quick);
    }

    public JsonNode searchAll(String sessionId, String wd, boolean quick) {
        ConfigPayload payload = getConfig(sessionId);
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode list = root.putArray("list");
        ArrayNode errors = root.putArray("errors");

        int searched = 0;
        for (SiteDefinition site : payload.getSites()) {
            if (site == null || site.getSearchable() == 0) {
                continue;
            }
            searched++;
            try {
                JsonNode response = search(sessionId, site.getUid(), wd, quick);
                List<JsonNode> items = extractList(response);
                for (JsonNode item : items) {
                    ObjectNode row;
                    if (item != null && item.isObject()) {
                        row = ((ObjectNode) item).deepCopy();
                    } else {
                        row = JsonNodeFactory.instance.objectNode();
                        row.set("raw", item);
                    }
                    row.put("source_uid", site.getUid());
                    row.put("source_key", site.getApi());
                    row.put("source_name", site.getName());
                    row.put("source_type", site.getType());
                    list.add(row);
                }
            } catch (Throwable ex) {
                ObjectNode err = JsonNodeFactory.instance.objectNode();
                err.put("source_uid", site.getUid());
                err.put("source_key", site.getApi());
                err.put("source_name", site.getName());
                err.put("error", ex.getMessage() == null ? "unknown" : ex.getMessage());
                errors.add(err);
            }
        }

        root.put("searched", searched);
        root.put("hits", list.size());
        root.put("failed", errors.size());
        return root;
    }

    public JsonNode play(String key, String flag, String id) {
        return play(null, key, flag, id);
    }

    public JsonNode play(String sessionId, String key, String flag, String id) {
        SiteDefinition site = getSite(sessionId, key);
        String normalizedId = sanitizePlayableUrl(id);
        if (site.getType() == 3) {
            List<String> vipFlags = getConfig(sessionId).getFlags();
            return jarSpiderService.play(sessionId, site, flag, normalizedId, vipFlags);
        }
        JsonNode response = httpSourceService.request(site, mapOf("play", normalizedId, "flag", flag));
        ObjectNode node;
        if (response.isObject()) {
            node = (ObjectNode) response;
        } else {
            node = JsonNodeFactory.instance.objectNode();
            node.set("data", response);
        }
        if (!node.has("flag")) {
            node.put("flag", flag);
        }
        if (!node.has("key")) {
            node.put("key", normalizedId);
        }
        if (!node.has("url")) {
            node.put("url", normalizedId);
        }
        if (!node.has("parse")) {
            node.put("parse", 1);
        }
        if (!node.has("playUrl")) {
            node.put("playUrl", site.getPlayUrl() == null ? "" : site.getPlayUrl());
        }
        if (node.has("url") && node.get("url").isTextual()) {
            node.put("url", sanitizePlayableUrl(node.get("url").asText()));
        }
        return node;
    }

    public Map<String, Object> health() {
        return health(null);
    }

    public Map<String, Object> health(String sessionId) {
        return configService.summary(sessionId);
    }

    private Map<String, String> mapOf(String... values) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length - 1; i += 2) {
            map.put(values[i], values[i + 1]);
        }
        return map;
    }

    private List<JsonNode> extractList(JsonNode data) {
        if (data == null) {
            return List.of();
        }
        if (data.has("list") && data.get("list").isArray()) {
            return java.util.stream.StreamSupport.stream(data.get("list").spliterator(), false).toList();
        }
        if (data.has("data") && data.get("data").has("list") && data.get("data").get("list").isArray()) {
            return java.util.stream.StreamSupport.stream(data.get("data").get("list").spliterator(), false).toList();
        }
        if (data.has("videoList") && data.get("videoList").isArray()) {
            return java.util.stream.StreamSupport.stream(data.get("videoList").spliterator(), false).toList();
        }
        return List.of();
    }

    private boolean isHomeUsable(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        boolean hasClass = node.has("class") && node.get("class").isArray() && node.get("class").size() > 0;
        boolean hasList = node.has("list") && node.get("list").isArray() && node.get("list").size() > 0;
        return hasClass || hasList;
    }

    private ArrayNode defaultClasses() {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        arr.add(classNode("1", "电影"));
        arr.add(classNode("2", "剧集"));
        arr.add(classNode("3", "综艺"));
        arr.add(classNode("4", "动漫"));
        return arr;
    }

    private ObjectNode classNode(String id, String name) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("type_id", id);
        node.put("type_name", name);
        return node;
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
}
