# Plan de Control TV

Control remoto web universal por WiFi (objetivo: varias marcas y modelos de TV/streamers). Un servidor local en Python
reenvía los botones de la web al dispositivo elegido usando el protocolo de cada marca.

## Estado actual

Servidor sin dependencias (`servidor.py` + `dispositivos.py`, solo biblioteca estandar). Detecta los dispositivos
por SSDP y la web deja elegir cual controlar; cada marca declara sus capacidades (`keys`, `text`, `apps`, `volume`)
y la interfaz muestra solo los botones que soporta. `servidor.ps1` es la version antigua (solo Roku) y queda obsoleta.

| Dispositivo | Driver | Funciona | Pendiente |
|---|---|---|---|
| Roku Express (192.168.31.110) | `Roku` (ECP :8060) | teclas, texto, apps, iconos | - |
| Hisense TN40.V0021.K221.WAM, 40" (192.168.31.192) | `Hisense` | volumen +/- y mute, abrir YouTube/Netflix (DIAL) | teclas, encendido/apagado, fuente |
| onn E58D62UN Vestel (192.168.31.145) | `Vestel` (solo manual) | nada (ver abajo) | ver abajo |

### Hisense: lo descubierto (nov 2026)
- Puertos abiertos: 9085 (UPnP/Twonky), 56789 (DIAL), 56790 (descripcion `dd.xml`), 8060 y 13000 (aceptan conexion, sin respuesta HTTP).
- UPnP `RenderingControl` en :9085: `GetVolume`/`GetMute` funcionan sin permiso; `SetVolume` da **401 hasta que se acepta el
  aviso de permiso en la pantalla de la TV**, luego funciona. No se sabe aun si el permiso expira o se pierde al reiniciar.
- `SetMute` responde 200 pero no tiene efecto (`GetMute` sigue en 0): el mute se **emula** con el volumen (guarda y pone 0).
- Volumen: escala 0-100, paso de 2 por pulsacion (`Hisense.PASO`).
- `AVTransport` (DLNA, enviar video/audio/fotos) sin probar.
- Las teclas (flechas, OK, Power) no tienen canal conocido. Candidatos: puertos 8060/13000, o analizar la app oficial
  (RemoteNOW) con REA para ver su protocolo.

### Como agregar otra TV o marca
1. Crear una subclase de `Driver` en `dispositivos.py` (`probe` para autodeteccion, `caps` y los metodos que soporte) y
   registrarla en `DRIVERS`.
2. Si no se autodetecta, agregarla a mano en `dispositivos.json` (ignorado por git):
   `[{"ip": "192.168.31.145", "marca": "vestel", "modelo": "E58D62UN"}]`
3. Varias TV de la misma marca funcionan sin cambios: cada una aparece como dispositivo distinto en el selector.

### Onn / Vestel (sin cambios, no funciona)
- La TV no acepta comandos por red. Canal SmartCenter (`:56789`) → 404, WebSocket `:7681` → 403, UDP `:4950` sin
  respuesta, WebSocket `:3697` acepta conexion pero ignora todo (37 variantes de Vol+ probadas).
- Causa probable: falta la opcion **"Virtual Remote"** en este firmware. El driver `Vestel` conserva los codigos de la app oficial.

## Pendientes

### Pendiente del Hisense y del onn
- [ ] Consultar al soporte del distribuidor si existe un firmware oficial del E58D62UN con control por red.
- [ ] Menú de ingeniería de Vestel (mantener `0` → Home → `4 7 2 5`) y activar *USB Logging → HTTP Test Server*,
      luego escanear puertos nuevos. Intento fallido hasta ahora: el control temporal no entró al menú.
- [ ] Si algún día hay un control IR o un adaptador UART: reevaluar.

### Mejoras generales (por orden de prioridad)
- [ ] Hisense: teclas/encendido (puertos 8060/13000 o analizar RemoteNOW con REA); probar AVTransport (DLNA).
- [ ] Comprobar si el permiso de la TV Hisense persiste tras reiniciar la TV.
- [ ] **Funcionar sin el PC:** probar una página HTML local en el celular que mande las teclas directo al Roku
      (`POST http://IP_ROKU:8060/keypress/<tecla>`, modo `no-cors`). Verificar en el celular que el navegador lo permita.
      Alternativa más pesada: un APK que hable con el Roku.
- [ ] **Código QR** en la ventana del servidor para abrir la web desde el celular sin escribir la dirección.
- [ ] **Servidor siempre disponible:** arrancar con Windows, IP fija para el PC y el Roku. (Modifica el sistema: confirmar antes.)
- [ ] **Búsqueda de contenido** por título entre apps (API de búsqueda del Roku). Probar con cuidado: cambia lo que se reproduce.
- [ ] Mantener la pantalla encendida mientras se usa el control, y mostrar la app activa (`/query/active-app`).
- [ ] Probar en el celular la instalación en pantalla de inicio (con HTTP puede abrirse con la barra del navegador).

## Cómo se usa

1. Ejecutar `Iniciar Control TV.bat` (detecta los dispositivos de la red al arrancar; el boton "Buscar" de la web vuelve a escanear).
2. Abrir en el celular la dirección que muestra la ventana, por ejemplo `http://192.168.31.14:8090`.
3. El celular y el PC deben estar en el mismo WiFi.
