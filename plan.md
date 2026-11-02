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

## Mejoras propuestas (análisis del código, nov 2026)

Prioridad: **A** = arreglo o mejora con mucho impacto y poco trabajo, **B** = conviene, **C** = idea a futuro.
Lo que ya estaba en "Mejoras generales" (IR, QR, arranque con Windows, búsqueda de contenido...) no se repite aquí.

### Errores y robustez
- [x] **A** · (hecho: `preparar()` al escanear; en la app el escaneo tampoco bloquea las teclas) `/devices` puede congelar todo el servidor: `ficha()` lee `d.caps` dentro de `with LOCK`, y en `AndroidTV.caps`
      eso abre una conexión TLS (hasta 3 s) si no se sabe aún si está emparejada. Mientras tanto ninguna otra petición avanza.
      Igual en la app (`Api.fichas()` y `AndroidTV.caps()` son `synchronized`). Solución: comprobar el emparejamiento durante
      el escaneo (fuera del lock) y que `caps` solo lea el valor guardado.
- [x] **A** · (hecho: clase `solo-keys`) Los botones de arriba (⏻ y 🏠) no tienen clase `cap-keys`: se ven también en la Hisense, que no admite teclas,
      y al pulsarlos sale error. Ocultarlos igual que el resto.
- [x] **A** · (hecho) Al mantener pulsada una flecha se manda una tecla cada 180 ms sin esperar la respuesta anterior: si la TV va lenta
      se acumulan peticiones y la TV sigue moviéndose después de soltar. No mandar la siguiente hasta que vuelva la anterior.
- [ ] **B** · Android TV (Python): `_sock`/`_listo` se modifican desde dos hilos sin lock; el socket de emparejamiento queda
      abierto si el usuario no escribe el código. (Hecho: el código caduca a los 2 min. Falta el lock de `_sock`/`_listo`.)
- [ ] **B** · Los IDs de dispositivo dependen de la IP (`android-tv-192-168-31-108`): si el router cambia la IP, la web olvida
      el dispositivo elegido. Usar un dato fijo (MAC, número de serie, `udn`) o reservar IPs en el router.
- [ ] **B** · `online()` existe en todos los drivers pero nadie lo usa: el indicador "● Conectado" solo comprueba el servidor,
      no la TV. Usarlo para un punto verde/rojo por dispositivo en el selector.
- [ ] **C** · La app Android ignora `dispositivos.json` (los agregados a mano, como la onn); permitir agregarlos desde la app.

### Rendimiento
- [x] **A** · (hecho: servidor ~5,6 s → ~3,2 s; la app prueba IP×puerto a la vez y sigue encontrando Caixun y Roku, sin medir el tiempo) Escaneo del servidor: los `probe` se hacen uno tras otro por IP (cada uno espera hasta 1,5-2 s). Hacerlos en
      paralelo (ThreadPoolExecutor, como ya hace `puerto_abierto`) y lanzar SSDP y el barrido de puertos a la vez: de ~10 s a ~3 s.
- [ ] **B** · Roku: cada tecla y cada letra del texto abren una conexión HTTP nueva. Reutilizar la conexión
      (`http.client.HTTPConnection` con keep-alive) baja la latencia y acelera mucho el envío de texto.
- [ ] **B** · App Android: `Discovery` prueba los puertos de cada IP uno tras otro (8060, 56790, 6466 → hasta 1,2 s por IP).
      Probarlos a la vez, o empezar por las IPs que responden a SSDP/mDNS.
- [ ] **C** · Guardar la lista de dispositivos del último escaneo y mostrarla al instante al abrir la web/app, y reescanear en
      segundo plano.

### Diseño del código
- [ ] **B** · La lógica de los drivers está duplicada en Python y Java (teclas, enlaces de apps, protocolos). Pasar los datos
      (mapas de teclas, enlaces de apps) a un `drivers.json` común que `build.sh` copie a los assets, para cambiarlos en un solo sitio.
- [ ] **B** · La app lleva dentro la clave privada de `androidtv.p12`: cualquiera con el APK podría usar el emparejamiento.
      Alternativa: generar la clave en el **Android Keystore** de cada celular (crea su propio certificado autofirmado) y
      emparejar una vez por celular. Así el APK no lleva secretos.
