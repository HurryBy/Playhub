package android.net;

import java.util.LinkedHashMap;
import java.util.Map;

public class UrlQuerySanitizer {

    private final Map<String, String> values = new LinkedHashMap<>();

    public void setAllowUnregisteredParamaters(boolean allow) {
    }

    public void parseUrl(String url) {
        values.clear();
        int queryIndex = url.indexOf('?');
        if (queryIndex < 0 || queryIndex >= url.length() - 1) {
            return;
        }
        for (String part : url.substring(queryIndex + 1).split("&")) {
            if (part.isBlank()) {
                continue;
            }
            String[] pair = part.split("=", 2);
            values.put(pair[0], pair.length > 1 ? pair[1] : "");
        }
    }

    public String getValue(String key) {
        return values.get(key);
    }
}
