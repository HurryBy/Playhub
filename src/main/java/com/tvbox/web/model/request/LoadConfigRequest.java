package com.tvbox.web.model.request;

import jakarta.validation.constraints.NotBlank;

public class LoadConfigRequest {

    @NotBlank
    private String url;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
