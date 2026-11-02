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
