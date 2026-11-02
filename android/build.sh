#!/usr/bin/env bash
# Compila ControlTV.apk sin Gradle: aapt2 + javac + d8 + zipalign + apksigner.
# Requisitos (ver CLAUDE.md): source ~/tools/env.sh y el SDK en ~/tools/android-sdk.
set -euo pipefail
AQUI="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/tools/android-sdk}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-35/android.jar"
KS="${CONTROLTV_KEYSTORE:-$HOME/tools/controltv.keystore}"   # clave de firma: fuera del repositorio
OUT="$AQUI/build"

rm -rf "$OUT" && mkdir -p "$OUT/res" "$OUT/classes" "$OUT/dex" "$AQUI/assets"

# La web es la misma que sirve servidor.py: se copia, no se duplica.
cp "$AQUI/../index.html" "$AQUI/assets/index.html"
# Identidad TLS de Android TV (clave privada: vive fuera del repo; ver CLAUDE.md). Se genera con openssl pkcs12 -export.
P12="${CONTROLTV_ANDROIDTV_P12:-$HOME/tools/androidtv.p12}"
[ -f "$P12" ] || { echo "Falta $P12 (identidad Android TV)"; exit 1; }
cp "$P12" "$AQUI/assets/androidtv.p12"
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
    -A "$AQUI/assets" --min-sdk-version 26 --target-sdk-version 35 "$OUT"/res/*.flat

javac --release 17 -Xlint:all -cp "$JAR" -d "$OUT/classes" "$AQUI"/src/com/alexperez/controltv/*.java
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
    keytool -genkeypair -keystore "$KS" -alias controltv -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass controltv -keypass controltv -dname "CN=ControlTV"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:controltv --key-pass pass:controltv --ks-key-alias controltv \
    --out "$OUT/ControlTV.apk" "$OUT/alineado.apk"
"$BT/apksigner" verify --verbose "$OUT/ControlTV.apk" | head -4
ls -la "$OUT/ControlTV.apk"
