"""Control TV - servidor local que reenvia comandos al Roku (ECP, puerto 8060).

Uso:  python servidor.py [--puerto 8090] [--roku 192.168.31.110]
Solo usa la biblioteca estandar de Python.
"""
import argparse
import base64
import concurrent.futures
import ipaddress
import os
import socket
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

BASE = Path(__file__).resolve().parent
CFG = BASE / "roku_ip.txt"
ROKU = ""  # se rellena al arrancar

# --- Control directo de la TV (plataforma Vestel / "Smart Center") ---------------
# Codigos tomados de la clase Mapper de la app oficial com.eu.smartcenter.
TV_CFG = BASE / "tv_ip.txt"
TV_KEYS = {
    "POWER": 1012, "HOME": 1046, "MENU": 1048, "EXIT": 1037, "BACK": 1010, "INFO": 1018,
    "SOURCE": 1056, "OK": 1053, "UP": 1020, "DOWN": 1019, "LEFT": 1021, "RIGHT": 1022,
    "VOL_UP": 1016, "VOL_DOWN": 1017, "MUTE": 1013, "PROG_UP": 1032, "PROG_DOWN": 1033,
    "PLAY": 1025, "PAUSE": 1049, "STOP": 1024, "REWIND": 1027, "FORWARD": 1028,
    "RED": 1055, "GREEN": 1054, "YELLOW": 1050, "BLUE": 1052,
    **{str(n): 1000 + n for n in range(10)},
}


MANIFEST = ('{"name":"Control TV","short_name":"Control TV","start_url":"/","display":"standalone",'
            '"orientation":"portrait","background_color":"#14161a","theme_color":"#14161a",'
            '"icons":[{"src":"/icono.svg","sizes":"any","type":"image/svg+xml","purpose":"any"}]}')
ICONO_SVG = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"><rect width="512" height="512" rx="110" fill="#14161a"/>'
             '<circle cx="256" cy="256" r="150" fill="#252931"/><circle cx="256" cy="256" r="62" fill="#6c3fd1"/>'
             '<path d="M256 120l34 40h-68zM256 392l-34-40h68zM120 256l40-34v68zM392 256l-40 34v-68z" fill="#e8eaed"/></svg>')


class TvError(Exception):
    pass


def tv_ip() -> str:
    return TV_CFG.read_text().strip() if TV_CFG.exists() else "192.168.31.145"


def _tv_http(ip: str, xml: bytes) -> str | None:
    """Canal DIAL 'SmartCenter'. Devuelve None si funciono, o el motivo del fallo."""
    req = urllib.request.Request(
        f"http://{ip}:56789/apps/SmartCenter", data=xml, method="POST",
        headers={"Content-Type": "text/plain; charset=ISO-8859-1", "application_name": "vestel smart center"})
    try:
        urllib.request.urlopen(req, timeout=3).read()
        return None
    except urllib.error.HTTPError as e:
        return f"SmartCenter HTTP {e.code}"
    except (urllib.error.URLError, OSError, TimeoutError) as e:
        return f"SmartCenter sin conexion ({e})"


def _tv_ws(ip: str, xml: bytes) -> str | None:
    """Canal WebSocket ws://IP:7681/ (el que usa la app Vestel en TV 'Dial2')."""
    try:
        s = socket.create_connection((ip, 7681), timeout=3)
    except OSError as e:
        return f"WebSocket sin conexion ({e})"
    try:
        s.settimeout(3)
        key = base64.b64encode(os.urandom(16)).decode()
        s.sendall((f"GET / HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nHost: {ip}\r\n"
                   f"Origin: http://{ip}\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
                   "Pragma: no-cache\r\nCache-Control: no-cache\r\n\r\n").encode())
        cab = s.recv(1024).split(b"\r\n")[0]
        if b" 101 " not in cab:
            return f"WebSocket {cab.decode(errors='replace')[9:].strip() or 'cerrado'}"
        mask, n = os.urandom(4), len(xml)
        head = bytes([0x81, 0x80 | n]) if n < 126 else bytes([0x81, 0x80 | 126]) + n.to_bytes(2, "big")
        s.sendall(head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(xml)))
        return None
    except (OSError, TimeoutError) as e:
        return f"WebSocket error ({e})"
    finally:
        s.close()


def tv_send(nombre: str) -> None:
    codigo = TV_KEYS.get(nombre.upper())
    if codigo is None:
        raise TvError(f"Tecla desconocida: {nombre}")
    xml = f"<?xml version='1.0' ?><remote><key code='{codigo}'/></remote>".encode("iso-8859-1")
    ip = tv_ip()
    fallos = []
    for canal in (_tv_http, _tv_ws):
        motivo = canal(ip, xml)
        if motivo is None:
            return
        fallos.append(motivo)
    raise TvError("La TV no acepta comandos (" + "; ".join(fallos) + "). Probablemente falta activar 'Virtual Remote' en la TV.")


