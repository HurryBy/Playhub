package com.tvbox.web.controller;

import com.tvbox.web.model.request.LiveBootstrapRequest;
import com.tvbox.web.service.LiveSourceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/live")
public class LiveController {

    private final LiveSourceService liveSourceService;

    public LiveController(LiveSourceService liveSourceService) {
        this.liveSourceService = liveSourceService;
    }

    @PostMapping("/bootstrap")
    public Map<String, Object> bootstrap(@Valid @RequestBody LiveBootstrapRequest request) {
        return liveSourceService.bootstrap(request.getPlaylistUrl(), request.getEpgUrl());
    }
}
