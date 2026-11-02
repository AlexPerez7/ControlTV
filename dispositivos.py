"""Controladores (drivers) por marca para Control TV. Solo biblioteca estandar.

Para agregar una marca nueva: crea una subclase de Driver, implementa `probe` (deteccion
automatica, opcional) y los metodos que soporte, y anadela a DRIVERS al final del archivo.
Cada driver declara en `caps` lo que puede hacer: keys, text, apps, volume.
"""
import re
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET


class DriverError(Exception):
    """Error con un mensaje apto para mostrar al usuario."""


def _http(url, method="GET", data=None, headers=None, timeout=4):
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(), r.headers.get("Content-Type", "application/octet-stream")


class Driver:
    marca = "?"
    caps: tuple = ()

    def __init__(self, ip: str, modelo: str = "", nombre: str = ""):
        self.ip, self.modelo = ip, modelo
        self.nombre = nombre or f"{self.marca} {modelo}".strip()

    @classmethod
    def probe(cls, ip: str):
        """Devuelve una instancia si `ip` es de esta marca, o None."""
        return None

    def online(self) -> bool:
        raise NotImplementedError

    def key(self, k: str):
        raise DriverError("Este dispositivo no admite teclas")

    def text(self, t: str):
        raise DriverError("Este dispositivo no admite texto")

    def apps(self) -> list:  # [{"id","name","icon"(url relativa o None)}]
        return []

    def launch(self, app_id: str):
        raise DriverError("Este dispositivo no admite abrir apps")

    def icon(self, app_id: str):
        raise DriverError("Sin iconos")

    def estado(self) -> dict:
        return {}


# --- Roku (ECP, puerto 8060) -----------------------------------------------------
class Roku(Driver):
    marca = "Roku"
    caps = ("keys", "text", "apps")

    @classmethod
    def probe(cls, ip):
        try:
            cuerpo, _ = _http(f"http://{ip}:8060/query/device-info", timeout=2)
            raiz = ET.fromstring(cuerpo)
        except Exception:
            return None
        if (raiz.findtext("vendor-name") or "") != "Roku":
            return None
        return cls(ip, raiz.findtext("model-name") or "", raiz.findtext("user-device-name") or "")

    def _post(self, path):
        _http(f"http://{self.ip}:8060{path}", "POST", b"", timeout=3)

    def online(self):
        try:
            _http(f"http://{self.ip}:8060/query/device-info", timeout=2)
            return True
        except Exception:
            return False

    def key(self, k):
        self._post("/keypress/" + urllib.parse.quote(k, safe="_"))

    def text(self, t):
        for ch in t:
            self._post("/keypress/Lit_" + urllib.parse.quote(ch, safe=""))

    def apps(self):
        cuerpo, _ = _http(f"http://{self.ip}:8060/query/apps")
        return [{"id": a.get("id"), "name": a.text or a.get("id"), "icon": True}
                for a in ET.fromstring(cuerpo).iter("app")]

    def launch(self, app_id):
        self._post("/launch/" + urllib.parse.quote(app_id, safe=""))

    def icon(self, app_id):
        return _http(f"http://{self.ip}:8060/query/icon/{urllib.parse.quote(app_id, safe='')}")


