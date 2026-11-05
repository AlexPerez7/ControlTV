"""Pruebas sin red: identificador estable, MAC, Wake-on-LAN y dispositivos recordados.

Ejecutar:  python -I -m unittest discover -s tests -v
"""
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import dispositivos as d  # noqa: E402
import servidor  # noqa: E402


class Mac(unittest.TestCase):
    def test_normalizar(self):
        self.assertEqual(d.normalizar_mac("BC-D7-D4-DA-B8-87"), "bc:d7:d4:da:b8:87")
        self.assertEqual(d.normalizar_mac("bcd7d4dab887"), "bc:d7:d4:da:b8:87")

    def test_invalidas(self):
        for x in ("", None, "00:00:00:00:00:00", "bc:d7:d4", "zz:d7:d4:da:b8:87"):
            self.assertEqual(d.normalizar_mac(x), "")

    def test_paquete_magico(self):
        p = d.paquete_magico("bc:d7:d4:da:b8:87")
        self.assertEqual(len(p), 102)
        self.assertEqual(p[:6], b"\xff" * 6)
        self.assertEqual(p[6:12], bytes.fromhex("bcd7d4dab887"))
        self.assertEqual(p[6:], bytes.fromhex("bcd7d4dab887") * 16)
        with self.assertRaises(d.DriverError):
            d.paquete_magico("nada")

    def test_wake_on_lan_envia_broadcast(self):
        envios = []
        falso = mock.MagicMock()
        falso.sendto.side_effect = lambda datos, dest: envios.append(dest)
        with mock.patch.object(d.socket, "socket", return_value=falso):
            d.wake_on_lan("bc:d7:d4:da:b8:87", "192.168.31.108")
        self.assertIn(("255.255.255.255", 9), envios)
        self.assertIn(("192.168.31.255", 9), envios)


class Identidad(unittest.TestCase):
    def test_id_no_cambia_con_la_ip(self):
        a, b = d.AndroidTV("192.168.31.108"), d.AndroidTV("192.168.31.99")
        a.uid = b.uid = "1c2013fc-8e56-9ad2-d115-201240d24fac"
        self.assertEqual(servidor.id_de(a), servidor.id_de(b))
        self.assertRegex(servidor.id_de(a), r"^[a-z0-9-]+$")

    def test_sin_uid_usa_la_ip(self):
        self.assertIn("192-168-31-145", servidor.id_de(d.Vestel("192.168.31.145")))


class Recordados(unittest.TestCase):
    def test_guardar_y_cargar(self):
        tv = d.AndroidTV("192.168.31.108", "", "AiPlus2K")
        tv.uid, tv.mac, tv._paired = "udn-1", "2a:c4:b5:ad:6e:0f", True
        roku = d.Roku("192.168.31.110", "Express", "Roku Express")
        roku.uid = "X0040"
        sin_uid = d.Vestel("192.168.31.145")  # manual: no se recuerda
        with tempfile.TemporaryDirectory() as t, mock.patch.object(servidor, "CONOCIDOS", Path(t) / "c.json"):
            servidor.guardar_conocidos({"a": tv, "b": roku, "c": sin_uid})
            res = servidor.cargar_conocidos()
        self.assertEqual(len(res), 2)
        tv2 = res[servidor.id_de(tv)]
        self.assertEqual((tv2.ip, tv2.nombre, tv2.mac, tv2.uid), (tv.ip, "AiPlus2K", tv.mac, "udn-1"))
        self.assertEqual(tv2.caps, tv.caps)  # sigue "pareada" aunque esté apagada
        self.assertIn("keys", tv2.caps)

    def test_archivo_roto(self):
        with tempfile.TemporaryDirectory() as t:
            f = Path(t) / "c.json"
            f.write_text("{no es json", encoding="utf-8")
            with mock.patch.object(servidor, "CONOCIDOS", f):
                self.assertEqual(servidor.cargar_conocidos(), {})


