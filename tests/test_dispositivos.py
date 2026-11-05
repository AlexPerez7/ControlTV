"""Pruebas sin red: identificador estable, MAC, Wake-on-LAN y dispositivos recordados.

Ejecutar:  python -I -m unittest discover -s tests -v
"""
import os
import sys
import tempfile
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

    def test_otras_teclas_siguen_fallando(self):
        tv = self._tv("2a:c4:b5:ad:6e:0f")
        with mock.patch.object(d, "wake_on_lan") as w, self.assertRaises(d.DriverError):
            tv.key("Home")
        w.assert_not_called()

    def test_sin_mac_el_error_se_conserva(self):
        with self.assertRaises(d.DriverError):
            self._tv("").key("Power")


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
