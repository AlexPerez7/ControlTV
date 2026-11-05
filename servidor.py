"""Control TV - servidor local que controla varios dispositivos (Roku, Hisense, ...) por WiFi.

Uso:  python servidor.py [--puerto 8090]
Los dispositivos se detectan solos (SSDP). Para agregar uno a mano o forzar su marca,
crea dispositivos.json:  [{"ip": "192.168.31.145", "marca": "vestel", "modelo": "E58D62UN"}]
Solo usa la biblioteca estandar de Python.
"""
import argparse
import ipaddress
import json
import re
import socket
import threading
import urllib.error
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import dispositivos
from dispositivos import DRIVERS, DriverError

BASE = Path(__file__).resolve().parent
CFG = BASE / "dispositivos.json"
DEVICES: dict = {}  # id -> Driver
LOCK = threading.Lock()
ESCANEO = threading.Lock()  # un solo barrido a la vez: si llega otro "Buscar", espera y usa ese resultado
IP_LOCAL = "127.0.0.1"

MANIFEST = ('{"name":"Control TV","short_name":"Control TV","start_url":"/","display":"standalone",'
            '"orientation":"portrait","background_color":"#14161a","theme_color":"#14161a",'
            '"icons":[{"src":"/icono.svg","sizes":"any","type":"image/svg+xml","purpose":"any"}]}')
ICONO_SVG = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><rect width="512" height="512" rx="110" fill="#14161a"/>'
             '<circle cx="256" cy="256" r="150" fill="#252931"/><circle cx="256" cy="256" r="62" fill="#6c3fd1"/>'
             '<path d="M256 120l34 40h-68zM256 392l-34-40h68zM120 256l40-34v68zM392 256l-40 34v-68z" fill="#e8eaed"/></svg>')


def ip_local() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def id_de(d) -> str:
    return re.sub(r"[^a-z0-9]+", "-", f"{d.marca}-{d.ip}".lower()).strip("-")


def manuales() -> list:
    if not CFG.exists():
        return []
    try:
        entradas = json.loads(CFG.read_text(encoding="utf-8"))
        if not isinstance(entradas, list):
            raise ValueError("debe ser una lista [ {...}, ... ]")
    except (OSError, ValueError) as e:  # JSONDecodeError es ValueError
        print(f"  dispositivos.json no se pudo leer, se ignora: {e}")
        return []
    res = []
    for e in entradas:
        if not isinstance(e, dict):
            print(f"  dispositivos.json: entrada ignorada {e!r}")
            continue
        clase = DRIVERS.get(str(e.get("marca", "")).lower())
        if clase and e.get("ip"):
            res.append(clase(e["ip"], e.get("modelo", ""), e.get("nombre", "")))
        else:
            print(f"  dispositivos.json: entrada ignorada {e}")
    return res


def escanear() -> int:
    if not ESCANEO.acquire(blocking=False):
        with ESCANEO:  # ya hay uno en curso: esperar a que termine
            pass
        with LOCK:
            return len(DEVICES)
    try:
        return _escanear()
    finally:
        ESCANEO.release()


def _escanear() -> int:
    nuevos = {id_de(d): d for d in dispositivos.descubrir(IP_LOCAL)}
    nuevos.update({id_de(d): d for d in manuales()})
    with LOCK:
        conocidos = dict(DEVICES)
    for k, d in nuevos.items():  # lento (red): fuera del lock, sobre la instancia que se va a conservar
        conocidos.get(k, d).preparar()
    with LOCK:
        for k, d in nuevos.items():  # conserva el estado (volumen, mute) de los ya conocidos
            DEVICES.setdefault(k, d)
        for k in [k for k in DEVICES if k not in nuevos]:
            del DEVICES[k]
        return len(DEVICES)


