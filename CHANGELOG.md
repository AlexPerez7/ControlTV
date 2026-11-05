# Cambios por versión

Cada versión nueva debe tener aquí su sección `## X.Y` (el workflow de Release la usa como descripción y falla si falta).

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
