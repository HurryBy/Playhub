package com.tvbox.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tvbox.web.model.SiteDefinition;
import com.tvbox.web.model.request.SpiderGatewayRequest;
import com.tvbox.web.service.JarSpiderService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

@RestController
@RequestMapping("/spider")
public class SpiderGatewayController {

    private final JarSpiderService jarSpiderService;

    public SpiderGatewayController(JarSpiderService jarSpiderService) {
        this.jarSpiderService = jarSpiderService;
    }

    @PostMapping("/home")
    public JsonNode home(@RequestBody SpiderGatewayRequest request) {
        SiteDefinition site = requireSite(request);
        return jarSpiderService.homeLocal(site, request.isFilter());
    }

    @PostMapping("/category")
    public JsonNode category(@RequestBody SpiderGatewayRequest request) {
        SiteDefinition site = requireSite(request);
        return jarSpiderService.categoryLocal(site, request.getTid(), request.getPg(), request.isFilter(), request.getExtend());
    }

    @PostMapping("/detail")
    public JsonNode detail(@RequestBody SpiderGatewayRequest request) {
        SiteDefinition site = requireSite(request);
        return jarSpiderService.detailLocal(site, request.getId());
    }

    @PostMapping("/search")
    public JsonNode search(@RequestBody SpiderGatewayRequest request) {
        SiteDefinition site = requireSite(request);
        return jarSpiderService.searchLocal(site, request.getWd(), request.isQuick());
    }

    @PostMapping("/play")
    public JsonNode play(@RequestBody SpiderGatewayRequest request) {
        SiteDefinition site = requireSite(request);
        return jarSpiderService.playLocal(site,
                request.getFlag(),
                request.getId(),
                request.getVipFlags() == null ? Collections.emptyList() : request.getVipFlags());
    }

    private SiteDefinition requireSite(SpiderGatewayRequest request) {
        if (request == null || request.getSite() == null) {
            throw new IllegalArgumentException("spider gateway 请求缺少 site");
        }
        return request.getSite();
    }
}
