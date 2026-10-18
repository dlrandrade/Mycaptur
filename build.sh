#!/usr/bin/env bash
# Compila o APK sem Gradle. Requer: JDK, aapt, dalvik-exchange, apksigner, zipalign,
# libandroid-23-java (android.jar) -> sudo apt install aapt dalvik-exchange apksigner zipalign libandroid-23-java
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
OUT=build; rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/gen"

aapt package -f -m -J "$OUT/gen" -M app/AndroidManifest.xml -I "$ANDROID_JAR"
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find app/src "$OUT/gen" -name '*.java') 2>&1 | grep -v -E '^(warning|Note|Picked up)' || true
dalvik-exchange --dex --output="$OUT/classes.dex" "$OUT/classes"
aapt package -f -M app/AndroidManifest.xml -I "$ANDROID_JAR" -F "$OUT/unsigned.apk"
(cd "$OUT" && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

[ -f horafix.keystore ] || keytool -genkeypair -keystore horafix.keystore -alias horafix \
  -storepass horafix123 -keypass horafix123 -dname "CN=HoraFix" -keyalg RSA -keysize 2048 -validity 10000 2>/dev/null
apksigner sign --ks horafix.keystore --ks-pass pass:horafix123 --key-pass pass:horafix123 \
  --out HoraFix.apk "$OUT/aligned.apk"
apksigner verify -v HoraFix.apk | head -3
ls -l HoraFix.apk
