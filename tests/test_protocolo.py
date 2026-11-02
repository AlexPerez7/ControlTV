"""Pruebas sin red ni TV del protocolo Android TV Remote (protobuf, DER, emparejamiento).

Ejecutar:  python -I -m unittest discover -s tests -v
"""
import os
import shutil
import ssl
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import dispositivos as d  # noqa: E402


class Protobuf(unittest.TestCase):
    def test_varint(self):
        self.assertEqual(d._varint(1), b"\x01")
        self.assertEqual(d._varint(300), b"\xac\x02")
        for n in (0, 127, 128, 622, 65537, 2 ** 40):
            self.assertEqual(d._leer_varint(d._varint(n), 0), (n, len(d._varint(n))))

    def test_mensaje_pairing_conocido(self):
        # protocol_version=2, status=200: los dos primeros campos de todo mensaje de pairing
        self.assertEqual(d._pb((1, 2), (2, 200)), b"\x08\x02\x10\xc8\x01")

    def test_ida_y_vuelta_con_mensaje_anidado(self):
        interno = d._pb((1, "Control TV"), (3, 1))
        res = d._pb_leer(d._pb((1, 622), (2, interno), (9, b"\x00\xff")))
        self.assertEqual(res[1], [622])
        self.assertEqual(d._pb_leer(res[2][0]), {1: [b"Control TV"], 3: [1]})
        self.assertEqual(res[9], [b"\x00\xff"])

    def test_tecla_corta(self):
        # remote_key_inject (campo 10): key_code=19 (arriba), direction=3 (SHORT)
        self.assertEqual(d._pb((10, d._pb((1, 19), (2, 3)))), b"\x52\x04\x08\x13\x10\x03")

    def test_campos_de_ancho_fijo_no_desalinean_el_resto(self):
        # campo 5 tipo fixed32 y campo 6 tipo fixed64: se leen sin romper el campo siguiente
        datos = b"\x2d\x00\x00\x00\x00" + b"\x31" + b"\x00" * 8 + d._pb((7, 25))
        self.assertEqual(d._pb_leer(datos)[7], [25])


@unittest.skipUnless(shutil.which("openssl"), "falta openssl")
class Certificados(unittest.TestCase):
    def test_modulo_y_exponente_coinciden_con_openssl(self):
        with tempfile.TemporaryDirectory() as t:
            c, k = os.path.join(t, "c.pem"), os.path.join(t, "k.pem")
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=prueba",
                            "-keyout", k, "-out", c], check=True, capture_output=True)
            der = ssl.PEM_cert_to_DER_cert(open(c).read())
            n, e = d._rsa_de_cert(der)
            salida = subprocess.run(["openssl", "x509", "-in", c, "-noout", "-modulus"], check=True,
                                    capture_output=True, text=True).stdout.strip()
            self.assertEqual(n.hex().upper(), salida.split("=")[1])
            self.assertEqual(e, b"\x01\x00\x01")  # 65537


class Emparejamiento(unittest.TestCase):
    def tv(self):
        return d.AndroidTV("192.0.2.1")

    def test_sin_iniciar(self):
        with self.assertRaisesRegex(d.DriverError, "Primero inicia"):
            self.tv().pair("ABC123")

    def test_codigo_mal_formado(self):
        for malo in ("12345", "GGGGGG", "1234567", ""):
            with self.assertRaisesRegex(d.DriverError, "6 caracteres"):
                self.tv().pair(malo)

    def test_codigo_caducado(self):
        class Falso:
            def close(self):
                pass
        tv = self.tv()
        tv._pair_sock, tv._pair_desde = Falso(), 0.0
        with self.assertRaisesRegex(d.DriverError, "caduco"):
            tv.pair("ABC123")
        self.assertIsNone(tv._pair_sock)

    def test_sin_emparejar_solo_ofrece_emparejar(self):
        tv = self.tv()
        self.assertEqual(tv.caps, ("pair",))
        tv._paired = True
        self.assertIn("volume", tv.caps)

    def test_enlaces_de_youtube_al_esquema_propio(self):
        casos = {"https://youtu.be/dQw4w9WgXcQ?si=abc": "vnd.youtube://dQw4w9WgXcQ",
                 "https://www.youtube.com/watch?v=dQw4w9WgXcQ": "vnd.youtube://dQw4w9WgXcQ",
                 "https://www.youtube.com/watch?list=PL1&v=dQw4w9WgXcQ&t=5": "vnd.youtube://dQw4w9WgXcQ",
                 "https://m.youtube.com/shorts/dQw4w9WgXcQ": "vnd.youtube://dQw4w9WgXcQ",
                 "https://example.com/video": "https://example.com/video"}
        for entrada, esperado in casos.items():
            self.assertEqual(d.AndroidTV.enlace_tv(entrada), esperado, entrada)

    def test_abrir_rechaza_enlaces_raros(self):
        for malo in ("", "javascript:alert(1)", "intent://x#Intent;end", "file:///etc/passwd", "https://a b"):
            with self.assertRaisesRegex(d.DriverError, "no valido"):
                self.tv().abrir(malo)

    def test_teclas_desconocidas(self):
        with self.assertRaisesRegex(d.DriverError, "no disponible"):
            self.tv().key("Inventada")


if __name__ == "__main__":
    unittest.main()
