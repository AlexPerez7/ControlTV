# Plan de Control TV

Control remoto web para un **Roku Express** (y, si algún día es posible, para la TV). Un servidor local en Python
reenvía los botones de la web al Roku por WiFi (ECP, puerto 8060).

## Estado actual

**Funciona**
- Control del Roku desde el celular: flechas, OK, Atrás, Inicio, opciones, reproducción, texto y lista de apps.
- Indicador de conexión con el servidor, y la web se puede agregar a la pantalla de inicio (manifiesto + ícono).
- Servidor sin dependencias externas (`servidor.py`, solo biblioteca estándar). `servidor.ps1` queda como alternativa.

**No funciona: volumen y encendido de la TV**
- TV: onn E58D62UN (192.168.31.145), plataforma Vestel NetReady. El Roku Express no controla su volumen.
- La TV no acepta comandos por red. Canal SmartCenter (`:56789`) → 404, WebSocket `:7681` → 403, UDP `:4950` sin
  respuesta, WebSocket `:3697` acepta conexión pero ignora todo (37 variantes de Vol+ probadas) y no emite eventos.
- Causa probable: en este firmware no existe la opción **"Virtual Remote"** (confirmado: no aparece en ningún ajuste).
- El código del modo TV sigue en `servidor.py` (`POST /tv/key/<TECLA>`, códigos de la app oficial de Vestel) y en
  `index.html` (`HABILITAR_TV = false`). Se reactiva si algún día la TV acepta comandos.

## Pendientes

### Pendiente de la TV (solo si se quiere retomar el volumen)
- [ ] Consultar al soporte del distribuidor si existe un firmware oficial del E58D62UN con control por red.
- [ ] Menú de ingeniería de Vestel (mantener `0` → Home → `4 7 2 5`) y activar *USB Logging → HTTP Test Server*,
      luego escanear puertos nuevos. Intento fallido hasta ahora: el control temporal no entró al menú.
- [ ] Si algún día hay un control IR o un adaptador UART: reevaluar.

### Mejoras del control del Roku (por orden de prioridad)
- [ ] **Funcionar sin el PC:** probar una página HTML local en el celular que mande las teclas directo al Roku
      (`POST http://IP_ROKU:8060/keypress/<tecla>`, modo `no-cors`). Verificar en el celular que el navegador lo permita.
      Alternativa más pesada: un APK que hable con el Roku.
- [ ] **Código QR** en la ventana del servidor para abrir la web desde el celular sin escribir la dirección.
- [ ] **Servidor siempre disponible:** arrancar con Windows, IP fija para el PC y el Roku. (Modifica el sistema: confirmar antes.)
- [ ] **Búsqueda de contenido** por título entre apps (API de búsqueda del Roku). Probar con cuidado: cambia lo que se reproduce.
- [ ] Mantener la pantalla encendida mientras se usa el control, y mostrar la app activa (`/query/active-app`).
- [ ] Probar en el celular la instalación en pantalla de inicio (con HTTP puede abrirse con la barra del navegador).

## Cómo se usa

1. Ejecutar `Iniciar Control TV.bat` (busca el Roku en la red y guarda su IP en `roku_ip.txt`).
2. Abrir en el celular la dirección que muestra la ventana, por ejemplo `http://192.168.31.14:8090`.
3. El celular y el PC deben estar en el mismo WiFi.
