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
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Android TV / Google TV (Caixun, ...). Protocolo "Android TV Remote v2" (equivalente a AndroidTV en dispositivos.py):
 * TLS con certificado de cliente + protobuf. Pairing en :6467 (la TV muestra un codigo de 6 hex) y control en :6466.
 * La identidad TLS (clave RSA + certificado autofirmado) la genera cada celular en el Android Keystore la primera vez:
 * la clave privada nunca sale del celular ni viaja en el APK. Cada celular debe emparejarse una vez con cada TV.
 */
final class AndroidTV extends Driver {
    static final int PROBE_PORT = 6466;
    private static final int PUERTO_PAIR = 6467;
    private static final String ALIAS_CLAVE = "controltv-androidtv-3";  // subir el sufijo si cambian los permisos de la clave: la anterior no se modifica

    /** Lo asigna MainActivity. */
    static Context contexto;
    private static SSLContext ssl;
    private static X509Certificate certCliente;

    private static final Map<String, Integer> TECLAS = new HashMap<>();
    private static final Map<String, String[]> APPS = new LinkedHashMap<>();
    static {
        String[] k = {"Up", "Down", "Left", "Right", "Select", "Back", "Home", "Power", "PowerOff", "PowerOn",
                "VolumeUp", "VolumeDown", "VolumeMute", "Play", "Rev", "Fwd", "Info", "Search", "Enter", "Backspace", "InstantReplay",
                "Settings", "Menu", "ChannelUp", "ChannelDown", "Stop", "Red", "Green", "Yellow", "Blue"};  // sin Source ni Guide: ver plan.md
        int[] v = {19, 20, 21, 22, 23, 4, 3, 26, 223, 224, 24, 25, 164, 85, 89, 90, 165, 84, 66, 67, 88,
                176, 82, 166, 167, 86, 183, 184, 185, 186};
        for (int i = 0; i < k.length; i++) TECLAS.put(k[i], v[i]);
        for (int n = 0; n < 10; n++) TECLAS.put("Num" + n, 7 + n);  // KEYCODE_0..9
        // id (va en la URL: solo letras, numeros, - . _) -> {nombre que se muestra, enlace que abre la app, color}
        APPS.put("youtube", new String[] {"YouTube", "vnd.youtube://", "#c4302b"});
        APPS.put("netflix", new String[] {"Netflix", "nflx://www.netflix.com", "#b20710"});
        APPS.put("prime-video", new String[] {"Prime Video", "https://app.primevideo.com", "#0a7bb5"});
        APPS.put("disney-plus", new String[] {"Disney+", "https://www.disneyplus.com", "#113ccf"});
        APPS.put("hbo-max", new String[] {"HBO Max", "https://play.hbomax.com", "#5822b4"});
        APPS.put("spotify", new String[] {"Spotify", "spotify://", "#1a9c47"});
    }

    private volatile boolean pareado;         // lo fija preparar() al escanear y el emparejamiento
    private volatile SSLSocket sock;
    private volatile boolean listo;
    /** Protege la conexion (sock/listo). Separado del lock del objeto: estado() y el emparejamiento no frenan las teclas. */
    private final Object conn = new Object();
    /** Si conectar acaba de fallar, las teclas siguientes fallan al instante en vez de esperar otro timeout cada una. */
    private volatile long falloHasta;
    private volatile long ultimoMsg;  // cuando llego el ultimo mensaje de la TV por la conexion de control
    private static final long ESPERA_TRAS_FALLO_MS = 3000;
    private final Object envio = new Object();
    private SSLSocket pairSock;
    private long pairDesde;                   // cuando se mostro el codigo en la TV (caduca a los 2 min)
    private volatile int volNivel = -1, volMax, volMute;  // ultima notificacion de la TV (-1 = aun no llego)

    AndroidTV(String ip, String modelo, String nombre) { super(ip, modelo, nombre); }

    @Override String marca() { return "Android TV"; }

    @Override String[] caps() {  // sin red: no bloquea /devices
        return pareado ? new String[] {"keys", "volume", "text", "apps", "link", "voice"} : new String[] {"pair"};
    }

