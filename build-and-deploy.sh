#!/usr/bin/env bash
# ============================================================
#  OpenBladeRunner - Build and Deploy (Linux / macOS)
# ============================================================

set -e

echo "============================================================"
echo " OpenBladeRunner - Build and Deploy"
echo "============================================================"
echo ""

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$SCRIPT_DIR/app"
OUT="$APP_DIR/build"

# --- Find JAVA_HOME ---
if [ -z "$JAVA_HOME" ]; then
    for candidate in \
        "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
        "$HOME/android-studio/jbr" \
        "$HOME/Android/android-studio/jbr" \
        "/usr/lib/jvm/java-17-openjdk-amd64" \
        "/usr/lib/jvm/java-11-openjdk-amd64"; do
        if [ -x "$candidate/bin/java" ]; then
            JAVA_HOME="$candidate"
            break
        fi
    done
fi
if [ -z "$JAVA_HOME" ]; then
    echo "ERROR: JAVA_HOME not found. Install JDK 11+ or Android Studio."
    exit 1
fi
echo "JAVA_HOME = $JAVA_HOME"

# --- Find Android SDK ---
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
    for candidate in \
        "$HOME/Android/Sdk" \
        "$HOME/Library/Android/sdk" \
        "/opt/android-sdk"; do
        if [ -d "$candidate" ]; then
            SDK="$candidate"
            break
        fi
    done
fi
if [ -z "$SDK" ]; then
    echo "ERROR: Android SDK not found. Set ANDROID_SDK_ROOT."
    exit 1
fi
echo "SDK        = $SDK"

# --- Find latest build-tools and platform ---
BUILD_TOOLS="$SDK/build-tools/$(ls "$SDK/build-tools/" | sort -V | tail -1)"
PLATFORM="$SDK/platforms/$(ls "$SDK/platforms/" | sort -V | tail -1)"
echo "BUILD_TOOLS= $BUILD_TOOLS"
echo "PLATFORM   = $PLATFORM"

# --- Tool paths ---
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"
JAR="$JAVA_HOME/bin/jar"
KEYTOOL="$JAVA_HOME/bin/keytool"
AAPT2="$BUILD_TOOLS/aapt2"
ZIPALIGN="$BUILD_TOOLS/zipalign"
ADB="$SDK/platform-tools/adb"

echo ""
echo "=== Cleaning ==="
rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/gen" "$OUT/lib/armeabi-v7a"

echo "=== Compiling resources ==="
"$AAPT2" compile --dir "$APP_DIR/res" -o "$OUT/compiled/"

echo "=== Linking resources ==="
"$AAPT2" link \
    -o "$OUT/resources.apk" \
    -I "$PLATFORM/android.jar" \
    --manifest "$APP_DIR/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --auto-add-overlay \
    "$OUT/compiled/"*.flat

echo "=== Compiling Java ==="
"$JAVAC" \
    -source 11 -target 11 \
    -classpath "$PLATFORM/android.jar" \
    -d "$OUT/classes" \
    "$APP_DIR/src/android_serialport_api/SerialPort.java" \
    "$APP_DIR/src/com/cutter/plotterctl/PlotterProtocol.java" \
    "$APP_DIR/src/com/cutter/plotterctl/CutJob.java" \
    "$APP_DIR/src/com/cutter/plotterctl/HpglParser.java" \
    "$APP_DIR/src/com/cutter/plotterctl/WebUI.java" \
    "$APP_DIR/src/com/cutter/plotterctl/WebServer.java" \
    "$APP_DIR/src/com/cutter/plotterctl/MainActivity.java" \
    "$OUT/gen/com/cutter/plotterctl/R.java"

echo "=== Converting to DEX ==="
CLASS_FILES=$(find "$OUT/classes" -name "*.class")
"$JAVA" -cp "$BUILD_TOOLS/lib/d8.jar" com.android.tools.r8.D8 \
    --output "$OUT" \
    --min-api 24 \
    $CLASS_FILES

echo "=== Building APK ==="
cp "$OUT/resources.apk" "$OUT/plotterctl-unsigned.apk"

(cd "$OUT" && "$JAR" uf "plotterctl-unsigned.apk" classes.dex)

cp "$APP_DIR/libs/armeabi-v7a/libserial_port.so" "$OUT/lib/armeabi-v7a/"
(cd "$OUT" && "$JAR" uf "plotterctl-unsigned.apk" lib/armeabi-v7a/libserial_port.so)

echo "=== Aligning ==="
"$ZIPALIGN" -f 4 "$OUT/plotterctl-unsigned.apk" "$OUT/plotterctl-aligned.apk"

echo "=== Signing ==="
KEYSTORE="$APP_DIR/debug.keystore"
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

APK_SIZE=$(du -h "$OUT/plotterctl.apk" | cut -f1)
echo ""
echo "=== Build successful! ($APK_SIZE) ==="
echo "APK: $OUT/plotterctl.apk"
echo ""

# --- Deploy ---
if [ ! -x "$ADB" ]; then
    echo "adb not found, skipping deploy."
    exit 0
fi

# Find a physical device (skip emulators)
DEVICE=$("$ADB" devices | grep "device$" | grep -v "^emulator-" | head -1 | awk '{print $1}')
if [ -z "$DEVICE" ]; then
    echo "No physical device connected. Skipping deploy."
    echo "  To deploy manually:"
    echo "    adb push $OUT/plotterctl.apk /sdcard/plotterctl.apk"
    echo "    adb shell pm install -r /sdcard/plotterctl.apk"
    echo "    adb shell am start -n com.cutter.plotterctl/.MainActivity"
    exit 0
fi

echo "=== Deploying to device $DEVICE ==="
"$ADB" -s "$DEVICE" push "$OUT/plotterctl.apk" /sdcard/plotterctl.apk
"$ADB" -s "$DEVICE" shell pm install -r /sdcard/plotterctl.apk || {
    echo "Install failed, trying uninstall first..."
    "$ADB" -s "$DEVICE" shell pm uninstall com.cutter.plotterctl
    "$ADB" -s "$DEVICE" shell pm install /sdcard/plotterctl.apk
}

echo "=== Launching ==="
"$ADB" -s "$DEVICE" shell am force-stop com.plotter.pea.lamelplotterapp
"$ADB" -s "$DEVICE" shell am force-stop com.cutter.plotterctl
"$ADB" -s "$DEVICE" shell am start -n com.cutter.plotterctl/.MainActivity

echo ""
echo "=== Deploy complete! ==="
