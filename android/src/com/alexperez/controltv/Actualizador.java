package com.alexperez.controltv;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Actualizaciones desde los Releases de GitHub: consulta la ultima version, descarga el APK, comprueba su SHA-256
 * y lo instala con PackageInstaller (Android pide confirmacion; solo se acepta un APK firmado con la misma clave).
 * La URL que se descarga sale de la respuesta de GitHub guardada aqui, nunca de la web.
 */
final class Actualizador {
    private static final String API = "https://api.github.com/repos/AlexPerez7/ControlTV/releases/latest";
    private static final String ACCION = "com.alexperez.controltv.INSTALACION";
    private static final int MAX_APK = 50 * 1024 * 1024;

    interface Avance { void texto(String t, boolean error); }

    /** Lo que dijo GitHub en la ultima consulta: lo unico que se puede descargar. */
    private static String urlApk = "", sha256 = "";
    private static long tamApk;

    static String versionActual(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    /** Compara "1.4" con "1.10" numericamente; admite prefijo "v". */
    static boolean esMasNueva(String candidata, String actual) {
        String[] a = candidata.replaceFirst("^v", "").split("\\."), b = actual.replaceFirst("^v", "").split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? num(a[i]) : 0, y = i < b.length ? num(b[i]) : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D.*$", "")); } catch (NumberFormatException e) { return 0; }
    }

    /** {actual, ultima, hay, notas}. Lanza excepcion si no hay red o GitHub no responde. */
    static synchronized JSONObject buscar(Context c) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(API).openConnection();
        h.setConnectTimeout(8000);
        h.setReadTimeout(8000);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (h.getResponseCode() != 200) throw new Driver.DriverError("GitHub respondió " + h.getResponseCode());
            JSONObject r = new JSONObject(new String(leerTodo(h.getInputStream(), 1024 * 1024), "UTF-8"));
            String tag = r.getString("tag_name"), actual = versionActual(c);
            boolean hay = esMasNueva(tag, actual);
            urlApk = ""; sha256 = ""; tamApk = 0;
            if (hay) {
                org.json.JSONArray as = r.getJSONArray("assets");
                for (int i = 0; i < as.length(); i++) {
                    JSONObject a = as.getJSONObject(i);
                    if (a.getString("name").endsWith(".apk")) {
                        urlApk = a.getString("browser_download_url");
                        tamApk = a.optLong("size", 0);
                        String dg = a.optString("digest", "");
                        sha256 = dg.startsWith("sha256:") ? dg.substring(7) : "";
                        break;
                    }
                }
                if (urlApk.isEmpty() || !urlApk.startsWith("https://github.com/AlexPerez7/ControlTV/releases/download/"))
                    throw new Driver.DriverError("El Release no trae un APK");
            }
            return new JSONObject().put("actual", actual).put("ultima", tag.replaceFirst("^v", "")).put("hay", hay)
                    .put("notas", r.optString("body", ""));
        } finally {
            h.disconnect();
        }
    }

    private static byte[] leerTodo(InputStream in, int max) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) {
            out.write(b, 0, n);
            if (out.size() > max) throw new Driver.DriverError("Respuesta demasiado grande");
        }
        return out.toByteArray();
    }

    /** Descarga el APK de la ultima busqueda, comprueba su SHA-256 y lo entrega a PackageInstaller. */
    static void actualizar(Context c, Avance av) throws Exception {
        if (urlApk.isEmpty()) throw new Driver.DriverError("Primero busca actualizaciones");
        if (Build.VERSION.SDK_INT >= 26 && !c.getPackageManager().canRequestPackageInstalls()) {
            c.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            throw new Driver.DriverError("Permite a Control TV instalar apps y vuelve a pulsar Actualizar");
        }
        File apk = new File(c.getCacheDir(), "ControlTV-nueva.apk");
        av.texto("Descargando...", false);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        HttpURLConnection h = (HttpURLConnection) new URL(urlApk).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(15000);
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
            byte[] b = new byte[16384];
            long total = 0;
            int n, ultimo = -1;
            while ((n = in.read(b)) > 0) {
                total += n;
                if (total > MAX_APK) throw new Driver.DriverError("El APK es demasiado grande");
                md.update(b, 0, n);
                out.write(b, 0, n);
                int pct = tamApk > 0 ? (int) (total * 100 / tamApk) : 0;
                if (pct / 10 != ultimo) { ultimo = pct / 10; av.texto("Descargando... " + pct + "%", false); }
            }
        } finally {
            h.disconnect();
        }
        StringBuilder hex = new StringBuilder();
        for (byte x : md.digest()) hex.append(String.format("%02x", x));
        if (!sha256.isEmpty() && !sha256.equalsIgnoreCase(hex.toString())) {
            apk.delete();
            throw new Driver.DriverError("La descarga está dañada (SHA-256 distinto): no se instala");
        }
        android.util.Log.i("Actualizador", "descargado " + apk.length() + " bytes, sha256 " + hex + (sha256.isEmpty() ? " (sin digest)" : " (coincide)"));
        av.texto("Instalando...", false);
        instalar(c, apk, av);
    }

    private static void instalar(final Context c, File apk, final Avance av) throws Exception {
        final PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            // el archivo debe quedar cerrado antes de commit() (si no: SecurityException "Files still open")
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("ControlTV.apk", 0, apk.length())) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out);
            }
            BroadcastReceiver rx = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent i) {
                    int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                    android.util.Log.i("Actualizador", "estado de instalacion " + st + ": " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                    if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent conf = i.getParcelableExtra(Intent.EXTRA_INTENT);
                        av.texto("Confirma la instalación en el diálogo de Android", false);
                        try {
                            if (conf != null) ctx.startActivity(conf.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (RuntimeException e) {
                            android.util.Log.e("Actualizador", "no se pudo abrir la confirmacion", e);
                            av.texto("No se pudo abrir la confirmación de Android: " + e.getMessage(), true);
                        }
                        return;
                    }
                    ctx.unregisterReceiver(this);
                    if (st == PackageInstaller.STATUS_SUCCESS) av.texto("✓ Actualizada", false);
                    else av.texto("No se instaló: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), true);
                }
            };
            pi.registerSessionCallback(new PackageInstaller.SessionCallback() {
                @Override public void onCreated(int i) { }
                @Override public void onBadgingChanged(int i) { }
                @Override public void onActiveChanged(int i, boolean a) { }
                @Override public void onProgressChanged(int i, float pr) { android.util.Log.i("Actualizador", "sesion " + i + " progreso " + pr); }
                @Override public void onFinished(int i, boolean ok) {
                    android.util.Log.i("Actualizador", "sesion " + i + " terminada, ok=" + ok);
                    if (i == id) pi.unregisterSessionCallback(this);
                }
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
            if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(rx, new IntentFilter(ACCION), Context.RECEIVER_NOT_EXPORTED);
            else c.registerReceiver(rx, new IntentFilter(ACCION));
            Intent resp = new Intent(ACCION).setPackage(c.getPackageName());
            PendingIntent pend = PendingIntent.getBroadcast(c, id, resp,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            android.util.Log.i("Actualizador", "commit de la sesion " + id);
            s.commit(pend.getIntentSender());
            av.texto("Esperando a Android (si no aparece nada en unos segundos, mira las notificaciones)", false);
        }
    }
}