# --- Hisense con Twonky/DLNA + DIAL (TN40.V0021.K221...) --------------------------
class Hisense(Driver):
    """Volumen por UPnP RenderingControl (:9085) y apps por DIAL (:56789).

    La TV pide permiso en pantalla la primera vez: sin aceptarlo responde 401 a SetVolume.
    `SetMute` responde 200 pero no tiene efecto, asi que el mute se emula con el volumen.
    """
    marca = "Hisense"
    caps = ("volume", "apps")
    PASO = 2
    APPS_DIAL = ("YouTube", "Netflix")
    _NS = "urn:schemas-upnp-org:service:RenderingControl:1"

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self._vol = None       # ultimo volumen conocido
        self._antes_mute = None  # volumen previo al mute emulado

    @classmethod
    def probe(cls, ip):
        try:
            cuerpo, _ = _http(f"http://{ip}:56790/dd.xml", timeout=2)
        except Exception:
            return None
        texto = cuerpo.decode("utf-8", "replace")
        if "hisense" not in texto.lower():
            return None
        m = re.search(r"<modelName>([^<]*)", texto)
        return cls(ip, m.group(1).replace("hisense_", "") if m else "")

    def _soap(self, accion, extra=""):
        cuerpo = (f'<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
                  f's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
                  f'<u:{accion} xmlns:u="{self._NS}"><InstanceID>0</InstanceID><Channel>Master</Channel>{extra}'
                  f'</u:{accion}></s:Body></s:Envelope>').encode()
        try:
            r, _ = _http(f"http://{self.ip}:9085/RenderingControl/Control", "POST", cuerpo,
                         {"Content-Type": 'text/xml; charset="utf-8"', "SOAPACTION": f'"{self._NS}#{accion}"'})
        except urllib.error.HTTPError as e:
            if e.code == 401:
                raise DriverError("La TV pide permiso: acepta el aviso en su pantalla y vuelve a intentarlo")
            raise DriverError(f"La TV respondio HTTP {e.code}")
        return r.decode("utf-8", "replace")

    def online(self):
        try:
            self.get_volume()
            return True
        except Exception:
            return False

    def get_volume(self) -> int:
        m = re.search(r"<CurrentVolume>(\d+)", self._soap("GetVolume"))
        if not m:
            raise DriverError("La TV no informo el volumen")
        self._vol = int(m.group(1))
        return self._vol

    def set_volume(self, v: int):
        v = max(0, min(100, int(v)))
        self._soap("SetVolume", f"<DesiredVolume>{v}</DesiredVolume>")
        self._vol = v

    def key(self, k):
        if k == "VolumeMute":
            if self._antes_mute is None:
                self._antes_mute = self.get_volume()
                self.set_volume(0)
            else:
                v, self._antes_mute = self._antes_mute, None
                self.set_volume(v)
            return
        if k in ("VolumeUp", "VolumeDown"):
            if self._antes_mute is not None:  # subir/bajar cancela el mute emulado
                self._vol, self._antes_mute = self._antes_mute, None
            base = self._vol if self._vol is not None else self.get_volume()
            self.set_volume(base + (self.PASO if k == "VolumeUp" else -self.PASO))
            return
        raise DriverError("Esta TV solo admite volumen por red (teclas y apagado: pendiente)")

    def estado(self):
        return {"volume": self.get_volume(), "muted": self._antes_mute is not None}

    def apps(self):
        res = []
        for nombre in self.APPS_DIAL:
            try:
                _http(f"http://{self.ip}:56789/apps/{nombre}", timeout=2)
                res.append({"id": nombre, "name": nombre, "icon": False})
            except Exception:
                pass
        return res

    def launch(self, app_id):
        if app_id not in self.APPS_DIAL:
            raise DriverError("App no soportada")
        _http(f"http://{self.ip}:56789/apps/{app_id}", "POST", b"", {"Content-Type": "text/plain"})


# --- Vestel / onn (experimental: la TV acepta conexion pero no los comandos) -------
class Vestel(Driver):
    """Solo manual (dispositivos.json). Codigos de la app com.eu.smartcenter. Ver plan.md."""
    marca = "Vestel"
    caps = ("keys",)
    CODIGOS = {"Power": 1012, "Home": 1046, "Back": 1010, "Info": 1018, "Select": 1053, "Up": 1020, "Down": 1019,
               "Left": 1021, "Right": 1022, "VolumeUp": 1016, "VolumeDown": 1017, "VolumeMute": 1013,
               "Play": 1025, "Rev": 1027, "Fwd": 1028}

    def online(self):
        try:
            socket.create_connection((self.ip, 56789), timeout=1.5).close()
            return True
        except OSError:
            return False

    def key(self, k):
        if k not in self.CODIGOS:
            raise DriverError("Tecla no disponible")
        xml = f"<?xml version='1.0' ?><remote><key code='{self.CODIGOS[k]}'/></remote>".encode("iso-8859-1")
        try:
            _http(f"http://{self.ip}:56789/apps/SmartCenter", "POST", xml,
                  {"Content-Type": "text/plain; charset=ISO-8859-1", "application_name": "vestel smart center"})
        except urllib.error.HTTPError as e:
            raise DriverError(f"La TV no acepta comandos (HTTP {e.code}); falta 'Virtual Remote'")


# Orden de deteccion; Vestel no se autodetecta.
DRIVERS = {"roku": Roku, "hisense": Hisense, "vestel": Vestel}


def ssdp_ips(ip_local: str, espera: float = 3.0) -> set:
    """IPs que responden a un M-SEARCH SSDP (casi todas las smart TV y streamers)."""
    msg = (b'M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\n'
           b"MX: 2\r\nST: ssdp:all\r\n\r\n")
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    ips = set()
    try:
        s.bind((ip_local, 0))
        s.settimeout(0.5)
        s.sendto(msg, ("239.255.255.250", 1900))
        fin = time.time() + espera
        while time.time() < fin:
            try:
                _, a = s.recvfrom(4096)
                ips.add(a[0])
            except socket.timeout:
                pass
    except OSError:
        pass
    finally:
        s.close()
    return ips


def descubrir(ip_local: str, extra_ips=()) -> list:
    """Detecta dispositivos soportados en la red. Devuelve instancias de Driver."""
    encontrados = []
    for ip in sorted(ssdp_ips(ip_local) | set(extra_ips), key=lambda x: tuple(map(int, x.split(".")))):
        for clase in (Roku, Hisense):
            d = clase.probe(ip)
            if d:
                encontrados.append(d)
                break
    return encontrados
