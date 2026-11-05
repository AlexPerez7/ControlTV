#!/usr/bin/env bash
# Compila ControlTV.apk sin Gradle: aapt2 + javac + d8 + zipalign + apksigner.
# Requisitos (ver CLAUDE.md): source ~/tools/env.sh y el SDK en ~/tools/android-sdk.
set -euo pipefail
AQUI="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/tools/android-sdk}"
# build-tools y plataforma: las de siempre si existen; si no (p. ej. en GitHub Actions), las mas nuevas instaladas.
BT="$SDK/build-tools/36.0.0"
[ -d "$BT" ] || BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
JAR="$SDK/platforms/android-35/android.jar"
[ -f "$JAR" ] || JAR="$SDK/platforms/$(ls "$SDK/platforms" | sort -V | tail -1)/android.jar"
KS="${CONTROLTV_KEYSTORE:-$HOME/tools/controltv.keystore}"   # clave de firma: fuera del repositorio
OUT="$AQUI/build"

rm -rf "$OUT" "$AQUI/assets" && mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex" "$AQUI/assets"  # assets se regenera entero: nunca queda una clave vieja dentro del APK

# La web es la misma que sirve servidor.py: se copia, no se duplica.
cp "$AQUI/../index.html" "$AQUI/../estilos.css" "$AQUI/../app.js" "$AQUI/assets/"
python3 -I - "$AQUI" <<'PY'
import re, sys
aqui = sys.argv[1]
src = open(aqui + "/../servidor.py", encoding="utf-8").read()
m = re.search(r"ICONO_SVG = \((.*?)\)\n\n", src, re.S)
svg = "".join(re.findall(r"'((?:[^'\\]|\\.)*)'", m.group(1)))
open(aqui + "/assets/icono.svg", "w", encoding="utf-8").write(svg)
PY

"$BT/aapt2" compile --dir "$AQUI/res" -o "$OUT/res"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest "$AQUI/AndroidManifest.xml" \
    -A "$AQUI/assets" --min-sdk-version 26 --target-sdk-version 35 --java "$OUT/gen" "$OUT"/res/*.flat

javac --release 17 -Xlint:all -cp "$JAR" -d "$OUT/classes" "$AQUI"/src/com/alexperez/controltv/*.java "$OUT"/gen/com/alexperez/controltv/R.java
(cd "$OUT/classes" && find . -name '*.class' | sed 's|^\./||') > "$OUT/clases.txt"
"$BT/d8" --release --min-api 26 --lib "$JAR" --output "$OUT/dex" $(sed "s|^|$OUT/classes/|" "$OUT/clases.txt")

# Agrega classes.dex al apk (zip) y alinea.
python3 -I - "$OUT" <<'PY'
import sys, zipfile
out = sys.argv[1]
with zipfile.ZipFile(out + "/base.apk", "a") as z:
    z.write(out + "/dex/classes.dex", "classes.dex")
PY
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/alineado.apk"

if [ ! -f "$KS" ]; then
    [ -z "${CI:-}" ] || { echo "Falta el keystore de firma ($KS): en CI debe venir del secreto KEYSTORE_B64"; exit 1; }
    keytool -genkeypair -keystore "$KS" -alias controltv -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass controltv -keypass controltv -dname "CN=ControlTV"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:controltv --key-pass pass:controltv --ks-key-alias controltv \
    --out "$OUT/ControlTV.apk" "$OUT/alineado.apk"
"$BT/apksigner" verify --verbose "$OUT/ControlTV.apk" | head -4
ls -la "$OUT/ControlTV.apk"
