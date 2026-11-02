# ControlTV

Control remoto web universal por WiFi. Un servidor local en Python (solo biblioteca estándar) controla varios
dispositivos (Roku, Hisense, ...) y sirve una web pensada para el celular. Idioma del proyecto: español.
`plan.md` es la fuente de verdad del estado, lo descubierto y lo descartado; léelo antes de investigar algo de las TV.

## Cómo ejecutar
- `python servidor.py [--puerto 8090]` (en Windows: `Iniciar Control TV.bat`). Detecta dispositivos por SSDP al arrancar;
  el botón "Buscar" de la web vuelve a escanear. Abrir en el celular la URL que imprime (mismo WiFi).
- No hay tests ni dependencias. Verificar con `python -I -m py_compile servidor.py dispositivos.py` y probando con `curl`
  contra otro puerto (`--puerto 8099`), p. ej. `curl localhost:8099/devices`.
- `servidor.ps1` es la versión antigua (solo Roku): obsoleta, no mantener.

## Arquitectura
- `servidor.py`: HTTP (`ThreadingHTTPServer`), registro `DEVICES` (id → Driver), rutas por dispositivo:
  `GET /devices`, `POST /scan`, `/d/<id>/key/<Tecla>`, `/d/<id>/text?t=`, `/d/<id>/apps`, `/d/<id>/launch/<app>`,
  `/d/<id>/icon/<app>`, `/d/<id>/estado`. Los errores de driver (`DriverError`) salen como HTTP 502 con el mensaje.
- `dispositivos.py`: un `Driver` por marca (`Roku`, `Hisense`, `Vestel`). Cada uno declara `caps` (`keys`, `text`,
  `apps`, `volume`) y la web solo muestra los botones que el dispositivo soporta. Las teclas usan nombres Roku ECP
  (`Up`, `Select`, `VolumeUp`, ...) como idioma común.
- `index.html`: una sola página; selector de dispositivo, secciones con clases `cap-keys|cap-text|cap-apps|cap-volume`.
- Agregar una marca: subclase de `Driver` + registrarla en `DRIVERS`. Si no se autodetecta, `dispositivos.json`
  (`[{"ip":..., "marca":"vestel", "modelo":...}]`, ignorado por git). Varias TV de la misma marca funcionan sin cambios.

## Reglas
- No subir IPs de la red ni claves: `dispositivos.json`, `roku_ip.txt`, `tv_ip.txt`, `*.pem`, `*.key` están en `.gitignore`.
- Los comandos a las TV cambian el estado de aparatos reales (volumen, apps, encendido). Antes de probar uno nuevo,
  avisar al usuario, confirmar que la TV está encendida y restaurar el valor original (p. ej. volumen 2→3→2).
  Las lecturas (GetVolume, device-info, SSDP) son seguras.
- La Hisense (192.168.31.192, TN40.V0021.K221.WAM) pide permiso en pantalla la primera vez: sin aceptarlo `SetVolume`
  da 401. Su mute nativo no funciona; está emulado con el volumen. Teclas/encendido: sin canal conocido (ver `plan.md`).
- Subir a `main` solo cuando el usuario lo pida.

## Análisis de apps con REA
- Herramientas instaladas solo para el usuario en `~/tools` (Node 24, JDK 21, `rea`, `jadx-headless-mcp`, `adb`);
  cargarlas con `source ~/tools/env.sh`. No usar `rea setup` sin permiso (modifica la configuración de Claude Code).
- APK: obtenerlos solo desde Google Play instalando en el perfil normal del móvil y sacándolos con `adb pull`
  (la Carpeta Segura, usuario 150, es inaccesible para `adb`). Los sitios espejo ya entregaron archivos equivocados.
- Trabajar en carpetas nuevas bajo `~/re/`, analizar de forma estática, nunca instalar ni ejecutar APK de origen dudoso,
  y borrar los APK al terminar.
