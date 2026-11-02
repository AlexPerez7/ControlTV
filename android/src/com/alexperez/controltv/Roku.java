package com.alexperez.controltv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Roku por ECP (puerto 8060). */
final class Roku extends Driver {
    static final int PROBE_PORT = 8060;

    Roku(String ip, String modelo, String nombre) { super(ip, modelo, nombre); }

    @Override String marca() { return "Roku"; }
    @Override String[] caps() { return new String[] {"keys", "text", "apps"}; }

    static Roku probe(String ip) {
        try {
            String x = Net.get("http://" + ip + ":8060/query/device-info", 2000).text();
            if (!x.contains("<vendor-name>Roku</vendor-name>")) return null;
            return new Roku(ip, tag(x, "model-name"), tag(x, "user-device-name"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String tag(String xml, String t) {
        Matcher m = Pattern.compile("<" + t + ">([^<]*)</" + t + ">").matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    private void post(String path) throws Exception {
        Net.post("http://" + ip + ":8060" + path, null, null, 3000);
    }

    @Override boolean online() {
        try { Net.get("http://" + ip + ":8060/query/device-info", 2000); return true; } catch (Exception e) { return false; }
    }

    @Override void key(String k) throws Exception { post("/keypress/" + Net.pct(seguro(k))); }

    @Override void text(String t) throws Exception {
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            post("/keypress/Lit_" + Net.pct(new String(Character.toChars(cp))));
            i += Character.charCount(cp);
        }
    }

    @Override JSONArray apps() throws Exception {
        String x = Net.get("http://" + ip + ":8060/query/apps", 4000).text();
        JSONArray res = new JSONArray();
        Matcher m = Pattern.compile("<app [^>]*?id=\"([^\"]+)\"[^>]*>([^<]*)</app>").matcher(x);
        while (m.find()) {
            res.put(new JSONObject().put("id", m.group(1)).put("name", unesc(m.group(2))).put("icon", true));
        }
        return res;
    }

    private static String unesc(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'");
    }

    @Override void launch(String appId) throws Exception { post("/launch/" + Net.pct(seguro(appId))); }

    @Override Net.Resp icon(String appId) throws Exception {
        return Net.get("http://" + ip + ":8060/query/icon/" + Net.pct(seguro(appId)), 4000);
    }
}
