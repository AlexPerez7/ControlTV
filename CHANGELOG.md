# Cambios por versión

Cada versión nueva debe tener aquí su sección `## X.Y` (el workflow de Release la usa como descripción y falla si falta).

## 1.9

### Interno
- **Diagnóstico del emparejamiento de Android TV:** la app registra en el log (`adb logcat -s ControlTV`) la huella del certificado de este celular y si la clave es nueva o ya existía. Sirve para saber, cuando una TV olvida el emparejamiento, si el cambio vino del celular o de la TV. No cambia el comportamiento.

## 1.8

### Correcciones
- **Android TV: "no responde" cuando la TV olvida el emparejamiento.** Si la TV dejaba de reconocer el celular (por ejemplo tras reiniciarla), la app seguía creyéndolo emparejado y solo decía "no responde". Ahora revisa el emparejamiento en cada escaneo: si la TV está encendida pero nos rechaza, muestra "Esta TV necesita emparejarse" para volver a hacerlo con el código; si la TV está apagada, conserva lo guardado.

## 1.7

### Novedades
- **Widget con selector de TV:** la fila de arriba muestra la TV elegida y al tocarla pasa a la siguiente (también queda elegida en la app, y si eliges otra en la app el widget se actualiza). Los botones cambian según la TV: Vol −, Silencio, Vol + y Pausa en Android TV y Hisense; Atrás, Inicio, Pausa y Repetir en el Roku. El widget ahora ocupa 2 filas de alto y se puede redimensionar; si lo tenías puesto de la versión anterior, quítalo y añádelo de nuevo si se ve apretado.

## 1.6

### Novedades
- **Rediseño de la web y la app:** estilo de mando físico (grafito, anillo de navegación con el OK como única luz) y la web separada en `index.html`, `estilos.css` y `app.js`.
- **Mando propio para el Roku:** Atrás arriba a la izquierda, línea "Ahora" (inicio, salvapantallas o "YouTube · en pausa"), acento morado y el panel de apps abierto. Sin encendido ni volumen, que el Roku Express no tiene.
- **Pulsación larga real en el Roku** (flechas, Retroceder y Avanzar) y **búsqueda de película o serie por título**: abre los resultados en la TV sin reproducir nada.
- **Android TV (Caixun):** Encender con Wake-on-LAN (la MAC se detecta sola o se escribe en Ajustes), pulsación larga real, iconos reales de las apps y widget de pantalla de inicio (Vol −, Silencio, Vol +, Pausa).
- **Dispositivos recordados:** una TV apagada del todo sigue en la lista (○) y los ids son estables aunque cambie la IP (se pierde una vez la TV elegida).

### Interno
- 69 pruebas sin red (`tests/test_protocolo.py`, `tests/test_dispositivos.py`).

## 1.5

### Novedades
- **Actualizaciones desde la app** (Android): al abrir, la app consulta los Releases de GitHub y avisa si hay una versión nueva. En **Ajustes y actualización** se ve la versión instalada, **Buscar actualizaciones** y **Actualizar a X** con las novedades de esa versión. La descarga se verifica con su SHA-256 y Android pide confirmar la instalación (solo acepta un APK con la misma firma). La primera vez hay que permitir que Control TV instale apps.

### Interno
- Permiso `REQUEST_INSTALL_PACKAGES` y nueva clase `Actualizador` (`PackageInstaller`).

## 1.4

### Novedades
- **Asistente de voz de Google** (solo en la app Android, Android TV): botón "Asistente de voz", se mantiene pulsado y se habla; el micrófono del celular se envía a la TV (probado en la Caixun: "Abre Netflix"). Pide el permiso de micrófono la primera vez.
- **Botones nuevos en "Más botones"** (Android TV): Detener y las teclas de colores (rojo, verde, amarillo, azul).
- **Compartir a la TV**: se comprobó que un enlace de YouTube enviado a la TV reproduce el video.

### Mejoras
- El estado de las teclas se muestra en español ("✓ Arriba", "✓ Volumen +") en vez del nombre técnico.
- Mensajes de error de los drivers con sus tildes ("Código incorrecto", "La TV no aceptó la conexión…").
- Accesibilidad: el estado se anuncia con TalkBack (`aria-live`).
- La app abre al instante con la lista de dispositivos guardada y los iconos del Roku se guardan.
- Robustez: el certificado de Android TV se crea sin `openssl`, freno al volumen físico del celular y bloqueos más seguros.

### Verificado en la Caixun (Android TV)
- Abrir Prime Video, Disney+ y HBO Max desde la app. Ajustes funciona; Exit y el asistente por tecla no existen en el protocolo remoto de esa TV (por eso el asistente usa voz).

### Interno
- CI en cada push y pull request (pruebas y `py_compile`).
- El Release publica esta sección del changelog como descripción.