    @Override String[] teclas() { return TECLAS.keySet().toArray(new String[0]); }

    /** Estado guardado del ultimo escaneo; si ya no vale, la primera tecla falla y preparar() lo corrige al reescanear. */
    void restaurarPareado(boolean si) { pareado = si; }

    @Override void preparar() {
        if (!pareado) pareado = comprobarPareado();
    }

    static AndroidTV probe(String ip) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, PUERTO_PAIR), 1500);
            String[] info = infoCast(ip);
            AndroidTV t = new AndroidTV(ip, "", info[0]);
            t.uid = info[1];
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    /** {nombre que el usuario le puso a la TV, id estable ssdp_udn} segun Chromecast (:8008); null / "" si no lo da. */
    private static String[] infoCast(String ip) {
        try {
            JSONObject j = new JSONObject(Net.get("http://" + ip + ":8008/setup/eureka_info", 2000).text());
            String n = j.optString("name", "");
            return new String[]{n.isEmpty() ? null : n, j.optString("ssdp_udn", "")};
        } catch (Exception e) {
            return new String[]{null, ""};
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
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            // El gestor de claves usa el primer alias que encuentra: solo debe quedar la clave vigente.
            for (String alias : java.util.Collections.list(ks.aliases())) if (!alias.equals(ALIAS_CLAVE)) ks.deleteEntry(alias);
            if (!ks.containsAlias(ALIAS_CLAVE)) generarClave();
            certCliente = (X509Certificate) ks.getCertificate(ALIAS_CLAVE);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, null);
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

    /** Crea la clave del celular en el Keystore (hardware si existe). El certificado autofirmado lo emite el propio Keystore. */
    private static void generarClave() throws Exception {
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
        g.initialize(new android.security.keystore.KeyGenParameterSpec.Builder(ALIAS_CLAVE,
                android.security.keystore.KeyProperties.PURPOSE_SIGN | android.security.keystore.KeyProperties.PURPOSE_VERIFY
                | android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048)
                .setRandomizedEncryptionRequired(false)  // la clave solo firma; sin esto el Keystore rechaza el relleno NONE
                .setDigests(android.security.keystore.KeyProperties.DIGEST_NONE, android.security.keystore.KeyProperties.DIGEST_SHA1,
                        android.security.keystore.KeyProperties.DIGEST_SHA256, android.security.keystore.KeyProperties.DIGEST_SHA384,
                        android.security.keystore.KeyProperties.DIGEST_SHA512)
                // Conscrypt firma el TLS armando el bloque PKCS#1 a mano y usando la clave con relleno NONE:
                // sin esto el Keystore responde INCOMPATIBLE_PADDING_MODE y falla el handshake.
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE,
                        android.security.keystore.KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setSignaturePaddings(android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                        android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setCertificateSubject(new javax.security.auth.x500.X500Principal("CN=Control TV"))
                .setCertificateNotBefore(new java.util.Date(System.currentTimeMillis() - 86400000L))
                .setCertificateNotAfter(new java.util.Date(System.currentTimeMillis() + 20L * 365 * 86400000L))
                .build());
        g.generateKeyPair();
    }

    private SSLSocket conectar(int puerto, int ms) throws Exception {
        SSLContext c = ctx();
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(ip, puerto), ms);
        SSLSocket s = (SSLSocket) c.getSocketFactory().createSocket(raw, ip, puerto, true);
        s.setSoTimeout(ms);
        // TLS 1.2: con TLS 1.3 la firma del certificado de cliente es RSA-PSS, y el Keystore de Android la hace
        // con un MGF1 distinto al que pide la TV (falla el handshake con "internal error").
        s.setEnabledProtocols(new String[] {"TLSv1.2"});
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
            if (b < 0) throw new java.io.IOException("conexión cerrada");
            n |= (long) (b & 0x7F) << sh;
            sh += 7;
            if ((b & 0x80) == 0) break;
        }
        byte[] buf = new byte[(int) n];
        int hecho = 0;
        while (hecho < buf.length) {
            int r = in.read(buf, hecho, buf.length - hecho);
            if (r < 0) throw new java.io.IOException("conexión cerrada");
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
            caducarPair(s);
        } catch (DriverError e) {
            throw e;
        } catch (Exception e) {
            throw new DriverError("No se pudo iniciar el emparejamiento: " + e.getMessage());
        }
    }

    /** Si nadie escribe el codigo, el socket de emparejamiento no se queda abierto para siempre (la TV tampoco lo espera). */
    private void caducarPair(final SSLSocket s) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(120000); } catch (InterruptedException e) { return; }
                synchronized (AndroidTV.this) {
                    if (pairSock == s) { cerrar(s); pairSock = null; }  // si no, ya se cerro o se empezo otro emparejamiento
                }
            }
        });
        t.setDaemon(true);
        t.start();
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
            if (h[0] != raw[0]) throw new DriverError("Código incorrecto");
            escribir(s, msgPair(40, f(1, h)));
            Map<Integer, List<Object>> resp = leer(leerMsg(s));
            if (entero(resp, 2, 200) != 200 || !resp.containsKey(41)) throw new DriverError("La TV no aceptó el código");
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
        // Conexion de control viva y la TV hablo hace poco (manda pings): sin tocar la red. Si la TV se apaga de golpe
        // el socket puede seguir "abierto", por eso cuenta el ultimo mensaje y no solo que exista.
        if (sock != null && listo && System.currentTimeMillis() - ultimoMsg < 12000) return true;
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
                ultimoMsg = System.currentTimeMillis();
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
                } else if (m.containsKey(21)) {  // la TV informa los contadores de su campo de texto
                    Map<Integer, List<Object>> v = leer((byte[]) m.get(21).get(0));
                    imeContador = (int) entero(v, 1, 0);
                    imeCampo = (int) entero(v, 2, 0);
                } else if (m.containsKey(30)) {  // remote_voice_begin: el asistente de la TV esta listo para recibir audio
                    int id = (int) entero(leer((byte[]) m.get(30).get(0)), 1, 0);
                    synchronized (vozLock) { vozSesion = id; vozLock.notifyAll(); }
                } else if (m.containsKey(8)) {   // ping
                    long v = entero(leer((byte[]) m.get(8).get(0)), 1, 0);
                    enviar(s, f(9, f(1, v)));
                }
            }
        } catch (Exception fin) {
            // conexion cerrada: se reconecta en el siguiente envio
        } finally {
            cerrar(s);
            synchronized (conn) {
                if (sock == s) { sock = null; listo = false; }  // una conexion vieja no apaga la senal de una nueva
            }
        }
    }

    private SSLSocket asegurarConexion() throws Exception {
        synchronized (conn) {
            SSLSocket actual = sock;
            if (actual != null && listo) return actual;
            if (System.currentTimeMillis() < falloHasta) throw new DriverError(nombre + " no responde (¿está encendida?)");
            try {
                return conectarControl();
            } catch (DriverError e) {
                falloHasta = System.currentTimeMillis() + ESPERA_TRAS_FALLO_MS;
                throw e;
            }
        }
    }

    private SSLSocket conectarControl() throws Exception {
        listo = false;
        final SSLSocket s;
        try {
            s = conectar(PROBE_PORT, 5000);
        } catch (java.io.IOException e) {
            throw new DriverError(nombre + " no responde (¿está encendida?)");
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
            throw new DriverError("La TV no aceptó la conexión: vuelve a emparejarla");
        }
        Thread.sleep(400);  // la TV crea un control virtual por conexion; una tecla enviada antes se pierde
        return s;
    }

    private void mandar(byte[] datos) throws Exception {
        for (int intento = 0; intento < 2; intento++) {
            SSLSocket s = asegurarConexion();
            try {
                enviar(s, datos);
                return;
            } catch (java.io.IOException e) {
                synchronized (conn) {
                    if (sock == s) { sock = null; listo = false; }  // una conexion vieja no apaga una nueva
                }
                cerrar(s);
                if (intento == 1) throw new DriverError(nombre + " no responde");
            }
        }
    }

    // RemoteDirection de Android TV
    private static final int CORTA = 3, START_LARGA = 1, END_LARGA = 2;
    /** Si no llega "end", la tecla mantenida se suelta sola (para que no quede pegada). */
    private static final long HOLD_MAX_MS = 5000;
    private final Object holdLock = new Object();
    private String holdTecla;
    private java.util.Timer holdTimer;

    // -- voz: tecla Busqueda -> la TV manda RemoteVoiceBegin(30) -> respondemos igual y mandamos Payload(31) PCM 16 bit mono 8 kHz -> End(32)
    private final Object vozLock = new Object();
    private int vozSesion = -1;

    @Override void vozIniciar() throws Exception {
        synchronized (vozLock) { vozSesion = -1; }
        inyectar("Search", CORTA);
        int id;
        synchronized (vozLock) {
            long fin = System.currentTimeMillis() + 3000;
            while (vozSesion < 0 && System.currentTimeMillis() < fin) vozLock.wait(Math.max(1, fin - System.currentTimeMillis()));
            id = vozSesion;
        }
        if (id < 0) throw new DriverError("La TV no abrió el asistente de voz");
        mandar(f(30, f(1, id)));
    }

    @Override void vozEnviar(byte[] pcm, int n) throws Exception {
        int id;
        synchronized (vozLock) { id = vozSesion; }
        if (id < 0) return;
        // AudioRecord entrega little-endian, pero la TV espera las muestras big-endian (si no, detecta voz y no transcribe nada)
        byte[] be = new byte[n & ~1];
        for (int i = 0; i < be.length; i += 2) { be[i] = pcm[i + 1]; be[i + 1] = pcm[i]; }
        mandar(f(31, cat(f(1, id), f(2, be))));
    }

    @Override void vozTerminar() throws Exception {
        int id;
        synchronized (vozLock) { id = vozSesion; vozSesion = -1; }
        if (id >= 0) mandar(f(32, f(1, id)));
    }

    private void inyectar(String k, int direccion) throws Exception {
        mandar(f(10, cat(f(1, TECLAS.get(k)), f(2, direccion))));
    }

    @Override void key(String k) throws Exception {
        if (!TECLAS.containsKey(k)) throw new DriverError("Tecla no disponible en esta TV");
        boolean encender = (k.equals("Power") || k.equals("PowerOn")) && !mac.isEmpty();
        if (encender) {
            // Siempre se manda tambien el paquete magico (la TV lo ignora si ya esta despierta): con la red de la TV
            // caida, o justo cuando la esta bajando, la tecla sola no la enciende.
            try { wake(); } catch (Exception e) { encender = false; }
        }
        try {
            inyectar(k, CORTA);
        } catch (DriverError e) {
            if (encender) { trasWake(k); return; }  // sin red: el paquete ya salio; se espera y se manda la tecla
            throw e;
        }
    }

    private static final long ESPERA_WAKE_MS = 20000;

    /**
     * El paquete magico solo despierta la red de la TV (unos 30 s) y la pantalla sigue apagada: en cuanto el puerto de
     * control abre se manda tambien la tecla de encendido. Si no vuelve a tiempo, queda enviado el Wake-on-LAN.
     */
    private void trasWake(String k) throws Exception {
        long fin = System.currentTimeMillis() + ESPERA_WAKE_MS;
        boolean abierto = false;
        while (!abierto && System.currentTimeMillis() < fin) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, PROBE_PORT), 1000);
                abierto = true;
            } catch (Exception e) {
                Thread.sleep(1000);
            }
        }
        if (!abierto) return;
        falloHasta = 0;  // el fallo de conexion anterior ya no vale
        for (int i = 0; i < 3; i++) {  // la TV tarda un instante en aceptar la conexion TLS
            try { inyectar(k, CORTA); return; } catch (DriverError e) { Thread.sleep(1500); }
        }
    }

    // Texto: mensaje de edicion (IME) del protocolo, el mismo que usa la app Google TV. Las teclas NO sirven: entran como
    // teclado numerico y Android solo convierte los digitos en caracteres. Comprobado en la Caixun (YouTube TV): inserta el
    // texto en la posicion del cursor del campo enfocado, con mayusculas, tildes y cualquier simbolo.
    private static final int MAX_TEXTO = 200;
    private volatile int imeContador = 0, imeCampo = 0;  // contadores que informa la TV (0 mientras no haya)

    /** RemoteMessage.remote_ime_batch_edit (21) que inserta `texto`. */
    static byte[] mensajeTexto(String texto, int ime, int campo) {
        int cursor = Math.max(texto.length() - 1, 0);  // String de Java ya cuenta en unidades UTF-16, como Android
        byte[] estado = cat(f(1, cursor), f(2, cursor), f(3, texto));
        return f(21, cat(f(1, ime), f(2, campo), f(3, cat(f(1, 1), f(2, estado)))));
    }

    /**
     * El campo de YouTube TV descarta los caracteres no ASCII (probado: "Ñandú" llego como "and"): se escribe la letra base
     * (ñ -> n, é -> e). Devuelve el texto en ASCII; lo que no tiene equivalente (¿, ¡, €...) se agrega a `omitidos`.
     */
    // Signos que piden Shift: la TV los escribe sin Shift ("!" llego como "1") y "?" activa la busqueda por voz de YouTube TV
    // (probado en la Caixun). Se omiten y se avisa, en vez de arriesgarse a escribir otro caracter en silencio.
    private static final String SIGNOS_CON_SHIFT = "!@#$%^&*()_+{}|:\"<>?~";

    static String aAscii(String texto, StringBuilder omitidos) {
        StringBuilder out = new StringBuilder();
        String nfd = java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD);
        for (int i = 0; i < nfd.length(); ) {
            int cp = nfd.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.getType(cp) == Character.NON_SPACING_MARK) continue;  // tilde suelta
            if (cp < 128 && SIGNOS_CON_SHIFT.indexOf(cp) < 0) { out.appendCodePoint(cp); continue; }
            String ch = new String(Character.toChars(cp));
            if (omitidos.indexOf(ch) < 0) omitidos.append(ch);
        }
        return out.toString();
    }

    @Override String omitidos(String t) {
        StringBuilder om = new StringBuilder();
        aAscii(t, om);
        return om.toString();
    }

    @Override void text(String t) throws Exception {
        t = aAscii(t.replace("\r", "").replace("\n", " "), new StringBuilder());
        if (t.isEmpty()) throw new DriverError("No hay texto que escribir (la TV solo acepta caracteres ASCII; las tildes se quitan)");
        if (t.length() > MAX_TEXTO) throw new DriverError("Texto demasiado largo (maximo " + MAX_TEXTO + " caracteres)");
        mandar(mensajeTexto(t, imeContador, imeCampo));
    }

    @Override boolean largo() { return true; }

    @Override void keyHold(final String k, String accion) throws Exception {
        if (!TECLAS.containsKey(k)) throw new DriverError("Tecla no disponible en esta TV");
        if (!accion.equals("start") && !accion.equals("end")) throw new DriverError("Acción no válida");
        synchronized (holdLock) {
            if (holdTecla != null) {  // cualquier orden suelta antes la tecla que siga mantenida
                String previa = holdTecla;
                holdTimer.cancel();
                holdTecla = null;
                holdTimer = null;
                inyectar(previa, END_LARGA);
            }
            if (accion.equals("start")) {
                inyectar(k, START_LARGA);
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
            if (!k.equals(holdTecla)) return;
            holdTimer.cancel();
            holdTecla = null;
            holdTimer = null;
            try { inyectar(k, END_LARGA); } catch (Exception ignorado) { /* TV sin respuesta: nada que soltar */ }
        }
    }

    @Override JSONObject estado() throws Exception {  // sin lock: la espera del volumen no frena las teclas
        asegurarConexion();
        for (int i = 0; i < 20 && volNivel < 0; i++) Thread.sleep(50);  // la TV manda el volumen justo despues de conectar
        if (volNivel < 0) return new JSONObject();
        return new JSONObject().put("volume", volNivel).put("max", volMax).put("muted", volMute != 0);
    }

    @Override JSONArray apps() throws Exception {
        JSONArray a = new JSONArray();
        for (Map.Entry<String, String[]> e : APPS.entrySet()) {
            a.put(new JSONObject().put("id", e.getKey()).put("name", e.getValue()[0])
                    .put("icon", paquete(e.getKey()) != null).put("color", e.getValue()[2]));
        }
        return a;
    }

    // Iconos: la TV no los entrega por este protocolo, asi que se usan los de las mismas apps instaladas en este celular
    // (sin red). Si el celular no tiene la app, la web cae a su logo propio.
    private static final Map<String, String[]> PAQUETES = new LinkedHashMap<>();
    static {
        PAQUETES.put("youtube", new String[] {"com.google.android.youtube"});
        PAQUETES.put("netflix", new String[] {"com.netflix.mediaclient"});
        PAQUETES.put("prime-video", new String[] {"com.amazon.avod.thirdpartyclient"});
        PAQUETES.put("disney-plus", new String[] {"com.disney.disneyplus"});
        PAQUETES.put("hbo-max", new String[] {"com.wbd.stream", "com.hbo.hbonow"});
        PAQUETES.put("spotify", new String[] {"com.spotify.music"});
    }
    private static final Map<String, byte[]> ICONOS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Paquete instalado en este celular que corresponde a la app, o null. */
    private static String paquete(String appId) {
        android.content.Context c = contexto;
        String[] candidatos = PAQUETES.get(appId);
        if (c == null || candidatos == null) return null;
        for (String p : candidatos) {
            try { c.getPackageManager().getApplicationInfo(p, 0); return p; } catch (Exception e) { /* no instalada */ }
        }
        return null;
    }

    @Override Net.Resp icon(String appId) throws Exception {
        byte[] png = ICONOS.get(appId);
        if (png == null) {
            String p = paquete(appId);
            if (p == null) throw new DriverError("Sin icono");
            android.graphics.drawable.Drawable d = contexto.getPackageManager().getApplicationIcon(p);
            int t = 192;
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(t, t, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas lienzo = new android.graphics.Canvas(b);
            d.setBounds(0, 0, t, t);
            d.draw(lienzo);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o);
            png = o.toByteArray();
            ICONOS.put(appId, png);
        }
        return new Net.Resp(png, "image/png");
    }

    private static final Pattern YOUTUBE = Pattern.compile(
            "(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|live/|embed/))([A-Za-z0-9_-]{11})");

    /** Los enlaces de YouTube pasan al esquema propio de la app (los https:// generales hacen que Android pregunte "Abrir con"). */
    static String enlaceTv(String url) {
        java.util.regex.Matcher m = YOUTUBE.matcher(url);
        return m.find() ? "vnd.youtube://" + m.group(1) : url;
    }

    // Entradas TIF de TVs MediaTek (ver AndroidTV en dispositivos.py). "Inicio": abrir una app y luego Home.
    private static final String[] FUENTES = {"HDMI1", "HDMI2", "HDMI3", "Composite", "TV", "Inicio"};
    @Override String[] fuentes() { return FUENTES; }

    @Override void fuente(String nombre) throws Exception {
        if ("Inicio".equals(nombre)) {
            launch("youtube");
            Thread.sleep(3000);
            key("Home");
            return;
        }
        String hw, srv = ".hdmi.HDMIInputService";
        switch (nombre == null ? "" : nombre) {
            case "TV": hw = "HW0"; srv = ".tuner.TunerInputService"; break;
            case "Composite": hw = "HW1"; srv = ".composite.CompositeInputService"; break;
            case "HDMI1": hw = "HW4"; break;
            case "HDMI2": hw = "HW5"; break;
            case "HDMI3": hw = "HW6"; break;
            default: throw new DriverError("Fuente no válida");
        }
        mandar(f(90, f(1, "content://android.media.tv/passthrough/com.mediatek.tvinput%2F" + srv + "%2F" + hw)));
    }

    @Override void abrir(String url) throws Exception {
        if (url == null || !url.matches("https?://\\S{1,500}")) throw new DriverError("Enlace no válido");
        mandar(f(90, f(1, enlaceTv(url))));
    }

    @Override void launch(String appId) throws Exception {
        String[] app = APPS.get(appId);
        if (app == null) throw new DriverError("App no soportada");
        mandar(f(90, f(1, app[1])));
    }
}
