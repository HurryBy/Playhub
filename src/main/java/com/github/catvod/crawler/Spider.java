package com.github.catvod.crawler;

import android.content.Context;
import okhttp3.Dns;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;

public abstract class Spider {

    public static final JSONObject empty = new JSONObject();

    protected static Context mContext;
    protected static SpiderApi mSpiderApi;

    public void init(Context context) {
        mContext = context;
    }

    public void init(Context context, String extend) {
        init(context);
    }

    public String homeContent(boolean filter) {
        return "";
    }

    public String homeVideoContent() {
        return "";
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return "";
    }

    public String detailContent(List<String> ids) {
        return "";
    }

    public String searchContent(String key, boolean quick) {
        return "";
    }

    public String playerContent(String flag, String id, List<String> vipFlags) {
        return "";
    }

    public void initApi(SpiderApi spiderApi) {
        mSpiderApi = spiderApi;
    }

    public boolean isVideoFormat(String url) {
        return false;
    }

    public boolean manualVideoCheck() {
        return false;
    }

    public static Dns safeDns() {
        return Dns.SYSTEM;
    }
}
