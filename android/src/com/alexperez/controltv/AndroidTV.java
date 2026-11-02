package com.alexperez.controltv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Android TV / Google TV (Caixun, ...). Protocolo "Android TV Remote v2" (equivalente a AndroidTV en dispositivos.py):
 * TLS con certificado de cliente + protobuf. Pairing en :6467 (la TV muestra un codigo de 6 hex) y control en :6466.
 * La identidad TLS sale de assets/androidtv.p12 (la misma que usa el servidor, asi lo ya emparejado sigue valiendo).
 */
final class AndroidTV extends Driver {
    static final int PROBE_PORT = 6466;
    private static final int PUERTO_PAIR = 6467;
    private static final String CLAVE_P12 = "controltv";

    /** Lo asigna MainActivity: hace falta para leer el .p12 de los assets. */
    static Context contexto;
    private static SSLContext ssl;
    private static X509Certificate certCliente;

    private static final Map<String, Integer> TECLAS = new HashMap<>();
    private static final Map<String, String> APPS = new LinkedHashMap<>();
    static {
        String[] k = {"Up", "Down", "Left", "Right", "Select", "Back", "Home", "Power", "PowerOff", "PowerOn",
                "VolumeUp", "VolumeDown", "VolumeMute", "Play", "Rev", "Fwd", "Info", "Search", "Enter", "Backspace", "InstantReplay"};
        int[] v = {19, 20, 21, 22, 23, 4, 3, 26, 223, 224, 24, 25, 164, 85, 89, 90, 165, 84, 66, 67, 88};
        for (int i = 0; i < k.length; i++) TECLAS.put(k[i], v[i]);
        APPS.put("YouTube", "vnd.youtube://");
        APPS.put("Netflix", "nflx://www.netflix.com");
        APPS.put("Prime Video", "https://app.primevideo.com");
        APPS.put("Disney+", "https://www.disneyplus.com");
        APPS.put("Max", "https://www.max.com");
        APPS.put("Spotify", "spotify://");
    }

    private volatile boolean pareado;         // lo fija preparar() al escanear y el emparejamiento
    private SSLSocket sock;
    private volatile boolean listo;
    private final Object envio = new Object();
    private SSLSocket pairSock;
    private long pairDesde;                   // cuando se mostro el codigo en la TV (caduca a los 2 min)
    private volatile int volNivel = -1, volMax, volMute;  // ultima notificacion de la TV (-1 = aun no llego)

    AndroidTV(String ip, String modelo, String nombre) { super(ip, modelo, nombre); }

    @Override String marca() { return "Android TV"; }

    @Override String[] caps() {  // sin red: no bloquea /devices
        return pareado ? new String[] {"keys", "volume", "apps"} : new String[] {"pair"};
    }

    @Override String[] teclas() { return TECLAS.keySet().toArray(new String[0]); }

    @Override void preparar() {
        if (!pareado) pareado = comprobarPareado();
    }

