package com.alexperez.controltv;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;
import android.widget.Toast;

/**
 * Widget de pantalla de inicio: Vol -, Silencio, Vol + y Pausa de la TV que se eligio en la app (o la primera), sin abrir
 * la web. Usa las mismas teclas que la web (nombres Roku ECP) a traves de Api.teclaRapida.
 */
public final class WidgetControl extends AppWidgetProvider {
    private static final String ACCION = "com.alexperez.controltv.TECLA";
    private static final String EXTRA_TECLA = "tecla";

    private static final int[][] BOTONES = {
            {R.id.w_vol_menos, 0}, {R.id.w_silencio, 1}, {R.id.w_vol_mas, 2}, {R.id.w_pausa, 3}};
    private static final String[] TECLAS = {"VolumeDown", "VolumeMute", "VolumeUp", "Play"};

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_control);
        for (int[] b : BOTONES) {
            Intent i = new Intent(ctx, WidgetControl.class).setAction(ACCION).putExtra(EXTRA_TECLA, TECLAS[b[1]]);
            // requestCode distinto por boton: si no, Android reutiliza el mismo PendingIntent para todos
            v.setOnClickPendingIntent(b[0], PendingIntent.getBroadcast(ctx, b[1], i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        mgr.updateAppWidget(ids, v);
    }

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        if (!ACCION.equals(intent.getAction())) { super.onReceive(ctx, intent); return; }
        final String tecla = intent.getStringExtra(EXTRA_TECLA);
        if (tecla == null) return;
        final PendingResult fin = goAsync();  // la red no puede ir en el hilo principal del receptor
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String error = Api.instancia(ctx.getApplicationContext()).teclaRapida(tecla);
                    if (!error.isEmpty()) {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override public void run() { Toast.makeText(ctx, error, Toast.LENGTH_SHORT).show(); }
                        });
                    }
                } finally {
                    fin.finish();
                }
            }
        }).start();
    }
}
