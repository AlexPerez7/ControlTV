package com.alexperez.controltv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Muestra index.html (la misma web que sirve servidor.py) en un WebView. Las peticiones a
 * http://controltv.local/ no salen a la red: se atienden aqui y las ejecutan los drivers.
 */
public class MainActivity extends Activity {
    private static final String HOST = "controltv.local";
    private static final String PERMISO_RED_LOCAL = "android.permission.ACCESS_LOCAL_NETWORK";
    private final Api api = new Api();
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AndroidTV.contexto = getApplicationContext();
        web = new WebView(this);
        web.setBackgroundColor(0xFF14161A);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !HOST.equals(r.getUrl().getHost());  // nunca salir de la app
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (!HOST.equals(u.getHost())) return respuesta(403, "text/plain", "Bloqueado".getBytes());
                String ruta = u.getPath() == null || u.getPath().isEmpty() ? "/" : u.getPath();
                if (ruta.equals("/") || ruta.equals("/index.html")) return respuesta(200, "text/html", asset("index.html"));
                if (ruta.equals("/icono.svg")) return respuesta(200, "image/svg+xml", asset("icono.svg"));
                if (ruta.equals("/manifest.json")) return respuesta(404, "text/plain", new byte[0]);
                Api.Response a = api.manejar(r.getMethod(), ruta, u.getEncodedQuery());
                return respuesta(a.code, a.type, a.body);
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap f) { /* sin accion */ }
        });

        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(0xFF14161A);
        raiz.addView(web, new FrameLayout.LayoutParams(-1, -1));
        raiz.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets i = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return ins;
        });
        setContentView(raiz);
        if (!redLocalPermitida()) {
            requestPermissions(new String[] {PERMISO_RED_LOCAL}, 1);  // al responder, onRequestPermissionsResult carga la web
        } else {
            web.loadUrl("http://" + HOST + "/");
        }
    }

    /** Android 17+ exige este permiso para hablar con dispositivos de la red local; antes no existe. */
    private boolean redLocalPermitida() {
        return Build.VERSION.SDK_INT < 37 || checkSelfPermission(PERMISO_RED_LOCAL) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int codigo, String[] permisos, int[] resultados) {
        web.loadUrl("http://" + HOST + "/");  // si se denego, la web mostrara "Sin dispositivos"
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    private byte[] asset(String nombre) {
        try (InputStream in = getAssets().open(nombre)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static WebResourceResponse respuesta(int code, String tipo, byte[] cuerpo) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        String razon = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Bad Gateway";
        return new WebResourceResponse(tipo, "utf-8", code, razon, h, new ByteArrayInputStream(cuerpo));
    }
}
