package android.net;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class Uri {

    private final String raw;
    private final Map<String, String> queryParameters;
    private final java.net.URI javaUri;

    private Uri(String raw) {
        this.raw = raw;
        this.queryParameters = parseQuery(raw);
        this.javaUri = parseUri(raw);
    }

    public static Uri parse(String raw) {
        return new Uri(raw);
    }

    public String getQueryParameter(String key) {
        return queryParameters.get(key);
    }

    public String getScheme() {
        return javaUri == null ? null : javaUri.getScheme();
    }

    public String getHost() {
        return javaUri == null ? null : javaUri.getHost();
    }

    public String getPath() {
        return javaUri == null ? null : javaUri.getPath();
    }

    @Override
    public String toString() {
        return raw;
    }

    private java.net.URI parseUri(String value) {
        try {
            return java.net.URI.create(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, String> parseQuery(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        int queryIndex = raw.indexOf('?');
        if (queryIndex < 0 || queryIndex >= raw.length() - 1) {
            return values;
        }
        String query = raw.substring(queryIndex + 1);
        for (String part : query.split("&")) {
            if (part.isBlank()) {
                continue;
            }
            String[] pair = part.split("=", 2);
            String name = decode(pair[0]);
            String value = pair.length > 1 ? decode(pair[1]) : "";
            values.put(name, value);
        }
        return values;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
