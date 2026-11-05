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
        /** /devices: true si la lista viene de la memoria y hay un escaneo en segundo plano (cabecera X-Escaneando). */
        boolean escaneando;
        Response(int code, String type, byte[] body) { this.code = code; this.type = type; this.body = body; }
    }

    /**
     * Ultima lista de dispositivos, guardada en el celular: al abrir la app se muestra al instante (sin esperar el
     * barrido de la red, varios segundos) y se reescanea en segundo plano.
     */
    private final android.content.SharedPreferences prefs;
    private final java.util.concurrent.atomic.AtomicBoolean enFondo = new java.util.concurrent.atomic.AtomicBoolean();

    private static Api compartida;

    /** Una sola Api por proceso: la web y el widget comparten los drivers (y la conexion TLS ya abierta con la TV). */
    static synchronized Api instancia(android.content.Context ctx) {
        if (compartida == null) compartida = new Api(ctx.getApplicationContext());
        return compartida;
    }

    /** La web avisa cual es la TV elegida: el widget manda sus teclas a esa. */
    void elegido(String id) { prefs.edit().putString("elegido", id == null ? "" : id).apply(); }

    /** La TV del widget: la elegida en la app o, si no hay, la primera. */
    private synchronized Driver elegidoOPrimero() {
        Driver d = dispositivos.get(prefs.getString("elegido", ""));
        if (d == null && !dispositivos.isEmpty()) d = dispositivos.values().iterator().next();
        return d;
    }

    /** El widget cambia a la TV siguiente de la lista (en la app tambien queda elegida). */
    String widgetSiguiente() {
        restaurar();
        synchronized (this) {
            Driver actual = elegidoOPrimero();
            boolean tomarSiguiente = false;
            Driver sig = null;
            for (Driver d : dispositivos.values()) {
                if (tomarSiguiente) { sig = d; break; }
                if (d == actual) tomarSiguiente = true;
            }
            if (sig == null && !dispositivos.isEmpty()) sig = dispositivos.values().iterator().next();
            if (sig != null) elegido(sig.id());
            return sig == null ? "" : sig.nombre;
        }
    }

    /**
     * Botones del widget segun la TV elegida: {nombre de la TV, etiqueta1, tecla1, ..., etiqueta4, tecla4}; etiqueta ""
     * = botón oculto. Con volumen: Vol-/Silencio/Vol+/Pausa; sin volumen pero con Inicio (Roku): Atras/Inicio/Pausa/Repetir.
     */
    String[] widgetPerfil() {
        restaurar();
        Driver d = elegidoOPrimero();
        if (d == null) return new String[] {"Abre Control TV", "", "", "", "", "", "", "", ""};
        java.util.List<String> t = d.teclas() == null ? java.util.Collections.<String>emptyList() : java.util.Arrays.asList(d.teclas());
        String[] p = {d.nombre, "", "", "", "", "", "", "", ""};
        String[][] b;
        if (t.contains("VolumeUp")) {
            b = new String[][] {{"Vol −", "VolumeDown"}, {"Silencio", "VolumeMute"}, {"Vol +", "VolumeUp"},
                    t.contains("Play") ? new String[] {"Pausa", "Play"} : new String[] {"", ""}};
        } else if (t.contains("Home")) {
            b = new String[][] {{"Atrás", "Back"}, {"Inicio", "Home"}, {"Pausa", "Play"}, {"Repetir", "InstantReplay"}};
        } else {
            b = new String[0][];
        }
        for (int i = 0; i < b.length; i++) { p[1 + 2 * i] = b[i][0]; p[2 + 2 * i] = b[i][1]; }
        return p;
    }

    /** Para el widget: manda una tecla a la TV elegida en la app (o a la primera). Devuelve "" si salio bien, o el error. */
    String teclaRapida(String tecla) {
        restaurar();
        Driver d = elegidoOPrimero();
        if (d == null) return "Abre Control TV para buscar tu TV";
        try {
            d.key(tecla);
            return "";
        } catch (Driver.DriverError e) {
            return e.getMessage();
        } catch (Exception e) {
            return "Sin conexión con " + d.nombre;
        }
    }

    Api(android.content.Context ctx) {
        prefs = ctx.getSharedPreferences("controltv", android.content.Context.MODE_PRIVATE);
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

    /** Un solo barrido a la vez: si llega otro (p. ej. "Buscar" durante el escaneo inicial), espera y usa ese resultado. */
    private final java.util.concurrent.locks.ReentrantLock escaneo = new java.util.concurrent.locks.ReentrantLock();

    int escanear() {
        if (!escaneo.tryLock()) {
            escaneo.lock();  // espera a que termine el que esta en curso
            escaneo.unlock();
            synchronized (this) { return dispositivos.size(); }
        }
        try {
            return escanearAhora();
        } finally {
            escaneo.unlock();
        }
    }

    /** La red (descubrir, preparar) va fuera del lock: mientras escanea, las teclas siguen funcionando. */
    private int escanearAhora() {
        List<Driver> nuevos = Discovery.descubrir();
        Map<String, Driver> mapa = new LinkedHashMap<>();
        for (Driver d : nuevos) mapa.put(d.id(), d);
        Map<String, Driver> conocidos;
        synchronized (this) { conocidos = new LinkedHashMap<>(dispositivos); }
        for (Map.Entry<String, Driver> e : mapa.entrySet()) {  // sobre la instancia que se va a conservar
            elegir(conocidos.get(e.getKey()), e.getValue()).preparar();
        }
        return fusionar(mapa);
    }

    /** Conserva la instancia ya conocida (su estado: volumen, conexion) salvo que la IP haya cambiado. */
    private static Driver elegir(Driver previo, Driver nuevo) {
        if (previo == null) return nuevo;
        if (nuevo.mac.isEmpty()) nuevo.mac = previo.mac;  // la MAC escrita a mano no se pierde (Android no puede leerla)
        if (!previo.ip.equals(nuevo.ip)) return nuevo;
        if (!nuevo.mac.isEmpty()) previo.mac = nuevo.mac;
        return previo;
    }

    private synchronized int fusionar(Map<String, Driver> mapa) {
        Map<String, Driver> res = new LinkedHashMap<>();
        for (Map.Entry<String, Driver> e : mapa.entrySet()) res.put(e.getKey(), elegir(dispositivos.get(e.getKey()), e.getValue()));
        for (Map.Entry<String, Driver> e : dispositivos.entrySet()) {  // los que no contestaron se recuerdan (○ en la web)
            if (!res.containsKey(e.getKey()) && !e.getValue().uid.isEmpty()) res.put(e.getKey(), e.getValue());
        }
        dispositivos.clear();
        dispositivos.putAll(res);
        escaneado = true;
        persistir();
        return dispositivos.size();
    }

    private synchronized void persistir() {
        try { prefs.edit().putString("dispositivos", listar().toString()).apply(); } catch (Exception ignorado) { /* sin guardar */ }
    }

    /** MAC guardada de la TV ("" si no se conoce): la usa el Wake-on-LAN. */
    synchronized String mac(String id) {
        Driver d = dispositivos.get(id);
        return d == null ? "" : d.mac;
    }

    /** Guarda la MAC que escribio el usuario (Android 10+ no deja leerla de la red). false si no es una MAC valida. */
    synchronized boolean guardarMac(String id, String texto) {
        Driver d = dispositivos.get(id);
        String m = Net.normalizarMac(texto);
        if (d == null || m.isEmpty()) return false;
        d.mac = m;
        persistir();
        return true;
    }

    /** Recrea los drivers de la ultima lista guardada, sin red. False si no hay lista. */
    private synchronized boolean restaurar() {
        if (!dispositivos.isEmpty()) return true;
        try {
            JSONArray a = new JSONArray(prefs.getString("dispositivos", "[]"));
            for (int i = 0; i < a.length(); i++) {
                Driver d = Driver.desdeFicha(a.getJSONObject(i));
                if (d != null) dispositivos.put(d.id(), d);
            }
        } catch (Exception e) {
            dispositivos.clear();
        }
        return !dispositivos.isEmpty();
    }

    private void escanearEnFondo() {
        if (!enFondo.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try { escanear(); } finally { enFondo.set(false); }
        });
        t.setDaemon(true);
        t.start();
    }

    synchronized Driver driver(String id) { return dispositivos.get(id); }

    private synchronized Driver buscar(String id) { return dispositivos.get(id); }

    private Response fichas() throws Exception {
        boolean falta;
        synchronized (this) { falta = !escaneado; }
        if (falta && restaurar()) {  // lista guardada: se muestra ya y el escaneo real sigue en segundo plano
            escanearEnFondo();
            Response r = json(listar());
            r.escaneando = true;
            return r;
        }
        if (falta) escanear();
        return json(listar());
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
                if (ruta.equals("/devices")) return fichas();
            }
            if (metodo.equals("POST") && ruta.equals("/scan")) {
                // ?esperar=1: solo esperar el escaneo de fondo (si ya termino, no se repite el barrido)
                boolean listo;
                synchronized (this) { listo = escaneado; }
                int n = listo && consulta(query, "esperar").equals("1") ? listar().length() : escanear();
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
                if (accion.equals("online")) return json(new JSONObject().put("online", d.online()));
                if (accion.equals("estado")) return json(d.estado());
                if (accion.equals("icon") && arg != null) {
                    Net.Resp r = d.icon(arg);
                    return new Response(200, r.type.split(";")[0].trim(), r.body);
                }
            } else if (metodo.equals("POST")) {
                if (accion.equals("key") && arg != null) {
                    String modo = consulta(query, "accion");  // start/end = mantener la tecla (pulsacion larga)
                    if (modo.isEmpty()) d.key(arg); else d.keyHold(arg, modo);
                    return OK;
                }
                if (accion.equals("text")) {
                    String t = consulta(query, "t");
                    d.text(t);
                    String om = d.omitidos(t);  // 200 con aviso: se escribio lo posible
                    return om.isEmpty() ? OK : texto(200, "Se omitieron: " + om);
                }
                if (accion.equals("search")) { d.search(consulta(query, "q")); return OK; }
                if (accion.equals("launch") && arg != null) { d.launch(arg); return OK; }
                if (accion.equals("pair") && arg != null) { d.pair(arg); return OK; }
                if (accion.equals("fuente") && arg != null) { d.fuente(arg); return OK; }
                if (accion.equals("open")) { d.abrir(consulta(query, "u")); return OK; }
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
