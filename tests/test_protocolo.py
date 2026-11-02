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

    def tv_capturando(self):
        tv = self.tv()
        tv.enviados = []
        tv._mandar = tv.enviados.append  # sin red: guarda los mensajes que se mandarian
        return tv

    def inyeccion(self, tecla, direccion):
        return d._pb((10, d._pb((1, d.AndroidTV.TECLAS[tecla]), (2, direccion))))

    def test_mantener_tecla_manda_start_y_end(self):
        tv = self.tv_capturando()
        tv.key_hold("Right", "start")
        tv.key_hold("Right", "end")
        self.assertEqual(tv.enviados, [self.inyeccion("Right", 1), self.inyeccion("Right", 2)])
        self.assertIsNone(tv._hold)

    def test_end_sin_start_no_hace_nada(self):
        tv = self.tv_capturando()
        tv.key_hold("Right", "end")
        self.assertEqual(tv.enviados, [])

    def test_otra_tecla_suelta_la_anterior(self):
        tv = self.tv_capturando()
        tv.key_hold("Right", "start")
        tv.key_hold("Select", "start")
        tv.key_hold("Select", "end")
        self.assertEqual(tv.enviados, [self.inyeccion("Right", 1), self.inyeccion("Right", 2),
                                       self.inyeccion("Select", 1), self.inyeccion("Select", 2)])

    def test_tecla_pegada_se_suelta_sola(self):
        import time
        tv = self.tv_capturando()
        tv.HOLD_MAX = 0.1
        tv.key_hold("Down", "start")
        time.sleep(0.4)
        self.assertEqual(tv.enviados, [self.inyeccion("Down", 1), self.inyeccion("Down", 2)])
        self.assertIsNone(tv._hold)

    def test_tecla_corta_sigue_siendo_short(self):
        tv = self.tv_capturando()
        tv.key("Up")
        self.assertEqual(tv.enviados, [d._pb((10, d._pb((1, 19), (2, 3))))])

    def test_mantener_rechaza_accion_o_tecla_invalida(self):
        tv = self.tv_capturando()
        with self.assertRaisesRegex(d.DriverError, "no valida"):
            tv.key_hold("Up", "mitad")
        with self.assertRaisesRegex(d.DriverError, "no disponible"):
            tv.key_hold("Inventada", "start")
        self.assertEqual(tv.enviados, [])

    def test_mensaje_de_texto_coincide_con_el_probado_en_la_tv(self):
        # Bytes enviados a la Caixun (YouTube TV, campo de busqueda enfocado) que escribieron "hola" en el campo.
        self.assertEqual(d.AndroidTV.mensaje_texto("hola").hex(), "aa0114080010001a0e0801120a080310031a04686f6c61")

    def test_tildes_se_quitan_y_lo_demas_se_omite(self):
        # En la Caixun (YouTube TV) "Hola Ñandú Árbol?" llego como "Hola and rbol?": los no ASCII se pierden
        self.assertEqual(d.AndroidTV.a_ascii("Hola Ñandú Árbol"), ("Hola Nandu Arbol", ""))
        self.assertEqual(d.AndroidTV.a_ascii("¿qué €5?"), ("que 5", "¿€?"))
        # "?" activa la busqueda por voz y "!" llega como "1": los signos con Shift no se mandan
        self.assertEqual(d.AndroidTV.a_ascii("a?b!c@d"), ("abcd", "?!@"))
        self.assertEqual(d.AndroidTV.a_ascii("a-b.c,d/e;f=g'h"), ("a-b.c,d/e;f=g'h", ""))

    def test_texto_se_manda_sin_tildes(self):
        tv = self.tv_capturando()
        tv.text("Ñandú")
        self.assertEqual(tv.enviados, [d.AndroidTV.mensaje_texto("Nandu")])
        self.assertEqual(tv.omitidos("¿Ñandú?"), "¿?")
        with self.assertRaisesRegex(d.DriverError, "No hay texto"):
            tv.text("¿¡")

    def test_cursor_cuenta_unidades_utf16(self):
        # "a😀": 'a' = 1 unidad, el emoji = 2 -> ultima posicion = 2
        m = d._pb_leer(d._pb_leer(d.AndroidTV.mensaje_texto("a😀"))[21][0])
        estado = d._pb_leer(d._pb_leer(m[3][0])[2][0])
        self.assertEqual((estado[1][0], estado[2][0]), (2, 2))

    def test_texto_se_manda_en_un_solo_mensaje(self):
        tv = self.tv_capturando()
        tv.text("Hola 123?")
        self.assertEqual(tv.enviados, [d.AndroidTV.mensaje_texto("Hola 123")])

    def test_texto_usa_los_contadores_que_informa_la_tv(self):
        tv = self.tv_capturando()
        tv._ime = (7, 3)
        tv.text("a")
        self.assertEqual(tv.enviados, [d.AndroidTV.mensaje_texto("a", 7, 3)])

    def test_saltos_de_linea_pasan_a_espacio(self):
        tv = self.tv_capturando()
        tv.text("a\nb\r")
        self.assertEqual(tv.enviados, [d.AndroidTV.mensaje_texto("a b")])

    def test_texto_vacio_o_muy_largo(self):
        tv = self.tv_capturando()
        for vacio in ("", "\r"):
            with self.assertRaisesRegex(d.DriverError, "No hay texto"):
                tv.text(vacio)
        with self.assertRaisesRegex(d.DriverError, "demasiado largo"):
            tv.text("a" * 201)
        self.assertEqual(tv.enviados, [])

    def test_ids_de_apps_caben_en_la_ruta(self):
        # servidor.py y Api.java solo aceptan [\w.-]+ en /d/<id>/launch/<app>: un espacio o un "+" dan 404
        for app_id in d.AndroidTV.APPS:
            self.assertRegex(app_id, r"^[\w.-]+$")
        self.assertEqual([a["name"] for a in self.tv().apps()][2:4], ["Prime Video", "Disney+"])
        self.assertIn("HBO Max", [a["name"] for a in self.tv().apps()])

    def test_teclas_desconocidas(self):
        with self.assertRaisesRegex(d.DriverError, "no disponible"):
            self.tv().key("Inventada")


if __name__ == "__main__":
    unittest.main()
