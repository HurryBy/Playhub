package com.tvbox.web.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ParseDefinition {
    private String name;
    private String url;
    private int type;
    private JsonNode ext;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public JsonNode getExt() {
        return ext;
    }

    public void setExt(JsonNode ext) {
        this.ext = ext;
    }
}
