"""Controladores (drivers) por marca para Control TV. Solo biblioteca estandar.

Para agregar una marca nueva: crea una subclase de Driver, implementa `probe` (deteccion
automatica, opcional) y los metodos que soporte, y anadela a DRIVERS al final del archivo.
Cada driver declara en `caps` lo que puede hacer: keys, text, apps, volume.
"""
import base64
import datetime
import hashlib
import json
import os
import re
import secrets
import socket
import ssl
import threading
import time
import unicodedata
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
    teclas = None  # None = todas; o la tupla de teclas que admite (la web oculta el resto)
    largo = False  # True si admite mantener una tecla (key_hold)

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

    def omitidos(self, t: str) -> str:
        """Caracteres de `t` que `text()` no puede escribir en este dispositivo ("" si escribe todo)."""
        return ""

    def key_hold(self, k: str, accion: str):
        """Mantener una tecla: accion "start" al pulsar y "end" al soltar."""
        raise DriverError("Este dispositivo no admite mantener teclas")

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

    def pair(self, arg: str):
        raise DriverError("Este dispositivo no necesita emparejarse")

    def abrir(self, url: str):
        """Abre un enlace (p. ej. un video compartido) en el dispositivo."""
        raise DriverError("Este dispositivo no admite abrir enlaces")

    def fuente(self, nombre: str):
        """Cambia la entrada de video (HDMI, TV...)."""
        raise DriverError("Este dispositivo no admite cambiar de fuente")

    def preparar(self):
        """Comprobaciones lentas por red que se hacen al escanear (fuera del lock del servidor)."""


# --- Roku (ECP, puerto 8060) -----------------------------------------------------
class Roku(Driver):
    marca = "Roku"
    caps = ("keys", "text", "apps")

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self._iconos = {}  # app_id -> (cuerpo, content-type)

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
        # En memoria: el icono de una app no cambia, y abrir "Aplicaciones" bajaba ~300 KB del Roku cada vez
        if app_id not in self._iconos:
            self._iconos[app_id] = _http(f"http://{self.ip}:8060/query/icon/{urllib.parse.quote(app_id, safe='')}")
        return self._iconos[app_id]


# --- Hisense con Twonky/DLNA + DIAL (TN40.V0021.K221...) --------------------------
class Hisense(Driver):
    """Volumen por UPnP RenderingControl (:9085) y apps por DIAL (:56789).

    La TV pide permiso en pantalla la primera vez: sin aceptarlo responde 401 a SetVolume.
    `SetMute` responde 200 pero no tiene efecto, asi que el mute se emula con el volumen.
    """
    marca = "Hisense"
    caps = ("volume", "apps")
    teclas = ("VolumeUp", "VolumeDown", "VolumeMute")
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
        # Solo el puerto: GetVolume da 401 hasta aceptar el permiso en la TV, y la TV no estaria "desconectada"
        try:
            socket.create_connection((self.ip, 9085), timeout=1.5).close()
            return True
        except OSError:
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

    VOL_SIN_MUTE = 10  # al quitar el silencio sin saber el volumen anterior (p. ej. tras reiniciar el servidor)

    def key(self, k):
        # Siempre se lee el volumen actual: el control fisico de la TV tambien lo cambia. Volumen 0 = silenciado.
        if k == "VolumeMute":
            v = self.get_volume()
            if v == 0:
                self.set_volume(self._antes_mute or self.VOL_SIN_MUTE)
                self._antes_mute = None
            else:
                self._antes_mute = v
                self.set_volume(0)
            return
        if k in ("VolumeUp", "VolumeDown"):
            v = self.get_volume()
            if v == 0 and self._antes_mute:  # subir/bajar desde el silencio parte del volumen previo
                v = self._antes_mute
            self._antes_mute = None
            self.set_volume(v + (self.PASO if k == "VolumeUp" else -self.PASO))
            return
        raise DriverError("Esta TV solo admite volumen por red (teclas y apagado: pendiente)")

    def estado(self):
        v = self.get_volume()
        return {"volume": v, "muted": v == 0}

    def apps(self):
        res = []
        for nombre in self.APPS_DIAL:
            try:
                _http(f"http://{self.ip}:56789/apps/{nombre}", timeout=2)
                res.append({"id": nombre, "name": nombre, "icon": False,
                            "color": {"YouTube": "#c4302b", "Netflix": "#b20710"}.get(nombre)})
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

    @property
    def teclas(self):
        return tuple(self.CODIGOS)

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


