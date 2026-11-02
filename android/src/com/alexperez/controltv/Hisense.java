package com.alexperez.controltv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hisense con Twonky/DLNA + DIAL (TN40.V0021.K221...). Volumen por UPnP RenderingControl (:9085)
 * y apps por DIAL (:56789). La TV pide permiso en pantalla la primera vez (sin aceptarlo, SetVolume da 401).
 * SetMute responde 200 pero no tiene efecto: el mute se emula con el volumen.
 */
final class Hisense extends Driver {
    static final int PROBE_PORT = 56790;
    private static final int PASO = 2;
    private static final String[] APPS_DIAL = {"YouTube", "Netflix"};
    private static final String NS = "urn:schemas-upnp-org:service:RenderingControl:1";

    private int vol = -1;            // ultimo volumen conocido
    private int antesMute = -1;      // volumen previo al mute emulado (-1 = sin mute)

    Hisense(String ip, String modelo, String nombre) { super(ip, modelo, nombre); }

    @Override String marca() { return "Hisense"; }
    @Override String[] caps() { return new String[] {"volume", "apps"}; }

    static Hisense probe(String ip) {
        try {
            String x = Net.get("http://" + ip + ":56790/dd.xml", 2000).text();
            if (!x.toLowerCase().contains("hisense")) return null;
            Matcher m = Pattern.compile("<modelName>([^<]*)").matcher(x);
            return new Hisense(ip, m.find() ? m.group(1).replace("hisense_", "") : "", null);
        } catch (Exception e) {
            return null;
        }
    }

    private String soap(String accion, String extra) throws Exception {
        String cuerpo = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + accion
                + " xmlns:u=\"" + NS + "\"><InstanceID>0</InstanceID><Channel>Master</Channel>" + extra
                + "</u:" + accion + "></s:Body></s:Envelope>";
        try {
            return Net.post("http://" + ip + ":9085/RenderingControl/Control", cuerpo.getBytes(StandardCharsets.UTF_8),
                    new String[][] {{"Content-Type", "text/xml; charset=\"utf-8\""}, {"SOAPACTION", "\"" + NS + "#" + accion + "\""}},
                    4000).text();
        } catch (Net.HttpError e) {
            if (e.code == 401) throw new DriverError("La TV pide permiso: acepta el aviso en su pantalla y vuelve a intentarlo");
            throw new DriverError("La TV respondio HTTP " + e.code);
        }
    }

    @Override synchronized boolean online() {
        try { getVolume(); return true; } catch (Exception e) { return false; }
    }

    private int getVolume() throws Exception {
        Matcher m = Pattern.compile("<CurrentVolume>(\\d+)").matcher(soap("GetVolume", ""));
        if (!m.find()) throw new DriverError("La TV no informo el volumen");
        vol = Integer.parseInt(m.group(1));
        return vol;
    }

    private void setVolume(int v) throws Exception {
        v = Math.max(0, Math.min(100, v));
        soap("SetVolume", "<DesiredVolume>" + v + "</DesiredVolume>");
        vol = v;
    }

    @Override synchronized void key(String k) throws Exception {
        if (k.equals("VolumeMute")) {
            if (antesMute < 0) { antesMute = getVolume(); setVolume(0); }
            else { int v = antesMute; antesMute = -1; setVolume(v); }
            return;
        }
        if (k.equals("VolumeUp") || k.equals("VolumeDown")) {
            if (antesMute >= 0) { vol = antesMute; antesMute = -1; }  // subir/bajar cancela el mute emulado
            int base = vol >= 0 ? vol : getVolume();
            setVolume(base + (k.equals("VolumeUp") ? PASO : -PASO));
            return;
        }
        throw new DriverError("Esta TV solo admite volumen por red (teclas y apagado: pendiente)");
    }

    @Override synchronized JSONObject estado() throws Exception {
        return new JSONObject().put("volume", getVolume()).put("muted", antesMute >= 0);
    }

    @Override JSONArray apps() {
        JSONArray res = new JSONArray();
        for (String n : APPS_DIAL) {
            try {
                Net.get("http://" + ip + ":56789/apps/" + n, 2000);
                res.put(new JSONObject().put("id", n).put("name", n).put("icon", false));
            } catch (Exception ignorado) { /* la app no esta disponible en esta TV */ }
        }
        return res;
    }

    @Override void launch(String appId) throws Exception {
        for (String n : APPS_DIAL) {
            if (n.equals(appId)) {
                Net.post("http://" + ip + ":56789/apps/" + n, null, new String[][] {{"Content-Type", "text/plain"}}, 4000);
                return;
            }
        }
        throw new DriverError("App no soportada");
    }
}
