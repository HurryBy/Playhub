package com.tvbox.web.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/mock")
public class MockSiteController {

    @GetMapping("/api")
    public Map<String, Object> api(@RequestParam(required = false) String ac,
                                   @RequestParam(required = false) String t,
                                   @RequestParam(required = false) String pg,
                                   @RequestParam(required = false) String ids,
                                   @RequestParam(required = false) String wd,
                                   @RequestParam(required = false) String play,
                                   @RequestParam(required = false) String flag,
                                   @RequestParam(required = false) String filter,
                                   @RequestParam(required = false) String quick) {
        if (play != null) {
            return play(play, flag);
        }
        if (wd != null) {
            return search(wd);
        }
        if (ids != null) {
            return detail(ids);
        }
        if ("true".equalsIgnoreCase(filter) || ac == null) {
            return home();
        }
        return category(t, pg);
    }

    private Map<String, Object> home() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> classes = new ArrayList<>();
        classes.add(cls("1", "电影"));
        classes.add(cls("2", "剧集"));
        classes.add(cls("3", "综艺"));
        data.put("class", classes);
        data.put("list", sampleList("home"));
        return data;
    }

    private Map<String, Object> category(String typeId, String page) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", page == null ? "1" : page);
        data.put("pagecount", 3);
        data.put("limit", 20);
        data.put("total", 60);
        data.put("list", sampleList(typeId == null ? "1" : typeId));
        return data;
    }

    private Map<String, Object> search(String wd) {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> list = sampleList("search");
        for (Map<String, Object> one : list) {
            one.put("vod_name", one.get("vod_name") + " · " + wd);
        }
        data.put("list", list);
        return data;
    }

    private Map<String, Object> detail(String id) {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> vod = baseVod(id, "示例影片 " + id);
        vod.put("vod_content", "这是用于联调的模拟详情数据，可验证详情与选集解析流程。");
        vod.put("vod_play_from", "线路A$$$线路B");
        vod.put("vod_play_url", "第1集$ep1#第2集$ep2#第3集$ep3$$$超清$ep_hd");
        list.add(vod);
        data.put("list", list);
        return data;
    }

    private Map<String, Object> play(String id, String flag) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("parse", 0);
        data.put("flag", flag == null ? "线路A" : flag);
        data.put("url", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8");
        data.put("header", new LinkedHashMap<>());
        data.put("key", id);
        return data;
    }

    private List<Map<String, Object>> sampleList(String bucket) {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(baseVod(bucket + "-001", "星际远航"));
        list.add(baseVod(bucket + "-002", "风起长歌"));
        list.add(baseVod(bucket + "-003", "极速追踪"));
        return list;
    }

    private Map<String, Object> baseVod(String id, String name) {
        Map<String, Object> vod = new LinkedHashMap<>();
        vod.put("vod_id", id);
        vod.put("vod_name", name);
        vod.put("vod_pic", "https://dummyimage.com/400x560/26335f/ffffff&text=" + name);
        vod.put("vod_remarks", "更新中");
        vod.put("vod_year", "2026");
        vod.put("vod_area", "中国");
        vod.put("type_name", "剧情");
        return vod;
    }

    private Map<String, Object> cls(String id, String name) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type_id", id);
        data.put("type_name", name);
        return data;
    }
}