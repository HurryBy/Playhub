package com.github.catvod.crawler;

import com.google.gson.JsonArray;

public class SpiderApi {

    private final String baseAddress;
    private final String port;

    public SpiderApi() {
        this("http://127.0.0.1:18080/", "18080");
    }

    public SpiderApi(String baseAddress, String port) {
        String normalizedBase = baseAddress == null ? "http://127.0.0.1:18080/" : baseAddress.trim();
        if (!normalizedBase.endsWith("/")) {
            normalizedBase = normalizedBase + "/";
        }
        this.baseAddress = normalizedBase;
        this.port = (port == null || port.isBlank()) ? "18080" : port.trim();
    }

    public void log(String msg) {
        if (msg != null && !msg.isBlank()) {
            System.err.println("[SpiderApi] " + msg);
        }
    }

    public String multiReq(JsonArray reqArray) {
        return null;
    }

    public String webParse(String from, String url) {
        return url == null ? "" : url;
    }

    public String getAddress(boolean local) {
        return baseAddress;
    }

    public String getPort() {
        return port;
    }
}