class EncenderSinRed(unittest.TestCase):
    def _tv(self, mac):
        tv = d.AndroidTV("192.168.31.108")
        tv.mac = mac
        tv._inyectar = mock.Mock(side_effect=d.DriverError("no responde"))
        return tv

    def test_power_usa_wake_on_lan_si_conoce_la_mac(self):
        tv = self._tv("2a:c4:b5:ad:6e:0f")
        with mock.patch.object(d, "wake_on_lan") as w, mock.patch.object(tv, "_tras_wake") as t:
            tv.key("PowerOn")
            w.assert_called_once_with("2a:c4:b5:ad:6e:0f", "192.168.31.108")
            t.assert_called_once_with("PowerOn")  # y luego manda la tecla cuando la TV vuelve a la red

    def test_tras_wake_manda_la_tecla_cuando_abre_el_puerto(self):
        tv = self._tv("2a:c4:b5:ad:6e:0f")
        tv._inyectar = mock.Mock()
        with mock.patch.object(d.socket, "create_connection", return_value=mock.MagicMock()):
            tv._tras_wake("PowerOn")
        tv._inyectar.assert_called_once_with("PowerOn", d.AndroidTV.CORTA)

    def test_tras_wake_sin_red_no_manda_nada(self):
        tv = self._tv("2a:c4:b5:ad:6e:0f")
        tv._inyectar = mock.Mock()
        tv.ESPERA_WAKE = 0
        with mock.patch.object(d.socket, "create_connection", side_effect=OSError):
            tv._tras_wake("PowerOn")
        tv._inyectar.assert_not_called()

    def test_encender_manda_el_paquete_aunque_la_tecla_funcione(self):
        tv = self._tv("2a:c4:b5:ad:6e:0f")
        tv._inyectar = mock.Mock()  # la TV responde
        with mock.patch.object(d, "wake_on_lan") as w:
            tv.key("PowerOn")
        w.assert_called_once_with("2a:c4:b5:ad:6e:0f", "192.168.31.108")
        tv._inyectar.assert_called_once()

    def test_otras_teclas_siguen_fallando(self):
        tv = self._tv("2a:c4:b5:ad:6e:0f")
        with mock.patch.object(d, "wake_on_lan") as w, self.assertRaises(d.DriverError):
            tv.key("Home")
        w.assert_not_called()

    def test_sin_mac_el_error_se_conserva(self):
        with self.assertRaises(d.DriverError):
            self._tv("").key("Power")


