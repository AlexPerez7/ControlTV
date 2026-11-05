package com.alexperez.controltv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** HTTP minimo sobre HttpURLConnection para hablar con las TV por la red local. */
final class Net {
    private Net() {}

    static final class HttpError extends IOException {
        final int code;
        HttpError(int code) { super("HTTP " + code); this.code = code; }
    }

    static final class Resp {
        final byte[] body;
        final String type;
        Resp(byte[] body, String type) { this.body = body; this.type = type; }
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    static Resp get(String url, int timeoutMs) throws IOException {
        return http(url, "GET", null, null, timeoutMs);
    }

    static Resp post(String url, byte[] data, String[][] headers, int timeoutMs) throws IOException {
        return http(url, "POST", data == null ? new byte[0] : data, headers, timeoutMs);
    }

    static Resp http(String url, String method, byte[] data, String[][] headers, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod(method);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            if (headers != null) for (String[] h : headers) c.setRequestProperty(h[0], h[1]);
            if (data != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream o = c.getOutputStream()) { o.write(data); }
            }
            int code = c.getResponseCode();
            if (code >= 400) throw new HttpError(code);
            byte[] body;
            try (InputStream in = c.getInputStream()) { body = readAll(in); }
            String t = c.getContentType();
            return new Resp(body, t == null ? "application/octet-stream" : t);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    /** "AA-BB-CC-DD-EE-FF" / "aabbccddeeff" -> "aa:bb:cc:dd:ee:ff"; "" si no es una MAC valida (o es todo ceros). */
    static String normalizarMac(String txt) {
        String h = txt == null ? "" : txt.toLowerCase().replaceAll("[^0-9a-f]", "");
        if (h.length() != 12 || h.matches("0+")) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) sb.append(i > 0 ? ":" : "").append(h, i, i + 2);
        return sb.toString();
    }

    /** MAC de `ip` segun /proc/net/arp. Android 10+ no deja leerla a las apps: entonces devuelve "". */
    static String macDe(String ip) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/net/arp"))) {
            String l;
            while ((l = r.readLine()) != null) {
                String[] c = l.trim().split("\\s+");
                if (c.length >= 4 && c[0].equals(ip)) return normalizarMac(c[3]);
            }
        } catch (Exception e) { /* sin acceso */ }
        return "";
    }

    /** Paquete Wake-on-LAN: 6 bytes 0xFF y la MAC repetida 16 veces. */
    static byte[] paqueteMagico(String mac) throws IOException {
        String m = normalizarMac(mac);
        if (m.isEmpty()) throw new IOException("MAC no valida");
        byte[] b = new byte[102];
        for (int i = 0; i < 6; i++) b[i] = (byte) 0xFF;
        for (int i = 0; i < 6; i++) {
            byte v = (byte) Integer.parseInt(m.substring(i * 3, i * 3 + 2), 16);
            for (int j = 0; j < 16; j++) b[6 + j * 6 + i] = v;
        }
        return b;
    }

    /** Manda el paquete magico por broadcast (255.255.255.255 y el de la /24 de `ip`) a los puertos 9 y 7. */
    static void wakeOnLan(String mac, String ip) throws IOException {
        byte[] datos = paqueteMagico(mac);
        String[] destinos = {"255.255.255.255", ip.substring(0, ip.lastIndexOf('.') + 1) + "255"};
        boolean enviado = false;
        try (java.net.DatagramSocket s = new java.net.DatagramSocket()) {
            s.setBroadcast(true);
            for (String d : destinos) {
                for (int puerto : new int[]{9, 7}) {
                    try {
                        s.send(new java.net.DatagramPacket(datos, datos.length, java.net.InetAddress.getByName(d), puerto));
                        enviado = true;
                    } catch (IOException e) { /* ese destino no */ }
                }
            }
        }
        if (!enviado) throw new IOException("No se pudo enviar Wake-on-LAN (sin red)");
    }

    /** Codifica en %XX todo lo que no sea alfanumerico (como urllib.parse.quote(safe="")). */
    static String pct(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return sb.toString();
    }
}
