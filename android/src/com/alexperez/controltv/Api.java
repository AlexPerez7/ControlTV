package com.alexperez.controltv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mismas rutas que servidor.py, pero dentro de la app (sin PC). Se llama desde el WebView. */
final class Api {
    static final class Response {
        final int code;
        final String type;
        final byte[] body;
        Response(int code, String type, byte[] body) { this.code = code; this.type = type; this.body = body; }
    }

    private static final Pattern RUTA = Pattern.compile("/d/([a-z0-9-]+)/(\\w+)(?:/([\\w.-]+))?");
    private final Map<String, Driver> dispositivos = new LinkedHashMap<>();
    private boolean escaneado = false;

    private static Response texto(int code, String s) {
        return new Response(code, "text/plain", s.getBytes(StandardCharsets.UTF_8));
    }

    private static Response json(Object o) {
        return new Response(200, "application/json", o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static final Response OK = new Response(200, "text/plain", new byte[0]);

    /** La red (descubrir, preparar) va fuera del lock: mientras escanea, las teclas siguen funcionando. */
    int escanear() {
        List<Driver> nuevos = Discovery.descubrir();
        Map<String, Driver> mapa = new LinkedHashMap<>();
        for (Driver d : nuevos) mapa.put(d.id(), d);
        Map<String, Driver> conocidos;
        synchronized (this) { conocidos = new LinkedHashMap<>(dispositivos); }
        for (Map.Entry<String, Driver> e : mapa.entrySet()) {  // sobre la instancia que se va a conservar
            Driver d = conocidos.get(e.getKey());
            (d != null ? d : e.getValue()).preparar();
        }
        return fusionar(mapa);
    }

    private synchronized int fusionar(Map<String, Driver> mapa) {
        for (Map.Entry<String, Driver> e : mapa.entrySet()) {  // conserva el estado (volumen, mute) de los ya conocidos
            if (!dispositivos.containsKey(e.getKey())) dispositivos.put(e.getKey(), e.getValue());
        }
        dispositivos.keySet().retainAll(mapa.keySet());
        escaneado = true;
        return dispositivos.size();
    }

    private synchronized Driver buscar(String id) { return dispositivos.get(id); }

    private JSONArray fichas() throws Exception {
        boolean falta;
        synchronized (this) { falta = !escaneado; }
        if (falta) escanear();
        return listar();
    }

    private synchronized JSONArray listar() throws Exception {
        JSONArray a = new JSONArray();
        for (Driver d : dispositivos.values()) a.put(d.ficha());
        return a;
    }

    private static String consulta(String q, String clave) {
        if (q == null) return "";
        for (String par : q.split("&")) {
            int i = par.indexOf('=');
            if (i > 0 && par.substring(0, i).equals(clave)) {
                try { return URLDecoder.decode(par.substring(i + 1), "UTF-8"); } catch (Exception e) { return ""; }
            }
        }
        return "";
    }

    Response manejar(String metodo, String ruta, String query) {
        try {
            if (metodo.equals("GET")) {
                if (ruta.equals("/ping")) return texto(200, "ok");
                if (ruta.equals("/devices")) return json(fichas());
            }
            if (metodo.equals("POST") && ruta.equals("/scan")) {
                int n = escanear();
                String red = Discovery.prefijoLocal();
                return json(new JSONObject().put("dispositivos", n).put("red", red == null ? "sin WiFi" : red + "0/24"));
            }
            Matcher m = RUTA.matcher(ruta);
            if (!m.matches()) return texto(404, "No encontrado");
            Driver d = buscar(m.group(1));
            if (d == null) return texto(404, "Dispositivo desconocido (reescanea)");
            String accion = m.group(2), arg = m.group(3);
            if (metodo.equals("GET")) {
                if (accion.equals("apps")) return json(d.apps());
                if (accion.equals("estado")) return json(d.estado());
                if (accion.equals("icon") && arg != null) {
                    Net.Resp r = d.icon(arg);
                    return new Response(200, r.type.split(";")[0].trim(), r.body);
                }
            } else if (metodo.equals("POST")) {
                if (accion.equals("key") && arg != null) { d.key(arg); return OK; }
                if (accion.equals("text")) { d.text(consulta(query, "t")); return OK; }
                if (accion.equals("launch") && arg != null) { d.launch(arg); return OK; }
                if (accion.equals("pair") && arg != null) { d.pair(arg); return OK; }
            }
            return texto(404, "No encontrado");
        } catch (Driver.DriverError e) {
            return texto(502, e.getMessage());
        } catch (IOException e) {
            return texto(502, "El dispositivo no responde");
        } catch (Exception e) {
            return texto(502, "Error: " + e.getMessage());
        }
    }
}