    static AndroidTV probe(String ip) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, PUERTO_PAIR), 1500);
            return new AndroidTV(ip, "", nombreCast(ip));
        } catch (Exception e) {
            return null;
        }
    }

    /** Nombre que el usuario le puso a la TV (lo publica por Chromecast en :8008); null si no lo da. */
    private static String nombreCast(String ip) {
        try {
            String n = new JSONObject(Net.get("http://" + ip + ":8008/setup/eureka_info?params=name", 2000).text()).optString("name", "");
            return n.isEmpty() ? null : n;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- protobuf minimo ----------------------------------------------------------------
    private static byte[] varint(long n) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        do {
            int b = (int) (n & 0x7F);
            n >>>= 7;
            o.write(b | (n != 0 ? 0x80 : 0));
        } while (n != 0);
        return o.toByteArray();
    }

    private static byte[] cat(byte[]... partes) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : partes) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    private static byte[] f(int num, long v) { return cat(varint((long) num << 3), varint(v)); }

    private static byte[] f(int num, byte[] v) { return cat(varint((long) num << 3 | 2), varint(v.length), v); }

    private static byte[] f(int num, String v) { return f(num, v.getBytes(StandardCharsets.UTF_8)); }

    /** Decodifica un mensaje a {numero: [Long | byte[]]}. */
    private static Map<Integer, List<Object>> leer(byte[] d) {
        Map<Integer, List<Object>> res = new HashMap<>();
        int[] i = {0};
        while (i[0] < d.length) {
            long clave = leerVarint(d, i);
            int num = (int) (clave >>> 3), tipo = (int) (clave & 7);
            Object v;
            if (tipo == 0) v = leerVarint(d, i);
            else if (tipo == 2) {
                int n = (int) leerVarint(d, i);
                v = java.util.Arrays.copyOfRange(d, i[0], i[0] + n);
                i[0] += n;
            } else if (tipo == 1) { i[0] += 8; continue; }
            else if (tipo == 5) { i[0] += 4; continue; }
            else break;
            List<Object> l = res.get(num);
            if (l == null) { l = new ArrayList<>(); res.put(num, l); }
            l.add(v);
        }
        return res;
    }

    private static long leerVarint(byte[] d, int[] i) {
        long n = 0;
        int sh = 0;
        while (true) {
            int b = d[i[0]++] & 0xFF;
            n |= (long) (b & 0x7F) << sh;
            sh += 7;
            if ((b & 0x80) == 0) return n;
        }
    }

    private static long entero(Map<Integer, List<Object>> m, int num, long defecto) {
        List<Object> l = m.get(num);
        return l == null || !(l.get(0) instanceof Long) ? defecto : (Long) l.get(0);
    }

    // ---- TLS y mensajes ------------------------------------------------------------------
    private static synchronized SSLContext ctx() throws Exception {
        if (ssl == null) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = contexto.getAssets().open("androidtv.p12")) {
                ks.load(in, CLAVE_P12.toCharArray());
            }
            certCliente = (X509Certificate) ks.getCertificate(ks.aliases().nextElement());
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, CLAVE_P12.toCharArray());
            // La TV usa un certificado propio: no se valida (el emparejamiento por codigo es la autenticacion).
            TrustManager[] confiar = {new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String t) { /* sin accion */ }
                @Override public void checkServerTrusted(X509Certificate[] c, String t) { /* sin accion */ }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext c = SSLContext.getInstance("TLS");
            c.init(kmf.getKeyManagers(), confiar, null);
            ssl = c;
        }
        return ssl;
    }

    private SSLSocket conectar(int puerto, int ms) throws Exception {
        SSLContext c = ctx();
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(ip, puerto), ms);
        SSLSocket s = (SSLSocket) c.getSocketFactory().createSocket(raw, ip, puerto, true);
        s.setSoTimeout(ms);
        s.startHandshake();
        return s;
    }

    private static void escribir(SSLSocket s, byte[] datos) throws Exception {
        s.getOutputStream().write(cat(varint(datos.length), datos));
        s.getOutputStream().flush();
    }

    private static byte[] leerMsg(SSLSocket s) throws Exception {
        InputStream in = s.getInputStream();
        long n = 0;
        int sh = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new java.io.IOException("conexion cerrada");
            n |= (long) (b & 0x7F) << sh;
            sh += 7;
            if ((b & 0x80) == 0) break;
        }
        byte[] buf = new byte[(int) n];
        int hecho = 0;
        while (hecho < buf.length) {
            int r = in.read(buf, hecho, buf.length - hecho);
            if (r < 0) throw new java.io.IOException("conexion cerrada");
            hecho += r;
        }
        return buf;
    }

    private static void cerrar(SSLSocket s) {
        try { if (s != null) s.close(); } catch (Exception ignorado) { /* ya cerrado */ }
    }

    // ---- emparejamiento ------------------------------------------------------------------
    /** Pareado = la TV nos habla (envia su configuracion) al conectar al puerto de control. */
    private boolean comprobarPareado() {
        SSLSocket s = null;
        try {
            s = conectar(PROBE_PORT, 3000);
            return leer(leerMsg(s)).containsKey(1);
        } catch (Exception e) {
            return false;
        } finally {
            cerrar(s);
        }
    }

    @Override synchronized void pair(String arg) throws Exception {
        if (arg.equals("start")) pairInicio(); else pairCodigo(arg);
    }

    private static byte[] msgPair(int campo, byte[] cuerpo) { return cat(f(1, 2), f(2, 200), f(campo, cuerpo)); }

    private void pairInicio() throws Exception {
        cerrar(pairSock);
        pairSock = null;
        try {
            SSLSocket s = conectar(PUERTO_PAIR, 8000);
            byte[] enc = cat(f(1, 3), f(2, 6));  // HEXADECIMAL, 6 simbolos
            byte[][][] pasos = {{{10}, cat(f(1, "atvremote"), f(2, "Control TV"))},
                    {{20}, cat(f(1, enc), f(3, 1))},
                    {{30}, cat(f(1, enc), f(2, 1))}};
            for (byte[][] p : pasos) {
                escribir(s, msgPair(p[0][0], p[1]));
                long estado = entero(leer(leerMsg(s)), 2, 200);
                if (estado != 200) { cerrar(s); throw new DriverError("La TV rechazo el emparejamiento (estado " + estado + ")"); }
            }
            pairSock = s;
            pairDesde = System.currentTimeMillis();
        } catch (DriverError e) {
            throw e;
        } catch (Exception e) {
            throw new DriverError("No se pudo iniciar el emparejamiento: " + e.getMessage());
        }
    }

    private static byte[] sinCero(BigInteger n) {
        byte[] b = n.toByteArray();
        return b.length > 1 && b[0] == 0 ? java.util.Arrays.copyOfRange(b, 1, b.length) : b;
    }

    private void pairCodigo(String codigo) throws Exception {
        codigo = codigo.trim().toUpperCase();
        if (!codigo.matches("[0-9A-F]{6}")) throw new DriverError("El codigo son 6 caracteres (0-9, A-F)");
        SSLSocket s = pairSock;
        if (s == null) throw new DriverError("Primero inicia el emparejamiento");
        if (System.currentTimeMillis() - pairDesde > 120000) {
            cerrar(s);
            pairSock = null;
            throw new DriverError("El codigo caduco: vuelve a pulsar 'Mostrar codigo en la TV'");
        }
        try {
            RSAPublicKey cli = (RSAPublicKey) certCliente.getPublicKey();
            Certificate[] cadena = s.getSession().getPeerCertificates();
            RSAPublicKey srv = (RSAPublicKey) cadena[0].getPublicKey();
            byte[] raw = new byte[3];
            for (int i = 0; i < 3; i++) raw[i] = (byte) Integer.parseInt(codigo.substring(2 * i, 2 * i + 2), 16);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(sinCero(cli.getModulus()));
            sha.update(sinCero(cli.getPublicExponent()));
            sha.update(sinCero(srv.getModulus()));
            sha.update(sinCero(srv.getPublicExponent()));
            sha.update(raw, 1, 2);
            byte[] h = sha.digest();
            if (h[0] != raw[0]) throw new DriverError("Codigo incorrecto");
            escribir(s, msgPair(40, f(1, h)));
            Map<Integer, List<Object>> resp = leer(leerMsg(s));
            if (entero(resp, 2, 200) != 200 || !resp.containsKey(41)) throw new DriverError("La TV no acepto el codigo");
        } catch (DriverError e) {
            throw e;
        } catch (Exception e) {
            throw new DriverError("Fallo el emparejamiento: " + e.getMessage());
        }
        cerrar(s);
        pairSock = null;
        pareado = true;
    }

    // ---- control -------------------------------------------------------------------------
    @Override boolean online() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, PROBE_PORT), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void enviar(SSLSocket s, byte[] datos) throws Exception {
        synchronized (envio) { escribir(s, datos); }
    }

    private void lector(final SSLSocket s) {
        try {
            s.setSoTimeout(0);
            while (true) {
                Map<Integer, List<Object>> m = leer(leerMsg(s));
                if (m.containsKey(1)) {          // la TV pide nuestra configuracion
                    byte[] dev = cat(f(1, "Control TV"), f(2, "Control TV"), f(3, 1), f(4, "1"), f(5, "control.tv"), f(6, "1.0.0"));
                    enviar(s, f(1, cat(f(1, 622), f(2, dev))));
                } else if (m.containsKey(2)) {   // set_active
                    enviar(s, f(2, f(1, 622)));
                } else if (m.containsKey(40)) {  // remote_start: listo para recibir teclas
                    listo = true;
                } else if (m.containsKey(50)) {  // volumen: nivel (7), maximo (6), silenciado (8)
                    Map<Integer, List<Object>> v = leer((byte[]) m.get(50).get(0));
                    volMax = (int) entero(v, 6, 0);
                    volMute = (int) entero(v, 8, 0);
                    volNivel = (int) entero(v, 7, 0);
                } else if (m.containsKey(8)) {   // ping
                    long v = entero(leer((byte[]) m.get(8).get(0)), 1, 0);
                    enviar(s, f(9, f(1, v)));
                }
            }
        } catch (Exception fin) {
            // conexion cerrada: se reconecta en el siguiente envio
        } finally {
            listo = false;
            cerrar(s);
            synchronized (AndroidTV.this) { if (sock == s) sock = null; }
        }
    }

    private SSLSocket asegurarConexion() throws Exception {
        if (sock != null && listo) return sock;
        listo = false;
        final SSLSocket s;
        try {
            s = conectar(PROBE_PORT, 5000);
        } catch (java.io.IOException e) {
            throw new DriverError(nombre + " no responde (¿esta encendida?)");
        }
        sock = s;
        Thread t = new Thread(new Runnable() { @Override public void run() { lector(s); } });
        t.setDaemon(true);
        t.start();
        for (int i = 0; i < 120 && !listo; i++) Thread.sleep(50);
        if (!listo) {
            sock = null;
            cerrar(s);
            pareado = false;
            throw new DriverError("La TV no acepto la conexion: vuelve a emparejarla");
        }
        return s;
    }

    private synchronized void mandar(byte[] datos) throws Exception {
        for (int intento = 0; intento < 2; intento++) {
            SSLSocket s = asegurarConexion();
            try {
                enviar(s, datos);
                return;
            } catch (java.io.IOException e) {
                sock = null;
                listo = false;
                if (intento == 1) throw new DriverError(nombre + " no responde");
            }
        }
    }

    @Override void key(String k) throws Exception {
        Integer code = TECLAS.get(k);
        if (code == null) throw new DriverError("Tecla no disponible en esta TV");
        mandar(f(10, cat(f(1, code), f(2, 3))));  // direction 3 = SHORT
    }

    @Override synchronized JSONObject estado() throws Exception {
        asegurarConexion();
        for (int i = 0; i < 20 && volNivel < 0; i++) Thread.sleep(50);  // la TV manda el volumen justo despues de conectar
        if (volNivel < 0) return new JSONObject();
        return new JSONObject().put("volume", volNivel).put("max", volMax).put("muted", volMute != 0);
    }

    @Override JSONArray apps() throws Exception {
        JSONArray a = new JSONArray();
        for (String n : APPS.keySet()) a.put(new JSONObject().put("id", n).put("name", n).put("icon", false));
        return a;
    }

    @Override void launch(String appId) throws Exception {
        String link = APPS.get(appId);
        if (link == null) throw new DriverError("App no soportada");
        mandar(f(90, f(1, link)));
    }
}