# --- Android TV (Caixun y cualquier TV/box con Android TV / Google TV) -----------
# Protocolo "Android TV Remote v2": TLS con certificado de cliente + protobuf.
# Pairing en :6467 (la TV muestra un codigo de 6 caracteres hex) y control en :6466.
def _varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def _pb(*campos):
    """Codifica campos protobuf: (numero, int | bytes | str)."""
    out = b""
    for num, v in campos:
        if isinstance(v, int):
            out += _varint(num << 3) + _varint(v)
        else:
            v = v.encode() if isinstance(v, str) else v
            out += _varint(num << 3 | 2) + _varint(len(v)) + v
    return out


def _pb_leer(data):
    """Decodifica un mensaje protobuf a {numero: [valores]} (ints o bytes)."""
    res, i = {}, 0
    while i < len(data):
        clave, i = _leer_varint(data, i)
        num, tipo = clave >> 3, clave & 7
        if tipo == 0:
            v, i = _leer_varint(data, i)
        elif tipo == 2:
            n, i = _leer_varint(data, i)
            v, i = data[i:i + n], i + n
        elif tipo == 1:
            v, i = data[i:i + 8], i + 8
        elif tipo == 5:
            v, i = data[i:i + 4], i + 4
        else:
            break
        res.setdefault(num, []).append(v)
    return res


def _leer_varint(data, i):
    n = sh = 0
    while True:
        b = data[i]
        i += 1
        n |= (b & 0x7F) << sh
        sh += 7
        if not b & 0x80:
            return n, i


def _der(data, i=0):
    """Lee un TLV DER en data[i:]; devuelve (tag, contenido, indice_siguiente)."""
    tag, n = data[i], data[i + 1]
    i += 2
    if n & 0x80:
        k = n & 0x7F
        n = int.from_bytes(data[i:i + k], "big")
        i += k
    return tag, data[i:i + n], i + n


def _hijos(contenido):
    res, i = [], 0
    while i < len(contenido):
        tag, c, i = _der(contenido, i)
        res.append((tag, c))
    return res


def _rsa_de_cert(der):
    """(modulo, exponente) como bytes big-endian, de un certificado DER RSA."""
    _, tbs, _ = _der(_hijos_raiz(der))
    h = _hijos(tbs)
    if h[0][0] == 0xA0:  # version explicita
        h = h[1:]
    spki = _hijos(h[5][1])  # serial, sigalg, emisor, validez, sujeto, clave publica
    bits = spki[1][1][1:]  # BIT STRING sin el byte de relleno
    _, rsa, _ = _der(bits)
    n, e = _hijos(rsa)
    return n[1].lstrip(b"\0"), e[1].lstrip(b"\0")


def _hijos_raiz(der):
    _, c, _ = _der(der)
    return c


# --- Certificado de cliente sin openssl (Windows no lo trae) -----------------------
# RSA y X.509 minimos en Python puro: solo se usa una vez, para crear androidtv_cert.pem/androidtv_clave.pem.
_PRIMOS_CHICOS = [p for p in range(3, 2000, 2) if all(p % q for q in range(3, int(p ** .5) + 1, 2))]


