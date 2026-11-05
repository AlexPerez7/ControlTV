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

    // El Roku Express no tiene encendido, volumen ni teclado numerico (eso solo lo traen los Roku TV): la web oculta el resto.
    private static final String[] TECLAS = {"Home", "Back", "Up", "Down", "Left", "Right", "Select", "Rev", "Fwd", "Play",
            "InstantReplay", "Info", "Search", "Enter", "Backspace"};
    @Override String[] teclas() { return TECLAS; }

    static Roku probe(String ip) {
        try {
            String x = Net.get("http://" + ip + ":8060/query/device-info", 2000).text();
            if (!x.contains("<vendor-name>Roku</vendor-name>")) return null;
            Roku r = new Roku(ip, tag(x, "model-name"), tag(x, "user-device-name"));
            r.uid = tag(x, "serial-number").isEmpty() ? tag(x, "device-id") : tag(x, "serial-number");
            r.mac = Net.normalizarMac(tag(x, "wifi-mac"));
            if (r.mac.isEmpty()) r.mac = Net.normalizarMac(tag(x, "ethernet-mac"));
            return r;
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

    // Mantener pulsada: el Roku repite la tecla mientras dura el keydown. Solo flechas y reproduccion (OK no: un toque corto
    // de OK no debe esperar a soltarse).
    private static final String[] LARGAS = {"Up", "Down", "Left", "Right", "Rev", "Fwd"};
    private static final long HOLD_MAX_MS = 5000;  // si no llega el "end" (se cae el celular), se suelta sola
    private final Object holdLock = new Object();
    private String holdTecla;
    private java.util.Timer holdTimer;

    @Override boolean largo() { return true; }
    @Override String[] teclasLargas() { return LARGAS; }

    @Override void keyHold(final String k, String accion) throws Exception {
        if (!java.util.Arrays.asList(LARGAS).contains(k)) throw new DriverError("Esa tecla no se puede mantener");
        if (!accion.equals("start") && !accion.equals("end")) throw new DriverError("Acción no válida");
        synchronized (holdLock) {
            if (holdTecla != null) {  // cualquier orden suelta antes la tecla que siga mantenida
                String previa = holdTecla;
                holdTimer.cancel();
                holdTecla = null;
                holdTimer = null;
                post("/keyup/" + previa);
            }
            if (accion.equals("start")) {
                post("/keydown/" + k);
                java.util.Timer t = new java.util.Timer(true);
                t.schedule(new java.util.TimerTask() {
                    @Override public void run() { soltarPorTiempo(k); }
                }, HOLD_MAX_MS);
                holdTecla = k;
                holdTimer = t;
            }
        }
    }

    private void soltarPorTiempo(String k) {
        synchronized (holdLock) {
            if (k.equals(holdTecla)) {
                holdTecla = null;
                holdTimer = null;
                try { post("/keyup/" + k); } catch (Exception ignorado) { /* sin respuesta */ }
            }
        }
    }

    @Override void text(String t) throws Exception {
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            post("/keypress/Lit_" + Net.pct(new String(Character.toChars(cp))));
            i += Character.charCount(cp);
        }
    }

    /** De /query/active-app (+ /query/media-player) a {actividad: inicio|salvapantallas|app, app, reproductor}. */
    static JSONObject interpretarEstado(String xmlApp, String xmlPlayer) throws Exception {
        String actividad;
        Matcher app = Pattern.compile("<app\\s([^>]*)>([^<]*)</app>").matcher(xmlApp);
        boolean hayApp = app.find();
        if (xmlApp.contains("<screensaver")) actividad = "salvapantallas";  // tapa lo que haya debajo
        else if (!hayApp || app.group(1).contains("type=\"home\"")) actividad = "inicio";
        else actividad = "app";
        JSONObject res = new JSONObject().put("actividad", actividad);
        if (actividad.equals("app")) {
            res.put("app", unesc(app.group(2)).trim());
            Matcher p = Pattern.compile("<player[^>]*\\sstate=\"([^\"]*)\"").matcher(xmlPlayer == null ? "" : xmlPlayer);
            if (p.find() && !p.group(1).equals("close") && !p.group(1).equals("none")) res.put("reproductor", p.group(1));
        }
        return res;
    }

    @Override JSONObject estado() throws Exception {
        String a = Net.get("http://" + ip + ":8060/query/active-app", 2000).text();
        String player = null;
        if (interpretarEstado(a, null).getString("actividad").equals("app")) {
            try { player = Net.get("http://" + ip + ":8060/query/media-player", 2000).text(); } catch (Exception e) { /* solo la app */ }
        }
        return interpretarEstado(a, player);
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

    /** En memoria: el icono de una app no cambia, y abrir "Aplicaciones" bajaba ~300 KB del Roku cada vez. */
    private final java.util.Map<String, Net.Resp> iconos = new java.util.concurrent.ConcurrentHashMap<>();

    @Override Net.Resp icon(String appId) throws Exception {
        Net.Resp r = iconos.get(appId);
        if (r == null) {
            r = Net.get("http://" + ip + ":8060/query/icon/" + Net.pct(seguro(appId)), 4000);
            iconos.put(appId, r);
        }
        return r;
    }
}
