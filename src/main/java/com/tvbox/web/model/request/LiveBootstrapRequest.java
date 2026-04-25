package com.tvbox.web.model.request;

import jakarta.validation.constraints.NotBlank;

public class LiveBootstrapRequest {

    @NotBlank
    private String playlistUrl;

    private String epgUrl;

    public String getPlaylistUrl() {
        return playlistUrl;
    }

    public void setPlaylistUrl(String playlistUrl) {
        this.playlistUrl = playlistUrl;
    }

    public String getEpgUrl() {
        return epgUrl;
    }

    public void setEpgUrl(String epgUrl) {
        this.epgUrl = epgUrl;
    }
}
