#!/bin/bash
set -e

# ============================================================
# OpenBladeRunner build script
# Builds the APK using Android SDK command-line tools (no Gradle)
#
# Prerequisites:
#   - ANDROID_SDK_ROOT or ANDROID_HOME set, OR edit SDK path below
#   - Java JDK 11+ (JAVA_HOME set, or Android Studio's JBR)
# ============================================================

# --- Configure paths ---
# Auto-detect JAVA_HOME if not set
if [ -z "$JAVA_HOME" ]; then
    if [ -d "/c/Program Files/Android/Android Studio/jbr" ]; then
        JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
    elif [ -d "$HOME/Android/android-studio/jbr" ]; then
        JAVA_HOME="$HOME/Android/android-studio/jbr"
    fi
fi

# Auto-detect Android SDK
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
    for candidate in "/c/Android/android-sdk" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
        if [ -d "$candidate" ]; then SDK="$candidate"; break; fi
    done
fi

if [ -z "$JAVA_HOME" ] || [ -z "$SDK" ]; then
    echo "ERROR: Set JAVA_HOME and ANDROID_SDK_ROOT (or ANDROID_HOME)"
    echo "  JAVA_HOME=$JAVA_HOME"
    echo "  SDK=$SDK"
    exit 1
fi

# Find latest build-tools and platform
BUILD_TOOLS="$SDK/build-tools/$(ls "$SDK/build-tools/" | sort -V | tail -1)"
PLATFORM="$SDK/platforms/$(ls "$SDK/platforms/" | sort -V | tail -1)"

echo "Using:"
echo "  JAVA_HOME=$JAVA_HOME"
echo "  SDK=$SDK"
echo "  BUILD_TOOLS=$BUILD_TOOLS"
echo "  PLATFORM=$PLATFORM"

# Tool paths (add .exe suffix on Windows/MSYS)
EXE=""
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "win32" || "$OSTYPE" == "cygwin" ]]; then
    EXE=".exe"
fi

JAVAC="$JAVA_HOME/bin/javac${EXE}"
JAR="$JAVA_HOME/bin/jar${EXE}"
KEYTOOL="$JAVA_HOME/bin/keytool${EXE}"
JAVA="$JAVA_HOME/bin/java${EXE}"
AAPT2="$BUILD_TOOLS/aapt2${EXE}"
ZIPALIGN="$BUILD_TOOLS/zipalign${EXE}"

# Project paths
PROJECT="$(cd "$(dirname "$0")" && pwd)"
OUT="$PROJECT/build"

echo ""
echo "=== Cleaning ==="
rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/apk/lib/armeabi-v7a"

echo "=== Compiling resources ==="
"$AAPT2" compile --dir "$PROJECT/res" -o "$OUT/compiled/"

echo "=== Linking resources ==="
"$AAPT2" link \
    --proto-format \
    -o "$OUT/apk.tmp.apk" \
    -I "$PLATFORM/android.jar" \
    --manifest "$PROJECT/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --auto-add-overlay \
    "$OUT/compiled/"*.flat

"$AAPT2" link \
    -o "$OUT/resources.apk" \
    -I "$PLATFORM/android.jar" \
    --manifest "$PROJECT/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --auto-add-overlay \
    "$OUT/compiled/"*.flat

echo "=== Compiling Java ==="
"$JAVAC" \
    -source 11 -target 11 \
    -classpath "$PLATFORM/android.jar" \
    -d "$OUT/classes" \
    "$PROJECT/src/android_serialport_api/SerialPort.java" \
    "$PROJECT/src/com/cutter/plotterctl/PlotterProtocol.java" \
    "$PROJECT/src/com/cutter/plotterctl/CutJob.java" \
    "$PROJECT/src/com/cutter/plotterctl/HpglParser.java" \
    "$PROJECT/src/com/cutter/plotterctl/WebUI.java" \
    "$PROJECT/src/com/cutter/plotterctl/WebServer.java" \
    "$PROJECT/src/com/cutter/plotterctl/MainActivity.java" \
    "$OUT/gen/com/cutter/plotterctl/R.java"

echo "=== Converting to DEX ==="
CLASS_FILES=$(find "$OUT/classes" -name "*.class")
"$JAVA" -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
    --output "$OUT" \
    --min-api 24 \
    $CLASS_FILES

echo "=== Building APK ==="
cp "$OUT/resources.apk" "$OUT/plotterctl-unsigned.apk"

cd "$OUT"
"$JAR" uf "plotterctl-unsigned.apk" classes.dex
cd "$PROJECT"

mkdir -p "$OUT/lib/armeabi-v7a"
cp "$PROJECT/libs/armeabi-v7a/libserial_port.so" "$OUT/lib/armeabi-v7a/"
cd "$OUT"
"$JAR" uf "plotterctl-unsigned.apk" lib/armeabi-v7a/libserial_port.so
cd "$PROJECT"

echo "=== Aligning ==="
"$ZIPALIGN" -f 4 "$OUT/plotterctl-unsigned.apk" "$OUT/plotterctl-aligned.apk"

echo "=== Signing ==="
KEYSTORE="$PROJECT/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
    "$KEYTOOL" -genkeypair \
        -keystore "$KEYSTORE" \
        -storepass android \
        -keypass android \
        -alias debug \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -dname "CN=Debug,O=OpenBladeRunner,C=US"
fi

"$JAVA" -jar "$BUILD_TOOLS/lib/apksigner.jar" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --ks-key-alias debug \
    --out "$OUT/plotterctl.apk" \
    "$OUT/plotterctl-aligned.apk"

echo ""
echo "=== Done! ==="
echo "APK: $OUT/plotterctl.apk"
ls -la "$OUT/plotterctl.apk"
