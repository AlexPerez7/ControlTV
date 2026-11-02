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
| Caixun Android TV (192.168.31.108) | `AndroidTV` (Android TV Remote v2, :6466/:6467) | teclas, volumen, encendido, abrir apps; emparejado | texto, iconos de apps; enlaces de Prime/Disney+/Max sin probar |
| onn E58D62UN Vestel (192.168.31.145) | `Vestel` (solo manual) | nada (ver abajo) | ver abajo |

### Android TV (Caixun): lo descubierto (nov 2026)
- Puertos 6466 (control) y 6467 (pairing), TLS con certificado de cliente + protobuf. La TV muestra un codigo hex de 6 caracteres;
  la clave del pairing es SHA-256(modulo+exponente del cliente, modulo+exponente de la TV, codigo sin su primer byte).
- La identidad TLS del servidor es `androidtv_cert.pem`/`androidtv_clave.pem` (generada con openssl, ignorada por git). La app Android
  usa la misma desde `~/tools/androidtv.p12` (`openssl pkcs12 -export ... -certpbe PBE-SHA1-3DES -keypbe PBE-SHA1-3DES -macalg sha1`),
  asi lo emparejado desde el PC sirve en el celular. `build.sh` la copia a assets.
- Abrir apps con enlaces `https://` genericos hace que Android pregunte "Abrir con" (Downloader tambien lo reclama).
  Usar esquemas propios: YouTube `vnd.youtube://` (probado), Netflix `nflx://www.netflix.com` (cambiado, sin confirmar). Falta Prime/Disney+/Max.
- Ojo: `pkill -f "servidor.py ..."` desde Bash se mata a si mismo; usar `pkill -f "[s]ervidor.py ..."`.

### Hisense: lo descubierto (nov 2026)
- Puertos abiertos: 9085 (UPnP/Twonky), 56789 (DIAL), 56790 (descripcion `dd.xml`), 8060 y 13000 (aceptan conexion, sin respuesta HTTP).
- UPnP `RenderingControl` en :9085: `GetVolume`/`GetMute` funcionan sin permiso; `SetVolume` da **401 hasta que se acepta el
  aviso de permiso en la pantalla de la TV**, luego funciona. No se sabe aun si el permiso expira o se pierde al reiniciar.
- `SetMute` responde 200 pero no tiene efecto (`GetMute` sigue en 0): el mute se **emula** con el volumen (guarda y pone 0).
- Volumen: escala 0-100, paso de 2 por pulsacion (`Hisense.PASO`).
- `AVTransport` (DLNA, enviar video/audio/fotos) sin probar.
- Las teclas (flechas, OK, Power, fuente) **no tienen canal conocido**; ver "Descartado" abajo.

### Hisense: lo descartado (analisis con REA, nov 2026)
Se extrajo y analizo con REA (`rea`, JADX) lo que hay en el movil; ningun protocolo de app encaja con esta TV:
- **Android TV Remote (6466/6467):** `com.allrcs.hisense_remote` ("Remote for Hisense Smart TV") lo usa; la TV no tiene esos puertos.
- **VIDAA / RemoteNOW (MQTT, 36669):** `com.universal.remote.multi` (app "VIDAA", Flutter, codigo protegido con baiduprotect)
  **no encuentra la TV**; el puerto 36669 esta cerrado. Define un servicio UPnP `HisenseLanControl` (`LanControlInfo`) que la TV no expone.
- **UDP antiguo de Hisense (50000/60030, proyecto hisense-udp):** el DISCOVERY y el CTCREATE no reciben respuesta.
- **Puertos 8060 y 13000:** aceptan TCP pero no contestan a HTTP ni TLS ni mandan banner; ninguna app probada los usa.
- Probado y **sin probar**: falta "Hisense TV Remote Controller" (Mobile-care); si alguna app aparte controla la TV, capturar su
  trafico con PCAPdroid y analizar el `.pcap` con REA.
- Ojo al conseguir APK: los sitios espejo entregaron archivos equivocados (instalador de Aptoide y una app de 463 MB ajena).
  Instalar siempre desde Google Play en el perfil normal (no en Carpeta Segura: `adb` no accede al usuario 150) y sacar el APK con `adb pull`.

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
- [ ] Hisense: probar "Hisense TV Remote Controller" (Mobile-care); si controla la TV, capturar trafico (PCAPdroid) y analizarlo con REA.
- [ ] Hisense: revisar en los ajustes de red de la TV opciones tipo Wake on LAN / control de red y reescanear puertos.
- [ ] **Driver IR** (emisor IR WiFi tipo Broadlink/Tuya, ~10-15 USD): plan B universal para teclas, encendido y fuente en cualquier TV.
- [ ] Hisense: probar AVTransport (DLNA) para enviar video/audio/fotos.
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