def _es_primo(n, rondas=40):
    """Miller-Rabin (probabilidad de error < 4^-40)."""
    if n < 2 or n % 2 == 0:
        return n == 2
    for p in _PRIMOS_CHICOS:
        if n % p == 0:
            return n == p
    d, r = n - 1, 0
    while d % 2 == 0:
        d, r = d // 2, r + 1
    for _ in range(rondas):
        x = pow(2 + secrets.randbelow(n - 3), d, n)
        if x in (1, n - 1):
            continue
        for _ in range(r - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def _primo(bits, e):
    while True:
        p = secrets.randbits(bits) | (3 << (bits - 2)) | 1  # dos bits altos: n tiene exactamente 2*bits
        if p % e != 1 and _es_primo(p):
            return p


def _der_tlv(tag, contenido):
    n = len(contenido)
    largo = bytes([n]) if n < 0x80 else bytes([0x80 | ((n.bit_length() + 7) // 8)]) + n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([tag]) + largo + contenido


def _der_int(n):
    return _der_tlv(0x02, n.to_bytes(n.bit_length() // 8 + 1, "big"))  # +1 byte: nunca negativo


def _der_seq(*partes):
    return _der_tlv(0x30, b"".join(partes))


def _der_oid(texto):
    a, b, *resto = map(int, texto.split("."))
    out = bytes([40 * a + b])
    for v in resto:
        grupo = [v & 0x7F]
        while v > 0x7F:
            v >>= 7
            grupo.append(0x80 | (v & 0x7F))
        out += bytes(reversed(grupo))
    return _der_tlv(0x06, out)


def _pem(tipo, der):
    b = base64.b64encode(der).decode()
    return f"-----BEGIN {tipo}-----\n" + "\n".join(b[i:i + 64] for i in range(0, len(b), 64)) + f"\n-----END {tipo}-----\n"


def certificado_autofirmado(cn="ControlTV", bits=2048, dias=3650):
    """(cert_pem, clave_pem): RSA `bits` + X.509 v3 autofirmado con SHA-256, como `openssl req -x509 -newkey rsa`."""
    e = 65537
    while True:
        p, q = _primo(bits // 2, e), _primo(bits // 2, e)
        if p != q:
            break
    n, fi = p * q, (p - 1) * (q - 1)
    d = pow(e, -1, fi)
    rsa_alg = _der_seq(_der_oid("1.2.840.113549.1.1.1"), b"\x05\x00")
    firma_alg = _der_seq(_der_oid("1.2.840.113549.1.1.11"), b"\x05\x00")  # sha256WithRSAEncryption
    nombre = _der_seq(_der_tlv(0x31, _der_seq(_der_oid("2.5.4.3"), _der_tlv(0x0C, cn.encode()))))
    ahora = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    hora = lambda t: _der_tlv(0x17, t.strftime("%y%m%d%H%M%SZ").encode())  # UTCTime: vale hasta 2049
    tbs = _der_seq(
        _der_tlv(0xA0, _der_int(2)),  # version v3
        _der_int(secrets.randbits(63) | 1),
        firma_alg, nombre,
        _der_seq(hora(ahora - datetime.timedelta(days=1)), hora(ahora + datetime.timedelta(days=dias))),
        nombre,
        _der_seq(rsa_alg, _der_tlv(0x03, b"\x00" + _der_seq(_der_int(n), _der_int(e)))))
    info = bytes.fromhex("3031300d060960864801650304020105000420") + hashlib.sha256(tbs).digest()  # DigestInfo SHA-256
    k = (n.bit_length() + 7) // 8
    em = b"\x00\x01" + b"\xff" * (k - len(info) - 3) + b"\x00" + info  # PKCS#1 v1.5
    firma = pow(int.from_bytes(em, "big"), d, n).to_bytes(k, "big")
    cert = _der_seq(tbs, firma_alg, _der_tlv(0x03, b"\x00" + firma))
    pkcs1 = _der_seq(*map(_der_int, (0, n, e, d, p, q, d % (p - 1), d % (q - 1), pow(q, -1, p))))
    pkcs8 = _der_seq(_der_int(0), rsa_alg, _der_tlv(0x04, pkcs1))
    return _pem("CERTIFICATE", cert), _pem("PRIVATE KEY", pkcs8)


class AndroidTV(Driver):
    marca = "Android TV"
    PUERTO_PAIR, PUERTO_REMOTE = 6467, 6466
    CERT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "androidtv_cert.pem")
    CLAVE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "androidtv_clave.pem")
    # Nombres de tecla (Roku ECP) -> KEYCODE de Android
    TECLAS = {"Up": 19, "Down": 20, "Left": 21, "Right": 22, "Select": 23, "Back": 4, "Home": 3,
              "Power": 26, "PowerOff": 223, "PowerOn": 224, "VolumeUp": 24, "VolumeDown": 25,
              "VolumeMute": 164, "Play": 85, "Rev": 89, "Fwd": 90, "Info": 165, "Search": 84,
              "Enter": 66, "Backspace": 67, "InstantReplay": 88,
              # Sin "Source" (178) ni "Guide" (172): en la Caixun Fuente abre la lista de canales y Guia cambia a la entrada
              # de TV en vivo y deja la TV atascada en "Sin senal" (ver plan.md)
              "Settings": 176, "Menu": 82, "ChannelUp": 166, "ChannelDown": 167,
              **{f"Num{n}": 7 + n for n in range(10)}}
    # id (va en la URL: solo letras, numeros, - . _) -> (nombre que se muestra, enlace que abre la app, color)
    APPS = {"youtube": ("YouTube", "vnd.youtube://", "#c4302b"),
            "netflix": ("Netflix", "nflx://www.netflix.com", "#b20710"),
            "prime-video": ("Prime Video", "https://app.primevideo.com", "#0a7bb5"),
            "disney-plus": ("Disney+", "https://www.disneyplus.com", "#113ccf"),
            "hbo-max": ("HBO Max", "https://play.hbomax.com", "#5822b4"),
            "spotify": ("Spotify", "spotify://", "#1a9c47")}
    _lock_cert = threading.Lock()

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self._paired = None  # None = sin comprobar
        self._sock = None
        self._listo = threading.Event()
        self._envio = threading.Lock()
        self._hold = None  # (tecla, temporizador) mientras hay una tecla mantenida
        self._hold_lock = threading.Lock()
        self._conn = threading.RLock()  # protege _sock/_listo (varias peticiones HTTP a la vez)
        self._pair_sock = None
        self._pair_desde = 0.0  # cuando se mostro el codigo en la TV (caduca a los 2 min)
        self._ime = (0, 0)  # contadores (ime, campo) de la ultima edicion de texto que informo la TV
        self._vol = None  # (nivel, maximo, silenciado) segun la ultima notificacion de la TV
        self._fallo_hasta = 0.0
        self._ultimo_msg = 0.0  # cuando llego el ultimo mensaje de la TV por la conexion de control  # tras fallar al conectar, las teclas fallan al instante hasta este momento

    @property
    def caps(self):  # sin red: el estado lo fija preparar() al escanear
        return ("keys", "volume", "text", "apps", "link") if self._paired else ("pair",)

    @property
    def teclas(self):
        return tuple(self.TECLAS)

    def preparar(self):
        if not self._paired:
            self._paired = self._comprobar_pareado()

    @classmethod
    def probe(cls, ip):
        for p in (cls.PUERTO_PAIR, cls.PUERTO_REMOTE):
            try:
                socket.create_connection((ip, p), timeout=1.5).close()
            except OSError:
                return None
        return cls(ip, "", cls._nombre_cast(ip))

    @staticmethod
    def _nombre_cast(ip):
        """Nombre que el usuario le puso a la TV (lo publica por Chromecast en :8008); "" si no lo da."""
        try:
            cuerpo, _ = _http(f"http://{ip}:8008/setup/eureka_info?params=name", timeout=2)
            return str(json.loads(cuerpo).get("name") or "")
        except Exception:
            return ""

    # -- TLS ---------------------------------------------------------------
    @classmethod
    def _asegurar_cert(cls):
        with cls._lock_cert:
            if os.path.exists(cls.CERT) and os.path.exists(cls.CLAVE):
                return
            try:
                cert, clave = certificado_autofirmado()
                fd = os.open(cls.CLAVE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                with os.fdopen(fd, "w") as f:
                    f.write(clave)
                with open(cls.CERT, "w") as f:
                    f.write(cert)
            except OSError as e:
                raise DriverError(f"No se pudo guardar el certificado: {e}")

    def _conectar(self, puerto, espera=5):
        self._asegurar_cert()
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        ctx.load_cert_chain(self.CERT, self.CLAVE)
        raw = socket.create_connection((self.ip, puerto), timeout=espera)
        return ctx.wrap_socket(raw)

    @staticmethod
    def _enviar_msg(sock, datos):
        sock.sendall(_varint(len(datos)) + datos)

    @staticmethod
    def _leer_msg(sock):
        n = sh = 0
        while True:
            b = sock.recv(1)
            if not b:
                raise ConnectionError("conexión cerrada")
            n |= (b[0] & 0x7F) << sh
            sh += 7
            if not b[0] & 0x80:
                break
        buf = b""
        while len(buf) < n:
            parte = sock.recv(n - len(buf))
            if not parte:
                raise ConnectionError("conexión cerrada")
            buf += parte
        return buf

    # -- emparejamiento ------------------------------------------------------
    def _comprobar_pareado(self):
        """Pareado = la TV nos habla (envia su configuracion) al conectar al puerto de control."""
        try:
            s = self._conectar(self.PUERTO_REMOTE, 3)
        except (OSError, DriverError):
            return False
        try:
            s.settimeout(3)
            return 1 in _pb_leer(self._leer_msg(s))
        except (OSError, ConnectionError, IndexError):
            return False
        finally:
            s.close()

    def pair(self, arg):
        if arg == "start":
            return self._pair_inicio()
        return self._pair_codigo(arg)

    @staticmethod
    def _pair_msg(campo, cuerpo):
        return _pb((1, 2), (2, 200), (campo, cuerpo))

    def _pair_inicio(self):
        self._cerrar_pair()
        try:
            s = self._conectar(self.PUERTO_PAIR)
            s.settimeout(8)
            enc = _pb((1, 3), (2, 6))  # HEXADECIMAL, 6 simbolos
            pasos = [(10, _pb((1, "atvremote"), (2, "Control TV"))),   # PairingRequest -> ack (11)
                     (20, _pb((1, enc), (3, 1))),                       # Options (rol INPUT) -> Options
                     (30, _pb((1, enc), (2, 1)))]                       # Configuration -> ack (31)
            for campo, cuerpo in pasos:
                self._enviar_msg(s, self._pair_msg(campo, cuerpo))
                resp = _pb_leer(self._leer_msg(s))
                if resp.get(2, [200])[0] != 200:
                    raise DriverError(f"La TV rechazo el emparejamiento (estado {resp[2][0]})")
        except (OSError, ConnectionError, IndexError) as e:
            self._cerrar_pair()
            raise DriverError(f"No se pudo iniciar el emparejamiento: {e}")
        self._pair_sock = s
        self._pair_desde = time.time()

    def _cerrar_pair(self):
        s, self._pair_sock = self._pair_sock, None
        if s:
            try:
                s.close()
            except OSError:
                pass

    def _pair_codigo(self, codigo):
        codigo = codigo.strip().upper()
        if not re.fullmatch(r"[0-9A-F]{6}", codigo):
            raise DriverError("El codigo son 6 caracteres (0-9, A-F)")
        s = self._pair_sock
        if s is None:
            raise DriverError("Primero inicia el emparejamiento")
        if time.time() - self._pair_desde > 120:
            self._cerrar_pair()
            raise DriverError("El codigo caduco: vuelve a pulsar 'Mostrar codigo en la TV'")
        try:
            cn, ce = _rsa_de_cert(ssl.PEM_cert_to_DER_cert(open(self.CERT).read()))
            sn, se = _rsa_de_cert(s.getpeercert(binary_form=True))
            raw = bytes.fromhex(codigo)
            h = hashlib.sha256(cn + ce + sn + se + raw[1:]).digest()
            if h[0] != raw[0]:
                raise DriverError("Código incorrecto")
            self._enviar_msg(s, self._pair_msg(40, _pb((1, h))))
            resp = _pb_leer(self._leer_msg(s))
            if resp.get(2, [200])[0] != 200 or 41 not in resp:
                raise DriverError("La TV no aceptó el código")
        except (OSError, ConnectionError, IndexError, ValueError) as e:
            raise DriverError(f"Fallo el emparejamiento: {e}")
        self._cerrar_pair()
        self._paired = True

    # -- control -------------------------------------------------------------
    def online(self):
        # Conexion de control viva y la TV hablo hace poco (manda pings): responde sin tocar la red. Si la TV se apaga
        # de golpe el socket puede seguir "abierto", por eso cuenta el ultimo mensaje y no solo que exista.
        if self._sock is not None and self._listo.is_set() and time.time() - self._ultimo_msg < self.VIVA:
            return True
        try:
            socket.create_connection((self.ip, self.PUERTO_REMOTE), timeout=1.5).close()
            return True
        except OSError:
            return False

    def _lector(self, s):
        try:
            s.settimeout(None)
            while True:
                m = _pb_leer(self._leer_msg(s))
                self._ultimo_msg = time.time()
                if 1 in m:  # la TV pide nuestra configuracion
                    dev = _pb((1, "Control TV"), (2, "Control TV"), (3, 1), (4, "1"), (5, "control.tv"), (6, "1.0.0"))
                    self._enviar(s, _pb((1, _pb((1, 622), (2, dev)))))
                elif 2 in m:  # set_active
                    self._enviar(s, _pb((2, _pb((1, 622)))))
                elif 40 in m:  # remote_start: listo para recibir teclas
                    self._listo.set()
                elif 50 in m:  # volumen: nivel (7), maximo (6), silenciado (8)
                    v = _pb_leer(m[50][0])
                    self._vol = (v.get(7, [0])[0], v.get(6, [0])[0], bool(v.get(8, [0])[0]))
                elif 21 in m:  # la TV informa los contadores de su campo de texto: se reutilizan al escribir
                    v = _pb_leer(m[21][0])
                    self._ime = (v.get(1, [0])[0], v.get(2, [0])[0])
                elif 8 in m:  # ping
                    v = _pb_leer(m[8][0]).get(1, [0])[0]
                    self._enviar(s, _pb((9, _pb((1, v)))))
        except (OSError, ConnectionError, IndexError):
            pass
        finally:
            with self._conn:
                if self._sock is s:  # una conexion vieja no debe apagar la senal de una nueva
                    self._sock = None
                    self._listo.clear()
            try:
                s.close()
            except OSError:
                pass

    def _enviar(self, s, datos):
        with self._envio:
            self._enviar_msg(s, datos)

    ESPERA_TECLADO = 0.4
    ESPERA_TRAS_FALLO = 3.0
    VIVA = 12.0  # segundos sin mensajes de la TV tras los que online() vuelve a comprobar por red

    def _asegurar_conexion(self):
        with self._conn:
            if self._sock is not None and self._listo.is_set():
                return self._sock
            if time.time() < self._fallo_hasta:  # no esperar otro timeout por cada tecla en cola
                raise DriverError(f"{self.nombre} no responde (¿está encendida?)")
            self._listo.clear()
            try:
                s = self._conectar(self.PUERTO_REMOTE)
            except OSError:
                self._fallo_hasta = time.time() + self.ESPERA_TRAS_FALLO
                raise DriverError(f"{self.nombre} no responde (¿está encendida?)")
            self._sock = s
            threading.Thread(target=self._lector, args=(s,), daemon=True).start()
            if not self._listo.wait(6):
                self._sock = None
                s.close()
                self._paired = False
                raise DriverError("La TV no aceptó la conexión: vuelve a emparejarla")
            # La TV crea un control virtual (uinput) por cada conexion; una tecla enviada antes de que
            # exista se pierde (la primera pulsacion "no hacia nada").
            time.sleep(self.ESPERA_TECLADO)
            return s

    def _mandar(self, datos):
        for intento in (0, 1):
            s = self._asegurar_conexion()
            try:
                self._enviar(s, datos)
                return
            except OSError:
                with self._conn:
                    if self._sock is s:
                        self._sock = None
                        self._listo.clear()
                if intento:
                    raise DriverError(f"{self.nombre} no responde")

    largo = True
    HOLD_MAX = 5.0  # segundos: si no llega "end", se suelta sola (para que la tecla no quede pegada)
    CORTA, START_LARGA, END_LARGA = 3, 1, 2  # RemoteDirection de Android TV

    def _inyectar(self, k, direccion):
        self._mandar(_pb((10, _pb((1, self.TECLAS[k]), (2, direccion)))))

    def key(self, k):
        if k not in self.TECLAS:
            raise DriverError("Tecla no disponible en esta TV")
        self._inyectar(k, self.CORTA)

    def key_hold(self, k, accion):
        if k not in self.TECLAS:
            raise DriverError("Tecla no disponible en esta TV")
        if accion not in ("start", "end"):
            raise DriverError("Acción no válida")
        with self._hold_lock:
            if self._hold:  # cualquier orden suelta antes la tecla que siga mantenida
                previa, temporizador = self._hold
                temporizador.cancel()
                self._hold = None
                self._inyectar(previa, self.END_LARGA)
            if accion == "start":
                self._inyectar(k, self.START_LARGA)
                t = threading.Timer(self.HOLD_MAX, self._soltar_por_tiempo, args=(k,))
                t.daemon = True
                t.start()
                self._hold = (k, t)

    def _soltar_por_tiempo(self, k):
        with self._hold_lock:
            if self._hold and self._hold[0] == k:
                self._hold = None
                try:
                    self._inyectar(k, self.END_LARGA)
                except DriverError:
                    pass

    def estado(self):
        self._asegurar_conexion()
        for _ in range(20):  # la TV manda el volumen justo despues de conectar
            if self._vol:
                break
            time.sleep(0.05)
        if not self._vol:
            return {}
        nivel, maximo, mute = self._vol
        return {"volume": nivel, "max": maximo, "muted": mute}

    def apps(self):
        return [{"id": i, "name": n, "icon": False, "color": c} for i, (n, _, c) in self.APPS.items()]

    def launch(self, app_id):
        if app_id not in self.APPS:
            raise DriverError("App no soportada")
        self._mandar(_pb((90, _pb((1, self.APPS[app_id][1])))))

    # Texto: mensaje de edicion (IME) del protocolo, el mismo que usa la app Google TV. Las teclas NO sirven: entran como
    # teclado numerico y Android solo convierte los digitos en caracteres. Comprobado en la Caixun (YouTube TV): inserta el
    # texto en la posicion del cursor del campo enfocado, con mayusculas, tildes y cualquier simbolo.
    MAX_TEXTO = 200

    @staticmethod
    def mensaje_texto(texto, ime=0, campo=0):
        """RemoteMessage.remote_ime_batch_edit (21) que inserta `texto` (contadores que informa la TV; 0 si aun no hay)."""
        cursor = max(len(texto.encode("utf-16-le")) // 2 - 1, 0)  # posiciones en unidades UTF-16, como Android
        estado = _pb((1, cursor), (2, cursor), (3, texto))
        return _pb((21, _pb((1, ime), (2, campo), (3, _pb((1, 1), (2, estado))))))

    # Signos que piden Shift: la TV los escribe sin Shift ("!" llego como "1") y "?" **activa la busqueda por voz** de
    # YouTube TV (probado en la Caixun). Se omiten y se avisa, en vez de arriesgarse a escribir otro caracter en silencio.
    SIGNOS_CON_SHIFT = frozenset('!@#$%^&*()_+{}|:"<>?~')

    @classmethod
    def a_ascii(cls, texto):
        """(texto, omitidos). El campo de YouTube TV descarta los caracteres no ASCII (probado: "Ñandú" llego como "and"),
        asi que se escribe la letra base (ñ -> n, é -> e); lo que no tiene equivalente (¿, ¡, €...) o pide Shift se omite y se avisa."""
        salida, omitidos = [], []
        for ch in unicodedata.normalize("NFD", texto):
            if unicodedata.combining(ch):
                continue
            (salida if ord(ch) < 128 and ch not in cls.SIGNOS_CON_SHIFT else omitidos).append(ch)
        return "".join(salida), "".join(dict.fromkeys(omitidos))

    def omitidos(self, t):
        return self.a_ascii(t)[1]

    def text(self, t):
        t, _ = self.a_ascii(t.replace("\r", "").replace("\n", " "))
        if not t:
            raise DriverError("No hay texto que escribir (la TV solo acepta caracteres ASCII; las tildes se quitan)")
        if len(t) > self.MAX_TEXTO:
            raise DriverError(f"Texto demasiado largo (maximo {self.MAX_TEXTO} caracteres)")
        self._mandar(self.mensaje_texto(t, *self._ime))

    _YOUTUBE = re.compile(r"(?:youtu\.be/|youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|live/|embed/))([A-Za-z0-9_-]{11})")

    @classmethod
    def enlace_tv(cls, url):
        """Los enlaces de YouTube pasan al esquema propio de la app (los https:// generales hacen que
        Android pregunte "Abrir con"; ver plan.md). Lo demas se manda tal cual."""
        m = cls._YOUTUBE.search(url)
        return "vnd.youtube://" + m.group(1) if m else url

    # Entradas TIF de TVs con chip MediaTek (la Caixun). Otras marcas usan otros ids: el enlace solo
    # se ignora. "Inicio" no es una entrada: desde una entrada la tecla Home no vuelve, asi que se abre
    # una app y luego Home (ver plan.md).
    fuentes = ("HDMI1", "HDMI2", "HDMI3", "Composite", "TV", "Inicio")
    FUENTES = {
        "TV": "HW0", "Composite": "HW1", "HDMI1": "HW4", "HDMI2": "HW5", "HDMI3": "HW6",
    }
    _SERVICIOS = {"HW0": ".tuner.TunerInputService", "HW1": ".composite.CompositeInputService"}

    def fuente(self, nombre):
        if nombre == "Inicio":
            self.launch("youtube")
            time.sleep(3)
            return self.key("Home")
        hw = self.FUENTES.get(nombre)
        if not hw:
            raise DriverError("Fuente no válida")
        srv = self._SERVICIOS.get(hw, ".hdmi.HDMIInputService")
        self._mandar(_pb((90, _pb((1, f"content://android.media.tv/passthrough/com.mediatek.tvinput%2F{srv}%2F{hw}")))))

    def abrir(self, url):
        if not re.fullmatch(r"https?://\S{1,500}", url or ""):
            raise DriverError("Enlace no válido")
        self._mandar(_pb((90, _pb((1, self.enlace_tv(url))))))


# Orden de deteccion; Vestel no se autodetecta.
DRIVERS = {"roku": Roku, "hisense": Hisense, "vestel": Vestel, "androidtv": AndroidTV}


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


def puerto_abierto(ip_local: str, puerto: int, timeout: float = 0.5) -> set:
    """IPs de la /24 de ip_local con `puerto` abierto (para TV que no responden a SSDP)."""
    from concurrent.futures import ThreadPoolExecutor
    base = ip_local.rsplit(".", 1)[0]

    def prueba(ip):
        try:
            socket.create_connection((ip, puerto), timeout=timeout).close()
            return ip
        except OSError:
            return None
    with ThreadPoolExecutor(64) as ex:
        return {ip for ip in ex.map(prueba, [f"{base}.{i}" for i in range(1, 255)]) if ip}


def descubrir(ip_local: str, extra_ips=()) -> list:
    """Detecta dispositivos soportados en la red. Devuelve instancias de Driver."""
    from concurrent.futures import ThreadPoolExecutor
    with ThreadPoolExecutor(2) as ex:  # SSDP y barrido de puertos a la vez
        a = ex.submit(ssdp_ips, ip_local)
        b = ex.submit(puerto_abierto, ip_local, AndroidTV.PUERTO_REMOTE)
        ips = a.result() | b.result() | set(extra_ips)

    def identificar(ip):
        for clase in (Roku, Hisense, AndroidTV):
            d = clase.probe(ip)
            if d:
                return d
        return None

    orden = sorted(ips, key=lambda x: tuple(map(int, x.split("."))))
    with ThreadPoolExecutor(16) as ex:  # cada probe puede tardar hasta ~2 s: en paralelo
        return [d for d in ex.map(identificar, orden) if d]
