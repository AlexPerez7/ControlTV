# ControlTV

Control remoto universal por WiFi para el celular. Maneja varias TV y reproductores desde una sola pantalla:
flechas, OK, volumen, encendido, escribir texto, abrir apps, cambiar de entrada (HDMI) y compartir videos a la TV.

| Marca | Qué se puede hacer |
|---|---|
| **Android TV** (probado en una Caixun) | Teclas, mantener pulsado, volumen, texto, apps (YouTube, Netflix, Prime Video, Disney+, HBO Max, Spotify), compartir enlaces, ajustes, menú, números y cambio de fuente |
| **Roku** | Teclas, texto y apps con su icono |
| **Hisense** (VIDAA) | Volumen (el silencio está emulado) |
| **Vestel** | Solo teclas |

Hay dos formas de usarlo: la **app para Android** (recomendada) o un **servidor en el PC** que sirve la misma página web.
Todo funciona dentro de tu red WiFi; no usa internet ni nube.

## Instalar la app en Android

1. En el celular abre la página de **[Releases](../../releases/latest)** y descarga `ControlTV.apk`.
2. Ábrelo. Android pedirá permiso para "Instalar apps desconocidas" para el navegador o el gestor de archivos: acéptalo.
3. Abre **Control TV**. Debe estar en el **mismo WiFi** que las TV. Busca los dispositivos solo; el botón "Buscar" vuelve a escanear.
4. **Android TV:** la primera vez pide un código de 6 caracteres que aparece en la TV; escríbelo en la app.

Requiere Android 8 o superior.

## Usar el servidor en el PC

Solo necesita Python 3, sin instalar nada más.

```bash
python servidor.py            # en Windows: doble clic en "Iniciar Control TV.bat"
python servidor.py --puerto 8090
```

Imprime una dirección (por ejemplo `http://192.168.1.20:8090`): ábrela en el celular, conectado al mismo WiFi.

## Cosas a tener en cuenta

- **Android TV:** el YouTube de la TV solo acepta caracteres ASCII al escribir (se quitan las tildes) y `?` abre la búsqueda por voz, así que se omite.
  Con el campo vacío, ⌫ actúa como Atrás.
- **Cambio de fuente:** los ids de entradas (HDMI 1-3, Compuesto, TV) son los de TV con chip MediaTek, como la Caixun.
  En otra marca de Android TV el panel aparece pero puede no hacer nada. "Inicio" abre YouTube un instante y pulsa Home.
- **Hisense:** la primera vez pide permiso en pantalla; sin aceptarlo el volumen da error 401.
- **Seguridad:** el APK de los Releases lleva incluida la identidad con la que se emparejó una Android TV. Quien tenga el APK y esté
  en tu WiFi podría controlar una TV ya emparejada con ella. Úsalo en redes de confianza.
  Para no depender de esa identidad, compila tu propia versión (más abajo).

## Compilar la app Android

Necesita el SDK de Android (`aapt2`, `d8`, `apksigner`), un JDK y OpenSSL. Ver `android/build.sh`.

```bash
bash android/build.sh                    # genera android/build/ControlTV.apk
adb install -r android/build/ControlTV.apk
```

La identidad TLS de las Android TV (`androidtv.p12`) no está en el repositorio: se genera con OpenSSL y el script la copia a la app.
El APK se firma con tu propio keystore; guárdalo, porque las actualizaciones solo se instalan encima si van firmadas con la misma clave.

## Pruebas

```bash
python -I -m unittest discover -s tests
```

Cubren protobuf, DER, emparejamiento, texto y enlaces, sin necesitar red ni TV.

## Añadir una marca

Crea una subclase de `Driver` en `dispositivos.py` (con sus `caps`: `keys`, `text`, `apps`, `volume`) y regístrala en `DRIVERS`.
Si no se autodetecta, añade la TV a mano en `dispositivos.json`:

```json
[{"ip": "192.168.1.50", "marca": "vestel", "modelo": "mi TV"}]
```

Más detalle técnico, lo descubierto de cada TV y lo descartado: `plan.md`.