def ip_local() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def es_roku(ip: str) -> bool:
    try:
        with urllib.request.urlopen(f"http://{ip}:8060/query/device-info", timeout=2) as r:
            return b"<vendor-name>Roku" in r.read()
    except Exception:
        return False


def puerto_abierto(ip: str) -> bool:
    try:
        with socket.create_connection((ip, 8060), timeout=1.5):
            return True
    except OSError:
        return False


def buscar_roku() -> str | None:
    print("Buscando el Roku en la red...")
    red = ipaddress.ip_network(ip_local() + "/24", strict=False)
    ips = [str(h) for h in red.hosts()]
    with concurrent.futures.ThreadPoolExecutor(64) as ex:
        abiertas = [ip for ip, ok in zip(ips, ex.map(puerto_abierto, ips)) if ok]
    return next((ip for ip in abiertas if es_roku(ip)), None)


def roku_post(path: str) -> None:
    req = urllib.request.Request(f"http://{ROKU}:8060{path}", method="POST")
    urllib.request.urlopen(req, timeout=3).read()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # silenciar el log por defecto
        pass

    def responder(self, code: int, body: bytes = b"", ctype: str = "text/plain; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def roku(self, accion):
        try:
            return accion()
        except (urllib.error.URLError, OSError, TimeoutError):
            self.responder(502, b"Roku no responde")

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        p = url.path
        if p in ("/", "/index.html"):
            return self.responder(200, (BASE / "index.html").read_bytes(), "text/html; charset=utf-8")
        if p == "/ping":
            return self.responder(200, b"ok")
        if p == "/manifest.json":
            return self.responder(200, MANIFEST.encode(), "application/manifest+json; charset=utf-8")
        if p == "/icono.svg":
            return self.responder(200, ICONO_SVG.encode(), "image/svg+xml")
        if p == "/apps":
            def f():
                with urllib.request.urlopen(f"http://{ROKU}:8060/query/apps", timeout=4) as r:
                    self.responder(200, r.read(), "text/xml; charset=utf-8")
            return self.roku(f)
        if p.startswith("/icon/") and p[6:].isalnum():
            def f():
                with urllib.request.urlopen(f"http://{ROKU}:8060/query/icon/{p[6:]}", timeout=4) as r:
                    self.responder(200, r.read(), r.headers.get("Content-Type", "image/png"))
            return self.roku(f)
        self.responder(404, b"No encontrado")

    def do_POST(self):
        url = urllib.parse.urlparse(self.path)
        p = url.path
        if p.startswith("/tv/key/"):
            try:
                tv_send(p[8:])
                print(f"  TV tecla: {p[8:]}")
                return self.responder(204)
            except TvError as e:
                print(f"  TV error: {e}")
                return self.responder(502, str(e).encode())
        if p.startswith("/key/") and p[5:].replace("_", "").isalnum():
            def f():
                roku_post(f"/keypress/{p[5:]}")
                print(f"  tecla: {p[5:]}")
                self.responder(204)
            return self.roku(f)
        if p == "/text":
            texto = urllib.parse.parse_qs(url.query).get("t", [""])[0]
            def f():
                for ch in texto:
                    roku_post("/keypress/Lit_" + urllib.parse.quote(ch, safe=""))
                print(f"  texto: {texto}")
                self.responder(204)
            return self.roku(f)
        if p.startswith("/launch/") and p[8:].isalnum():
            def f():
                roku_post(f"/launch/{p[8:]}")
                self.responder(204)
            return self.roku(f)
        self.responder(404, b"No encontrado")


def main():
    global ROKU
    ap = argparse.ArgumentParser()
    ap.add_argument("--puerto", type=int, default=8090)
    ap.add_argument("--roku", default="")
    a = ap.parse_args()

    ip = a.roku or (CFG.read_text().strip() if CFG.exists() else "")
    if not ip or not es_roku(ip):
        ip = buscar_roku()
    if not ip:
        raise SystemExit("No encontre ningun Roku. Revisa que este encendido y en el mismo WiFi.")
    ROKU = ip
    CFG.write_text(ip)

    srv = ThreadingHTTPServer(("0.0.0.0", a.puerto), Handler)
    srv.daemon_threads = True
    print("=" * 41)
    print(f"  CONTROL TV listo  (Roku en {ROKU})")
    print("=" * 41)
    print("Abre en el navegador de tu celular:")
    print(f"    http://{ip_local()}:{a.puerto}")
    print("\nDeja esta ventana abierta. Ctrl+C para cerrar.\n")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
