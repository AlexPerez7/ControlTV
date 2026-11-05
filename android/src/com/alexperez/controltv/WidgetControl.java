package com.alexperez.controltv;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

/**
 * Widget de pantalla de inicio con selector de TV: la fila de arriba muestra la TV elegida (tocarla pasa a la siguiente) y
 * los cuatro botones de abajo cambian segun la TV (Api.widgetPerfil): volumen en Android TV/Hisense; Atras/Inicio en Roku.
 * Las teclas usan los nombres Roku ECP, igual que la web, a traves de Api.teclaRapida.
 */
public final class WidgetControl extends AppWidgetProvider {
    private static final String ACCION = "com.alexperez.controltv.TECLA";
    private static final String ACCION_TV = "com.alexperez.controltv.TV_SIGUIENTE";
    private static final String EXTRA_BOTON = "boton";  // 0..3: la tecla se resuelve al pulsar, con la TV de ese momento

    private static final int[] BOTONES = {R.id.w_b1, R.id.w_b2, R.id.w_b3, R.id.w_b4};

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        pintar(ctx, mgr, ids);
    }

    /** Vuelve a dibujar todos los widgets (p. ej. cuando se elige otra TV en la app). */
    static void refrescar(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, WidgetControl.class));
        if (ids.length > 0) pintar(ctx, mgr, ids);
    }

    private static void pintar(final Context ctx, final AppWidgetManager mgr, final int[] ids) {
        new Thread(new Runnable() {
            @Override public void run() {
                String[] p = Api.instancia(ctx.getApplicationContext()).widgetPerfil();
                RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_control);
                v.setTextViewText(R.id.w_tv, "▸ " + p[0]);
                v.setOnClickPendingIntent(R.id.w_tv, PendingIntent.getBroadcast(ctx, 10,
                        new Intent(ctx, WidgetControl.class).setAction(ACCION_TV),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
                for (int i = 0; i < BOTONES.length; i++) {
                    String etiqueta = p[1 + 2 * i];
                    v.setViewVisibility(BOTONES[i], etiqueta.isEmpty() ? View.INVISIBLE : View.VISIBLE);
                    v.setTextViewText(BOTONES[i], etiqueta);
                    Intent in = new Intent(ctx, WidgetControl.class).setAction(ACCION).putExtra(EXTRA_BOTON, i);
                    // requestCode distinto por boton: si no, Android reutiliza el mismo PendingIntent para todos
                    v.setOnClickPendingIntent(BOTONES[i], PendingIntent.getBroadcast(ctx, i, in,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
                }
                mgr.updateAppWidget(ids, v);
            }
        }).start();
    }

    @Override
    public void onReceive(final Context ctx, final Intent intent) {
        final String accion = intent.getAction();
        if (!ACCION.equals(accion) && !ACCION_TV.equals(accion)) { super.onReceive(ctx, intent); return; }
        final PendingResult fin = goAsync();  // la red no puede ir en el hilo principal del receptor
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Api api = Api.instancia(ctx.getApplicationContext());
                    String error = "";
                    if (ACCION_TV.equals(accion)) {
                        api.widgetSiguiente();
                        refrescar(ctx);
                    } else {
                        int b = intent.getIntExtra(EXTRA_BOTON, -1);
                        String[] p = api.widgetPerfil();
                        if (b >= 0 && b < 4 && !p[1 + 2 * b].isEmpty()) error = api.teclaRapida(p[2 + 2 * b]);
                    }
                    if (!error.isEmpty()) {
                        final String msg = error;
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override public void run() { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show(); }
                        });
                    }
                } finally {
                    fin.finish();
                }
            }
        }).start();
    }
}
