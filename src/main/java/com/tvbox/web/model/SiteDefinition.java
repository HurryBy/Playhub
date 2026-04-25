package com.tvbox.web.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SiteDefinition {
    private String uid;
    private String key;
    private String name;
    private int type;
    private String api;
    private int searchable = 1;
    private int quickSearch = 1;
    private int filterable = 1;
    private String playUrl = "";
    private JsonNode ext;
    private String jar = "";
    private int playerType = -1;
    @JsonProperty("click")
    private String clickSelector = "";
    private List<String> categories = new ArrayList<>();

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public String getApi() {
        return api;
    }

    public void setApi(String api) {
        this.api = api;
    }

    public int getSearchable() {
        return searchable;
    }

    public void setSearchable(int searchable) {
        this.searchable = searchable;
    }

    public int getQuickSearch() {
        return quickSearch;
    }

    public void setQuickSearch(int quickSearch) {
        this.quickSearch = quickSearch;
    }

    public int getFilterable() {
        return filterable;
    }

    public void setFilterable(int filterable) {
        this.filterable = filterable;
    }

    public String getPlayUrl() {
        return playUrl;
    }

    public void setPlayUrl(String playUrl) {
        this.playUrl = playUrl;
    }

    public JsonNode getExt() {
        return ext;
    }

    public void setExt(JsonNode ext) {
        this.ext = ext;
    }

    public String getExtText() {
        if (ext == null || ext.isNull()) {
            return "";
        }
        return ext.isTextual() ? ext.asText() : ext.toString();
    }

    public String getJar() {
        return jar;
    }

    public void setJar(String jar) {
        this.jar = jar;
    }

    public int getPlayerType() {
        return playerType;
    }

    public void setPlayerType(int playerType) {
        this.playerType = playerType;
    }

    public String getClickSelector() {
        return clickSelector;
    }

    public void setClickSelector(String clickSelector) {
        this.clickSelector = clickSelector;
    }

    public List<String> getCategories() {
        return categories;
    }

    public void setCategories(List<String> categories) {
        this.categories = categories;
    }
}