def ficha(k, d) -> dict:
    f = {"id": k, "marca": d.marca, "modelo": d.modelo, "nombre": d.nombre, "ip": d.ip, "caps": list(d.caps)}
    if d.teclas is not None:
        f["teclas"] = list(d.teclas)
    if d.largo:
        f["largo"] = True
    if getattr(d, "fuentes", None):
        f["fuentes"] = list(d.fuentes)
    return f


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def responder(self, code, body=b"", ctype="text/plain; charset=utf-8", cache="no-store"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", cache)
        self.end_headers()
        self.wfile.write(body)

    def json(self, obj, code=200):
        self.responder(code, json.dumps(obj).encode(), "application/json; charset=utf-8")

    def despachar(self, metodo):
        url = urllib.parse.urlparse(self.path)
        p, q = url.path, urllib.parse.parse_qs(url.query)

        if metodo == "GET":
            if p in ("/", "/index.html"):
                return self.responder(200, (BASE / "index.html").read_bytes(), "text/html; charset=utf-8")
            if p == "/ping":
                return self.responder(200, b"ok")
            if p == "/manifest.json":
                return self.responder(200, MANIFEST.encode(), "application/manifest+json; charset=utf-8")
            if p == "/icono.svg":
                return self.responder(200, ICONO_SVG.encode(), "image/svg+xml")
            if p == "/devices":
                with LOCK:
                    return self.json([ficha(k, d) for k, d in DEVICES.items()])
        if metodo == "POST" and p == "/scan":
            return self.json({"dispositivos": escanear(), "red": IP_LOCAL.rsplit(".", 1)[0] + ".0/24"})

        m = re.fullmatch(r"/d/([a-z0-9-]+)/(\w+)(?:/([\w.-]+))?", p)
        if not m:
            return self.responder(404, b"No encontrado")
        with LOCK:
            d = DEVICES.get(m[1])
        if d is None:
            return self.responder(404, "Dispositivo desconocido (reescanea)".encode())
        accion, arg = m[2], m[3]
        try:
            if metodo == "GET" and accion == "apps":
                return self.json(d.apps())
            if metodo == "GET" and accion == "online":
                return self.json({"online": bool(d.online())})
            if metodo == "GET" and accion == "estado":
                return self.json(d.estado())
            if metodo == "GET" and accion == "icon" and arg:
                cuerpo, ctype = d.icon(arg)
                return self.responder(200, cuerpo, ctype, "max-age=86400")  # los iconos no cambian
            if metodo == "POST" and accion == "key" and arg:
                modo = q.get("accion", [""])[0]  # start/end = mantener la tecla (pulsacion larga)
                if modo:
                    d.key_hold(arg, modo)
                else:
                    d.key(arg)
                    print(f"  {d.nombre}: tecla {arg}")
                return self.responder(204)
            if metodo == "POST" and accion == "text":
                t = q.get("t", [""])[0]
                d.text(t)
                print(f"  {d.nombre}: texto {t!r}")
                omitidos = d.omitidos(t)
                if omitidos:  # 200 con aviso: se escribio lo posible
                    return self.responder(200, f"Se omitieron: {omitidos}".encode())
                return self.responder(204)
            if metodo == "POST" and accion == "fuente" and arg:
                d.fuente(arg)
                print(f"  {d.nombre}: fuente {arg}")
                return self.responder(204)
            if metodo == "POST" and accion == "open":
                d.abrir(q.get("u", [""])[0])
                print(f"  {d.nombre}: abrir enlace")
                return self.responder(204)
            if metodo == "POST" and accion == "pair" and arg:
                d.pair(arg)
                return self.responder(204)
            if metodo == "POST" and accion == "launch" and arg:
                d.launch(arg)
                return self.responder(204)
        except DriverError as e:
            print(f"  {d.nombre}: {e}")
            return self.responder(502, str(e).encode())
        except (urllib.error.URLError, OSError, TimeoutError):
            return self.responder(502, f"{d.nombre} no responde".encode())
        self.responder(404, b"No encontrado")

    def do_GET(self):
        self.despachar("GET")

    def do_POST(self):
        self.despachar("POST")


def main():
    global IP_LOCAL
    ap = argparse.ArgumentParser()
    ap.add_argument("--puerto", type=int, default=8090)
    ap.add_argument("--ip", help="IP de este PC en la red de las TV (si tiene VPN o varias redes y elige mal)")
    a = ap.parse_args()

    IP_LOCAL = a.ip or ip_local()
    try:
        ip = ipaddress.IPv4Address(IP_LOCAL)
    except ValueError:
        ap.error(f"--ip no valida: {IP_LOCAL}")
    if not ip.is_private or ip.is_loopback:
        print(f"  Aviso: {IP_LOCAL} no parece una IP de red local (¿VPN?). Si no encuentra las TV, usa --ip 192.168.x.y")
    print(f"Red: {IP_LOCAL.rsplit('.', 1)[0]}.0/24 (se revisan solo esas 254 direcciones)")
    print("Buscando dispositivos en la red...")
    escanear()
    if not DEVICES:
        print("  (ninguno por ahora; enciende el dispositivo y pulsa 'Buscar' en la web)")
    srv = ThreadingHTTPServer(("0.0.0.0", a.puerto), Handler)
    srv.daemon_threads = True
    print("=" * 41)
    print("  CONTROL TV listo")
    for k, d in DEVICES.items():
        print(f"    - {d.nombre} ({d.marca}) en {d.ip}")
    print("=" * 41)
    print("Abre en el navegador de tu celular:")
    print(f"    http://{IP_LOCAL}:{a.puerto}")
    print("\nDeja esta ventana abierta. Ctrl+C para cerrar.\n")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
