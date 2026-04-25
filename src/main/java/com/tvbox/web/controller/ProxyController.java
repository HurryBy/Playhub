package com.tvbox.web.controller;

import com.tvbox.web.service.JarSpiderService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.util.Map;

@RestController
public class ProxyController {

    private final JarSpiderService jarSpiderService;

    public ProxyController(JarSpiderService jarSpiderService) {
        this.jarSpiderService = jarSpiderService;
    }

    @GetMapping("/proxy")
    public ResponseEntity<StreamingResponseBody> proxy(HttpSession session,
                                                       @RequestParam Map<String, String> params) {
        JarSpiderService.ProxyResponse response = jarSpiderService.proxy(session.getId(), params);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }

        StreamingResponseBody body = outputStream -> {
            try (InputStream inputStream = response.body()) {
                if (inputStream != null) {
                    inputStream.transferTo(outputStream);
                }
            }
        };

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_TYPE, response.contentType());
        return ResponseEntity.status(response.status())
                .headers(headers)
                .body(body);
    }
}
