package com.tvbox.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tvbox.web.model.ConfigPayload;
import com.tvbox.web.model.request.CategoryRequest;
import com.tvbox.web.model.request.LoadConfigRequest;
import com.tvbox.web.service.TvboxFacadeService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Validated
public class TvboxController {

    private final TvboxFacadeService facadeService;

    public TvboxController(TvboxFacadeService facadeService) {
        this.facadeService = facadeService;
    }

    @PostMapping("/config/load")
    public Map<String, Object> loadConfig(HttpSession session,
                                          @Valid @RequestBody LoadConfigRequest request) {
        String sessionId = session.getId();
        ConfigPayload payload = facadeService.load(sessionId, request.getUrl());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ok", true);
        data.put("summary", facadeService.health(sessionId));
        data.put("config", payload);
        return data;
    }

    @GetMapping("/config")
    public ConfigPayload getConfig(HttpSession session) {
        return facadeService.getConfig(session.getId());
    }

    @GetMapping("/health")
    public Map<String, Object> health(HttpSession session) {
        return facadeService.health(session.getId());
    }

    @GetMapping("/source/{key}/home")
    public JsonNode home(HttpSession session,
                         @PathVariable String key,
                         @RequestParam(defaultValue = "true") boolean filter) {
        return facadeService.home(session.getId(), key, filter);
    }

    @PostMapping("/source/{key}/category")
    public JsonNode category(HttpSession session,
                             @PathVariable String key,
                             @RequestBody(required = false) CategoryRequest request) {
        CategoryRequest req = request == null ? new CategoryRequest() : request;
        return facadeService.category(session.getId(), key, req.getTid(), req.getPg(), req.isFilter(), req.getExtend());
    }

    @GetMapping("/source/{key}/detail")
    public JsonNode detail(HttpSession session,
                           @PathVariable String key,
                           @RequestParam String id) {
        return facadeService.detail(session.getId(), key, id);
    }

    @GetMapping("/source/{key}/search")
    public JsonNode search(HttpSession session,
                           @PathVariable String key,
                           @RequestParam String wd,
                           @RequestParam(defaultValue = "false") boolean quick) {
        return facadeService.search(session.getId(), key, wd, quick);
    }

    @GetMapping("/search/all")
    public JsonNode searchAll(HttpSession session,
                              @RequestParam String wd,
                              @RequestParam(defaultValue = "false") boolean quick) {
        return facadeService.searchAll(session.getId(), wd, quick);
    }

    @GetMapping("/source/{key}/play")
    public JsonNode play(HttpSession session,
                         @PathVariable String key,
                         @RequestParam String flag,
                         @RequestParam String id) {
        return facadeService.play(session.getId(), key, flag, id);
    }
}
