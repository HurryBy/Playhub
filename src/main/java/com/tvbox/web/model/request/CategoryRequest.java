package com.tvbox.web.model.request;

import java.util.LinkedHashMap;
import java.util.Map;

public class CategoryRequest {
    private String tid;
    private String pg = "1";
    private boolean filter = true;
    private Map<String, String> extend = new LinkedHashMap<>();

    public String getTid() {
        return tid;
    }

    public void setTid(String tid) {
        this.tid = tid;
    }

    public String getPg() {
        return pg;
    }

    public void setPg(String pg) {
        this.pg = pg;
    }

    public boolean isFilter() {
        return filter;
    }

    public void setFilter(boolean filter) {
        this.filter = filter;
    }

    public Map<String, String> getExtend() {
        return extend;
    }

    public void setExtend(Map<String, String> extend) {
        this.extend = extend;
    }
}
