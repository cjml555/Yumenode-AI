#!/usr/bin/env bash
# Reproduce el ANR #3 de Yumenode AI (descarga activa + cambio a Chat).
#
# Uso:  ./tools/anr-repro.sh <apk> [intentos]
#
# Protocolo validado en el Edge 50 Fusion (Android 16):
#   1. desinstalar + instalar (la instalación FRÍA es parte del disparador)
#   2. abrir la app y esperar 12 s
#   3. pestaña «Modelos libres» y pulsar el primer «Descargar modelo»
#   4. esperar 4 s y tocar la pestaña Chat
#   5. esperar 9 s y contar «ANR in com.yumenode» en logcat
#
# Con builds recientes el fallo apareció 10 de 10 veces; con un APK concreto
# (build incremental que llevaba otra libstable-diffusion.so) no apareció nunca.
# Es decir: la tasa depende del binario, así que compara siempre builds del
# mismo origen y usa >=3 intentos por build.
#
# Requisitos: adb con el dispositivo autorizado, app de prueba instalable como
# com.yumenode.ai.qa (compilar con -PqaAppIdSuffix=.qa).
set -u
APK=${1:?falta el APK}
N=${2:-3}
PKG=com.yumenode.ai.qa

centro() {  # $1 = xml, $2 = texto exacto -> "x y"
python3 - "$1" "$2" <<'PY'
import sys, re, xml.etree.ElementTree as ET
try:
    r = ET.parse(sys.argv[1]).getroot()
except Exception:
    print(""); raise SystemExit
for n in r.iter('node'):
    if (n.get('text') or '').strip() == sys.argv[2]:
        b = list(map(int, re.findall(r'\d+', n.get('bounds'))))
        print((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
        break
PY
}

dump() {  # $1 = destino
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  adb pull /sdcard/u.xml "$1" >/dev/null 2>&1
}

total=0; validos=0
for i in $(seq 1 "$N"); do
  adb shell am force-stop $PKG >/dev/null 2>&1
  adb uninstall $PKG >/dev/null 2>&1
  if ! adb install "$APK" >/dev/null 2>&1; then
    echo "  #$i install FALLÓ"; continue
  fi
  adb shell cmd locale set-app-locales $PKG --user 0 --locales es-MX >/dev/null 2>&1
  adb logcat -c
  adb shell am start -n $PKG/com.yumenode.ai.MainActivity >/dev/null
  sleep 12

  dump /tmp/anr_1.xml; T=$(centro /tmp/anr_1.xml "Modelos libres")
  [ -z "$T" ] && { echo "  #$i sin pestaña (¿idioma o app no arrancó?)"; continue; }
  adb shell input tap $T; sleep 5

  dump /tmp/anr_2.xml; D=$(centro /tmp/anr_2.xml "Descargar modelo")
  [ -z "$D" ] && { echo "  #$i sin botón de descarga"; continue; }
  adb shell input tap $D; sleep 4

  MB=$(adb shell "run-as $PKG du -sm files/models/gguf 2>/dev/null" | awk '{print $1}')
  adb shell input tap 90 2305     # pestaña Chat
  sleep 9
  A=$(adb logcat -d | grep -cE 'ANR in com.yumenode')

  echo "  #$i descarga=${MB:-0}MB ANR=$A"
  validos=$((validos + 1)); total=$((total + A))
  adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 1
done

echo "== ANRs=$total en $validos intentos válidos (de $N)"
