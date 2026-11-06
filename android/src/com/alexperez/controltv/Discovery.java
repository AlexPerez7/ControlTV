package com.alexperez.controltv;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Busca dispositivos soportados en la subred /24: prueba el puerto de cada driver y luego lo identifica. */
final class Discovery {
    private Discovery() {}

    /** Prefijo "a.b.c." de la red WiFi del telefono (prefiere wlan*), o null si no hay red. */
    static String prefijoLocal() {
        String respaldo = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String s = a.getHostAddress();
                        String pre = s.substring(0, s.lastIndexOf('.') + 1);
                        if (ni.getName().startsWith("wlan")) return pre;
                        if (respaldo == null) respaldo = pre;
                    }
                }
            }
        } catch (Exception ignorado) { /* sin red */ }
        return respaldo;
    }

    private static boolean abierto(String ip, int puerto) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, puerto), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Puertos de cada driver, en el orden en que se intenta identificar un dispositivo. */
    private static final int[] PUERTOS = {Roku.PROBE_PORT, Hisense.PROBE_PORT, AndroidTV.PUERTO_PAIR};

    private static Driver identificar(String ip, int puerto) {
        if (puerto == Roku.PROBE_PORT) return Roku.probe(ip);
        if (puerto == Hisense.PROBE_PORT) return Hisense.probe(ip);
        return AndroidTV.probe(ip);
    }

    /**
     * Fase 1: prueba a la vez cada (IP, puerto) de la subred. Fase 2: identifica solo lo que tiene un puerto abierto.
     * Antes cada IP probaba sus puertos uno tras otro (hasta 1,2 s por IP).
     */
    static List<Driver> descubrir() {
        List<Driver> res = new ArrayList<>();
        String pre = prefijoLocal();
        if (pre == null) return res;
        ExecutorService ex = Executors.newFixedThreadPool(128);
        try {
            List<Future<Boolean>> abiertos = new ArrayList<>();
            for (int i = 1; i < 255; i++) {
                for (final int puerto : PUERTOS) {
                    final String ip = pre + i;
                    abiertos.add(ex.submit(new Callable<Boolean>() {
                        @Override public Boolean call() { return abierto(ip, puerto); }
                    }));
                }
            }
            List<Future<Driver>> tareas = new ArrayList<>();
            int n = 0;
            for (int i = 1; i < 255; i++) {
                final String ip = pre + i;
                final List<Integer> puertos = new ArrayList<>();
                for (int puerto : PUERTOS) {
                    try { if (abiertos.get(n++).get()) puertos.add(puerto); } catch (Exception ignorado) { /* sin respuesta */ }
                }
                if (puertos.isEmpty()) continue;
                tareas.add(ex.submit(new Callable<Driver>() {
                    @Override public Driver call() {
                        for (int puerto : puertos) {
                            Driver d = identificar(ip, puerto);
                            if (d != null) return d;
                        }
                        return null;
                    }
                }));
            }
            for (Future<Driver> f : tareas) {
                try {
                    Driver d = f.get();
                    if (d != null) res.add(d);
                } catch (Exception ignorado) { /* host sin respuesta */ }
            }
        } finally {
            ex.shutdownNow();
        }
        for (Driver d : res) if (d.mac.isEmpty()) d.mac = Net.macDe(d.ip);  // en Android 10+ queda "": ver Driver.mac
        return res;
    }
}
