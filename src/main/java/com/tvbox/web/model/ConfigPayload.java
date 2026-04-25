package com.tvbox.web.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfigPayload {
    private String spider;
    private String wallpaper;
    private List<SiteDefinition> sites = new ArrayList<>();
    private List<ParseDefinition> parses = new ArrayList<>();
    private List<String> flags = new ArrayList<>();
    private List<JsonNode> lives = new ArrayList<>();
    private List<JsonNode> rules = new ArrayList<>();

    public String getSpider() {
        return spider;
    }

    public void setSpider(String spider) {
        this.spider = spider;
    }

    public String getWallpaper() {
        return wallpaper;
    }

    public void setWallpaper(String wallpaper) {
        this.wallpaper = wallpaper;
    }

    public List<SiteDefinition> getSites() {
        return sites;
    }

    public void setSites(List<SiteDefinition> sites) {
        this.sites = sites;
    }

    public List<ParseDefinition> getParses() {
        return parses;
    }

    public void setParses(List<ParseDefinition> parses) {
        this.parses = parses;
    }

    public List<String> getFlags() {
        return flags;
    }

    public void setFlags(List<String> flags) {
        this.flags = flags;
    }

    public List<JsonNode> getLives() {
        return lives;
    }

    public void setLives(List<JsonNode> lives) {
        this.lives = lives;
    }

    public List<JsonNode> getRules() {
        return rules;
    }

    public void setRules(List<JsonNode> rules) {
        this.rules = rules;
    }
}
