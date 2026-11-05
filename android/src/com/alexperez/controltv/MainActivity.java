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
    private Api api;
    /** Enlace recibido por "Compartir"; la web lo muestra y espera a que el usuario lo envie a la TV. */
    private volatile String compartido = "";
    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        AndroidTV.contexto = getApplicationContext();
        api = new Api(getApplicationContext());
        leerCompartido(getIntent());
        web = new WebView(this);
        web.setBackgroundColor(0xFF14161A);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new Puente(), "App");  // solo recibe paginas de controltv.local (ver shouldOverrideUrlLoading)
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
                WebResourceResponse w = respuesta(a.code, a.type, a.body);
                if (a.escaneando) w.getResponseHeaders().put("X-Escaneando", "1");
                return w;
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
        if (codigo == PEDIDO_MIC) {
            if (resultados.length == 0 || resultados[0] != PackageManager.PERMISSION_GRANTED) avisarVoz("Sin permiso de micrófono", true);
            else avisarVoz("Permiso concedido: mantén pulsado de nuevo", false);
            return;
        }
        web.loadUrl("http://" + HOST + "/");  // si se denego, la web mostrara "Sin dispositivos"
    }

    /** Si llega por "Compartir" (ACTION_SEND), guarda el primer enlace http(s) del texto. */
    private void leerCompartido(android.content.Intent i) {
        if (i == null || !android.content.Intent.ACTION_SEND.equals(i.getAction())) return;
        String t = i.getStringExtra(android.content.Intent.EXTRA_TEXT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(t == null ? "" : t);
        if (m.find() && m.group().length() <= 500) compartido = m.group();
    }

    @Override
    protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        leerCompartido(i);
        web.evaluateJavascript("revisarCompartido()", null);  // muestra el enlace sin recargar la pagina
    }

    /** La web avisa si la TV elegida admite volumen (aplicarCaps en index.html). */
    private volatile boolean tvConVolumen = false;

    private static final int PEDIDO_MIC = 2;
    private volatile boolean grabando;
    private Thread hiloVoz;

    private void avisarVoz(final String txt, final boolean err) {
        runOnUiThread(() -> web.evaluateJavascript("vozEstado(" + org.json.JSONObject.quote(txt) + "," + err + ")", null));
    }

    /** Mantener pulsado: abre el asistente de la TV y envia el microfono (PCM 16 bit, mono, 8 kHz) hasta que se suelta. */
    private void vozEmpezar(final String id) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.RECORD_AUDIO}, PEDIDO_MIC);
            return;
        }
        if (grabando) return;
        grabando = true;
        hiloVoz = new Thread(() -> {
            Driver d = api.driver(id);
            android.media.AudioRecord mic = null;
            boolean abierto = false;
            try {
                if (d == null) throw new Driver.DriverError("Dispositivo desconocido");
                int min = android.media.AudioRecord.getMinBufferSize(8000, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT);
                mic = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, 8000,
                        android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 16000));
                mic.startRecording();
                avisarVoz("Abriendo el asistente...", false);
                d.vozIniciar();
                abierto = true;
                avisarVoz("Escuchando... habla y suelta al terminar", false);
                byte[] buf = new byte[4000];  // 0,25 s de audio a 8 kHz
                while (grabando) {
                    int n = mic.read(buf, 0, buf.length);
                    if (n > 0) d.vozEnviar(buf, n);
                    else if (n < 0) break;
                }
                avisarVoz("✓ Enviado al asistente", false);
            } catch (Exception e) {
                avisarVoz(e instanceof Driver.DriverError ? e.getMessage() : "Error de voz: " + e, true);
            } finally {
                grabando = false;
                if (mic != null) { try { mic.stop(); } catch (RuntimeException e) { /* ya parado */ } mic.release(); }
                if (abierto && d != null) { try { d.vozTerminar(); } catch (Exception e) { /* la TV cerro la sesion */ } }
            }
        });
        hiloVoz.setDaemon(true);
        hiloVoz.start();
    }

    private volatile boolean actualizando;

    private void js(String f, String txt, boolean err) {
        runOnUiThread(() -> web.evaluateJavascript(f + "(" + org.json.JSONObject.quote(txt) + "," + err + ")", null));
    }

    private void buscarActualizacion(final boolean silencioso) {
        new Thread(() -> {
            try {
                js("actualizacionInfo", Actualizador.buscar(getApplicationContext()).toString(), false);
            } catch (Exception e) {
                if (!silencioso) js("actualizacionInfo", "No se pudo consultar: " + (e instanceof Driver.DriverError ? e.getMessage() : "sin conexión a internet"), true);
            }
        }).start();
    }

    private void instalarActualizacion() {
        if (actualizando) return;
        actualizando = true;
        new Thread(() -> {
            try {
                Actualizador.actualizar(getApplicationContext(), (t, err) -> js("actualizacionEstado", t, err));
            } catch (Exception e) {
                android.util.Log.e("Actualizador", "fallo al actualizar", e);
                js("actualizacionEstado", e instanceof Driver.DriverError ? e.getMessage() : "Error al actualizar: " + e, true);
            } finally {
                actualizando = false;
            }
        }).start();
    }

    private final class Puente {
        @android.webkit.JavascriptInterface
        public String version() { return Actualizador.versionActual(getApplicationContext()); }

        @android.webkit.JavascriptInterface
        public void buscarActualizacion(boolean silencioso) { MainActivity.this.buscarActualizacion(silencioso); }

        @android.webkit.JavascriptInterface
        public void actualizar() { instalarActualizacion(); }

        @android.webkit.JavascriptInterface
        public void vozIniciar(String id) { vozEmpezar(id); }

        @android.webkit.JavascriptInterface
        public void vozFin() { grabando = false; }

        @android.webkit.JavascriptInterface
        public void volumen(boolean si) { tvConVolumen = si; }

        /** Vibracion de "boton": click (pulsar), tick (repetir/soltar) o fuerte (mantener). */
        @android.webkit.JavascriptInterface
        public void tocar(String tipo) { vibrar(tipo); }

        @android.webkit.JavascriptInterface
        public String compartido() { return compartido; }

        @android.webkit.JavascriptInterface
        public void limpiarCompartido() { compartido = ""; }
    }

    private android.os.Vibrator vibrador;

    @SuppressWarnings("deprecation")
    private void vibrar(String tipo) {
        try {
            if (vibrador == null) vibrador = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrador == null || !vibrador.hasVibrator()) return;
            boolean fuerte = "fuerte".equals(tipo), tick = "tick".equals(tipo);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                vibrador.vibrate(android.os.VibrationEffect.createPredefined(fuerte ? android.os.VibrationEffect.EFFECT_HEAVY_CLICK
                        : tick ? android.os.VibrationEffect.EFFECT_TICK : android.os.VibrationEffect.EFFECT_CLICK));
            } else {
                vibrador.vibrate(android.os.VibrationEffect.createOneShot(fuerte ? 30 : tick ? 8 : 15, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (RuntimeException e) { /* sin vibrador o sin permiso: no pasa nada */ }
    }

    /** Los botones de volumen del celular controlan la TV elegida si esta soporta volumen; si no, quedan para el celular. */
    @Override
    public boolean onKeyDown(int codigo, android.view.KeyEvent ev) {
        if (tvConVolumen && (codigo == android.view.KeyEvent.KEYCODE_VOLUME_UP || codigo == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            String k = codigo == android.view.KeyEvent.KEYCODE_VOLUME_UP ? "VolumeUp" : "VolumeDown";
            web.evaluateJavascript("teclaFisica('" + k + "')", null);  // con freno: mantener no acumula peticiones
            return true;
        }
        return super.onKeyDown(codigo, ev);
    }

    @Override
    public boolean onKeyUp(int codigo, android.view.KeyEvent ev) {
        if (tvConVolumen && (codigo == android.view.KeyEvent.KEYCODE_VOLUME_UP || codigo == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true;  // el celular no debe cambiar su propio volumen al soltar
        }
        return super.onKeyUp(codigo, ev);
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