class RokuPruebas(unittest.TestCase):
    INFO = ("<device-info><vendor-name>Roku</vendor-name><model-name>Roku Express</model-name>"
            "<user-device-name>Sala</user-device-name><serial-number>X00400M33V6H</serial-number>"
            "<wifi-mac>bc:d7:d4:da:b8:87</wifi-mac></device-info>")

    def test_probe_lee_modelo_serie_y_mac(self):
        with mock.patch.object(d, "_http", return_value=(self.INFO.encode(), "text/xml")):
            r = d.Roku.probe("192.168.31.110")
        self.assertEqual((r.modelo, r.nombre, r.uid, r.mac), ("Roku Express", "Sala", "X00400M33V6H", "bc:d7:d4:da:b8:87"))

    def test_probe_ignora_otras_marcas_y_errores(self):
        with mock.patch.object(d, "_http", return_value=(b"<device-info><vendor-name>Otra</vendor-name></device-info>", "")):
            self.assertIsNone(d.Roku.probe("192.168.31.1"))
        with mock.patch.object(d, "_http", side_effect=d.DriverError("sin red")):
            self.assertIsNone(d.Roku.probe("192.168.31.1"))

    def test_apps(self):
        xml = ('<apps><app id="12" type="appl" version="1">Netflix</app>'
               '<app id="837" type="appl" version="2">YouTube &amp; Co</app></apps>')
        with mock.patch.object(d, "_http", return_value=(xml.encode(), "text/xml")):
            apps = d.Roku("192.168.31.110").apps()
        self.assertEqual([(a["id"], a["name"]) for a in apps], [("12", "Netflix"), ("837", "YouTube & Co")])

    def test_teclas_y_texto(self):
        r = d.Roku("192.168.31.110")
        with mock.patch.object(d, "_http") as h:
            r.key("Select")
            r.text("a ñ")
        urls = [c.args[0] for c in h.call_args_list]
        self.assertEqual(urls[0], "http://192.168.31.110:8060/keypress/Select")
        self.assertEqual(urls[1:], ["http://192.168.31.110:8060/keypress/Lit_a", "http://192.168.31.110:8060/keypress/Lit_%20",
                                     "http://192.168.31.110:8060/keypress/Lit_%C3%B1"])

    def test_no_declara_encendido_ni_volumen(self):
        self.assertNotIn("Power", d.Roku.teclas)
        self.assertNotIn("VolumeUp", d.Roku.teclas)
        self.assertNotIn("volume", d.Roku.caps)

    def test_mantener_pulsada_manda_keydown_y_keyup(self):
        r = d.Roku("192.168.31.110")
        with mock.patch.object(d, "_http") as h:
            r.key_hold("Right", "start")
            r.key_hold("Right", "end")
        urls = [c.args[0] for c in h.call_args_list]
        self.assertEqual(urls, ["http://192.168.31.110:8060/keydown/Right", "http://192.168.31.110:8060/keyup/Right"])
        self.assertIsNone(r._hold)

    def test_mantener_otra_tecla_suelta_la_anterior(self):
        r = d.Roku("192.168.31.110")
        with mock.patch.object(d, "_http") as h:
            r.key_hold("Fwd", "start")
            r.key_hold("Left", "start")
            r._hold[1].cancel()
        urls = [c.args[0].rsplit("/", 2)[-2:] for c in h.call_args_list]
        self.assertEqual(urls, [["keydown", "Fwd"], ["keyup", "Fwd"], ["keydown", "Left"]])

    def test_se_suelta_sola_si_no_llega_el_end(self):
        r = d.Roku("192.168.31.110")
        r.HOLD_MAX = 0.05
        with mock.patch.object(d, "_http") as h:
            r.key_hold("Up", "start")
            time.sleep(0.3)
        self.assertEqual([c.args[0].rsplit("/", 2)[-2] for c in h.call_args_list], ["keydown", "keyup"])
        self.assertIsNone(r._hold)

    def test_no_se_puede_mantener_ok_ni_acciones_raras(self):
        r = d.Roku("192.168.31.110")
        with self.assertRaises(d.DriverError):
            r.key_hold("Select", "start")
        with self.assertRaises(d.DriverError):
            r.key_hold("Up", "otra")

    def test_la_ficha_lista_las_teclas_que_se_mantienen(self):
        self.assertEqual(servidor.ficha("roku-x", d.Roku("192.168.31.110"))["largo"],
                         ["Up", "Down", "Left", "Right", "Rev", "Fwd"])
        self.assertIs(servidor.ficha("tv", d.AndroidTV("192.168.31.108"))["largo"], True)

    APP = '<active-app><app id="562859" type="home">Roku Dynamic Menu</app>{}</active-app>'
    SS = '<screensaver id="55545" type="ssvr">Roku City</screensaver>'

    def test_estado_inicio_y_salvapantallas(self):
        self.assertEqual(d.Roku.interpretar_estado(self.APP.format("")), {"actividad": "inicio"})
        self.assertEqual(d.Roku.interpretar_estado(self.APP.format(self.SS)), {"actividad": "salvapantallas"})
        self.assertEqual(d.Roku.interpretar_estado("<active-app></active-app>"), {"actividad": "inicio"})

    def test_estado_app_con_reproductor(self):
        app = '<active-app><app id="837" type="appl" version="1">YouTube</app></active-app>'
        pausa = '<player state="pause" error="false"/>'
        self.assertEqual(d.Roku.interpretar_estado(app, pausa), {"actividad": "app", "app": "YouTube", "reproductor": "pause"})
        self.assertEqual(d.Roku.interpretar_estado(app, '<player state="close" error="false"/>'),
                         {"actividad": "app", "app": "YouTube"})
        self.assertEqual(d.Roku.interpretar_estado(app), {"actividad": "app", "app": "YouTube"})

    def test_estado_sin_dato_del_reproductor(self):
        app = '<active-app><app id="837" type="appl">YouTube</app></active-app>'

        def http(url, **kw):
            if "media-player" in url:
                raise d.DriverError("sin respuesta")
            return app.encode(), "text/xml"
        with mock.patch.object(d, "_http", side_effect=http):
            self.assertEqual(d.Roku("192.168.31.110").estado(), {"actividad": "app", "app": "YouTube"})


class Emparejamiento(unittest.TestCase):
    def test_el_socket_caduca_solo(self):
        tv = d.AndroidTV("192.168.31.108")
        s = mock.MagicMock()
        tv._pair_sock = s
        tv._caducar_pair(s)
        self.assertIsNone(tv._pair_sock)
        s.close.assert_called_once()

    def test_caducar_ignora_un_socket_viejo(self):
        tv = d.AndroidTV("192.168.31.108")
        viejo, nuevo = mock.MagicMock(), mock.MagicMock()
        tv._pair_sock = nuevo  # se empezó otro emparejamiento después
        tv._caducar_pair(viejo)
        self.assertIs(tv._pair_sock, nuevo)
        nuevo.close.assert_not_called()

    def test_cerrar_cancela_el_temporizador(self):
        tv = d.AndroidTV("192.168.31.108")
        tv._pair_sock, tv._pair_timer = mock.MagicMock(), mock.MagicMock()
        temporizador = tv._pair_timer
        tv._cerrar_pair()
        temporizador.cancel.assert_called_once()
        self.assertIsNone(tv._pair_sock)


if __name__ == "__main__":
    unittest.main()
