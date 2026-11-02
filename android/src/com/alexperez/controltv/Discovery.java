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

    static List<Driver> descubrir() {
        List<Driver> res = new ArrayList<>();
        String pre = prefijoLocal();
        if (pre == null) return res;
        ExecutorService ex = Executors.newFixedThreadPool(64);
        try {
            List<Future<Driver>> tareas = new ArrayList<>();
            for (int i = 1; i < 255; i++) {
                final String ip = pre + i;
                tareas.add(ex.submit(new Callable<Driver>() {
                    @Override public Driver call() {
                        if (abierto(ip, Roku.PROBE_PORT)) {
                            Driver d = Roku.probe(ip);
                            if (d != null) return d;
                        }
                        if (abierto(ip, Hisense.PROBE_PORT)) {
                            Driver d = Hisense.probe(ip);
                            if (d != null) return d;
                        }
                        if (abierto(ip, AndroidTV.PROBE_PORT)) return AndroidTV.probe(ip);
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
        return res;
    }
}
