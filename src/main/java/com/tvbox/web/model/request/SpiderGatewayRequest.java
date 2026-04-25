package com.tvbox.web.model.request;

import com.tvbox.web.model.SiteDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SpiderGatewayRequest {

    private SiteDefinition site;
    private boolean filter = true;
    private String tid = "";
    private String pg = "1";
    private Map<String, String> extend = new LinkedHashMap<>();
    private String id = "";
    private String wd = "";
    private boolean quick = false;
    private String flag = "";
    private List<String> vipFlags;

    public SiteDefinition getSite() {
        return site;
    }

    public void setSite(SiteDefinition site) {
        this.site = site;
    }

    public boolean isFilter() {
        return filter;
    }

    public void setFilter(boolean filter) {
        this.filter = filter;
    }

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

    public Map<String, String> getExtend() {
        return extend;
    }

    public void setExtend(Map<String, String> extend) {
        this.extend = extend;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWd() {
        return wd;
    }

    public void setWd(String wd) {
        this.wd = wd;
    }

    public boolean isQuick() {
        return quick;
    }

    public void setQuick(boolean quick) {
        this.quick = quick;
    }

    public String getFlag() {
        return flag;
    }

    public void setFlag(String flag) {
        this.flag = flag;
    }

    public List<String> getVipFlags() {
        return vipFlags;
    }

    public void setVipFlags(List<String> vipFlags) {
        this.vipFlags = vipFlags;
    }
}