- [x] **B** · (hecho: `tests/test_protocolo.py`, 11 pruebas; faltan Roku/Hisense) Pruebas mínimas sin dependencias (`python -m unittest`): codificar/decodificar protobuf, el hash del pairing con un
      ejemplo conocido y el parseo de respuestas de Roku/Hisense. Hoy un error ahí solo se ve probando con la TV.
- [ ] **C** · Borrar `servidor.ps1` (obsoleto) y que `Iniciar Control TV.bat` no dependa de la ruta fija de Python 3.12
      (usar `py -3` o `python` del PATH).
- [ ] **C** · El servidor acepta comandos de cualquier equipo del WiFi y de cualquier página web abierta en un navegador de la red
      (un `fetch` desde otra web llega igual). Comprobar la cabecera `Origin` y, opcional, un PIN.

### UI y UX
- [x] **A** · (hecho: campo `teclas` en la ficha) Botones por dispositivo: cada driver podría declarar las teclas que soporta y la web ocultar el resto
      (p. ej. "↺ Repetir" o "Encender" no sirven en todas las TV).
- [x] **A** · (hecho, falta el nombre real de la TV) Emparejamiento: al pulsar "Mostrar código", enfocar el campo del código, aceptar minúsculas y emparejar solo al
      escribir el 6.º carácter. Mostrar el nombre de la TV.
- [ ] **B** · Nombre real de la TV: hoy la Caixun sale como "Android TV". La propia TV lo envía al conectar (`remote_configure`
      trae fabricante y modelo) y también responde en `http://IP:8008/setup/eureka_info`.
- [ ] **B** · Apps sin icono en Android TV y Hisense: usar iconos/colores propios (SVG pequeños en la web) en lugar de texto.
- [ ] **B** · Pulsación larga real: en Android TV mandar `START_LONG`/`END_LONG` al mantener OK (abre menús contextuales),
      en vez de repetir pulsaciones cortas.
- [ ] **B** · Gestos: zona táctil para deslizar (como la app Google TV) como alternativa a las flechas.
- [ ] **C** · Accesibilidad: `user-scalable=no` impide hacer zoom y los botones con solo emoji no tienen `aria-label`.
- [ ] **C** · Diseño horizontal/tablet (hoy la columna mide como máximo 420 px) y tema claro.

### Funciones nuevas
- [x] **A** · (hecho: la Caixun ahora tenia sin botones de volumen) Android TV: **volumen visible**. La TV envía por la misma conexión el nivel, el máximo y el mute
      (`remote_set_volume_level`); con eso se activa la capacidad `volume` y se ve el número como en la Hisense.
- [x] **A** · (hecho y probado en la Caixun) App Android: **botones de volumen del celular** controlan la TV elegida (`onKeyDown` de VOLUME_UP/DOWN).
- [ ] **B** · Android TV: **escribir texto** (mensaje `remote_ime_batch_edit`), para búsquedas y contraseñas.
- [ ] **B** · App Android: **"Compartir con Control TV"**: compartir un video de YouTube desde el celular y que se abra en la TV
      (filtro de intents `SEND` + `launch` con el enlace).
- [ ] **B** · Más teclas en Android TV: entrada/fuente (`KEYCODE_TV_INPUT`), ajustes, canal +/-, teclado numérico.
- [ ] **B** · Encender por red: **Wake-on-LAN** (la MAC se puede sacar de la tabla ARP durante el escaneo) para TV que apagan el
      WiFi en reposo.
- [ ] **C** · App Android: widget o mosaico de ajustes rápidos con Pausa, Volumen y Silencio, sin abrir la app.
- [ ] **C** · Atajos: secuencias guardadas (p. ej. "Noche": abrir YouTube y bajar el volumen) y acciones a todas las TV a la vez
      (silenciar todo).

## Cómo se usa

1. Ejecutar `Iniciar Control TV.bat` (detecta los dispositivos de la red al arrancar; el boton "Buscar" de la web vuelve a escanear).
2. Abrir en el celular la dirección que muestra la ventana, por ejemplo `http://192.168.31.14:8090`.
3. El celular y el PC deben estar en el mismo WiFi.
