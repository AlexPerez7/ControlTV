package com.alexperez.controltv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Driver por marca (equivalente a dispositivos.py). Cada uno declara sus capacidades
 * (keys, text, apps, volume) y la web solo muestra los botones que soporta.
 * Para agregar una marca: subclase + registrarla en Discovery.DRIVERS.
 */
abstract class Driver {
    /** Error con un mensaje apto para mostrar al usuario. */
    static final class DriverError extends Exception {
        DriverError(String m) { super(m); }
    }

    final String ip;
    final String modelo;
    final String nombre;

    Driver(String ip, String modelo, String nombre) {
        this.ip = ip;
        this.modelo = modelo == null ? "" : modelo;
        this.nombre = (nombre == null || nombre.isEmpty()) ? (marca() + " " + this.modelo).trim() : nombre;
    }

    abstract String marca();
    abstract String[] caps();

    boolean online() { return false; }
    void key(String k) throws Exception { throw new DriverError("Este dispositivo no admite teclas"); }
    void text(String t) throws Exception { throw new DriverError("Este dispositivo no admite texto"); }
    JSONArray apps() throws Exception { return new JSONArray(); }
    void launch(String appId) throws Exception { throw new DriverError("Este dispositivo no admite abrir apps"); }
    Net.Resp icon(String appId) throws Exception { throw new DriverError("Sin iconos"); }
    JSONObject estado() throws Exception { return new JSONObject(); }
    /** Abre un enlace (p. ej. un video compartido) en el dispositivo. */
    void abrir(String url) throws Exception { throw new DriverError("Este dispositivo no admite abrir enlaces"); }

    /** True si admite mantener una tecla (keyHold). */
    boolean largo() { return false; }

    /** Mantener una tecla: accion "start" al pulsar y "end" al soltar. */
    void keyHold(String k, String accion) throws Exception { throw new DriverError("Este dispositivo no admite mantener teclas"); }

    /** Teclas que admite (la web oculta las demas), o null si admite todas. */
    String[] teclas() { return null; }

    /** Comprobaciones lentas por red que se hacen al escanear (fuera de cualquier lock). */
    void preparar() { }
    void pair(String arg) throws Exception { throw new DriverError("Este dispositivo no necesita emparejarse"); }

    static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    String id() { return slug(marca() + "-" + ip); }

    JSONObject ficha() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id());
        o.put("marca", marca());
        o.put("modelo", modelo);
        o.put("nombre", nombre);
        o.put("ip", ip);
        o.put("caps", new JSONArray(java.util.Arrays.asList(caps())));
        String[] t = teclas();
        if (t != null) o.put("teclas", new JSONArray(java.util.Arrays.asList(t)));
        if (largo()) o.put("largo", true);
        return o;
    }

    /** Valida identificadores que acaban dentro de una URL del dispositivo. */
    static String seguro(String s) throws DriverError {
        if (s == null || !s.matches("[A-Za-z0-9_.-]{1,64}")) throw new DriverError("Identificador no valido");
        return s;
    }
}
