# ============================================================
#  OpenBladeRunner - Build and Deploy (Windows PowerShell)
# ============================================================

$ErrorActionPreference = "Stop"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " OpenBladeRunner - Build and Deploy" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# --- Find JAVA_HOME ---
if (-not $env:JAVA_HOME) {
    $candidates = @(
        "C:\Program Files\Android\Android Studio\jbr",
        "C:\Program Files\Java\jdk-*"
    )
    foreach ($c in $candidates) {
        $found = Get-Item $c -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found -and (Test-Path "$($found.FullName)\bin\java.exe")) {
            $env:JAVA_HOME = $found.FullName
            break
        }
    }
}
if (-not $env:JAVA_HOME) {
    Write-Host "ERROR: JAVA_HOME not found. Install JDK 11+ or Android Studio." -ForegroundColor Red
    exit 1
}
Write-Host "JAVA_HOME = $env:JAVA_HOME"

# --- Find Android SDK ---
$SDK = $env:ANDROID_SDK_ROOT
if (-not $SDK) { $SDK = $env:ANDROID_HOME }
if (-not $SDK) {
    $sdkCandidates = @(
        "C:\Android\android-sdk",
        "$env:LOCALAPPDATA\Android\Sdk"
    )
    foreach ($c in $sdkCandidates) {
        if (Test-Path $c) { $SDK = $c; break }
    }
}
if (-not $SDK) {
    Write-Host "ERROR: Android SDK not found. Set ANDROID_SDK_ROOT." -ForegroundColor Red
    exit 1
}
Write-Host "SDK        = $SDK"

# --- Find latest build-tools and platform ---
$BT = Get-ChildItem "$SDK\build-tools" -Directory | Sort-Object Name | Select-Object -Last 1
$PLATFORM = Get-ChildItem "$SDK\platforms" -Directory | Sort-Object Name | Select-Object -Last 1
if (-not $BT -or -not $PLATFORM) {
    Write-Host "ERROR: No build-tools or platform found in SDK." -ForegroundColor Red
    exit 1
}
Write-Host "BUILD_TOOLS= $($BT.FullName)"
Write-Host "PLATFORM   = $($PLATFORM.FullName)"

# --- Tool paths ---
$JAVAC    = "$env:JAVA_HOME\bin\javac.exe"
$JAVA     = "$env:JAVA_HOME\bin\java.exe"
$JAR      = "$env:JAVA_HOME\bin\jar.exe"
$KEYTOOL  = "$env:JAVA_HOME\bin\keytool.exe"
$AAPT2    = "$($BT.FullName)\aapt2.exe"
$ZIPALIGN = "$($BT.FullName)\zipalign.exe"
$ADB      = "$SDK\platform-tools\adb.exe"
$D8_JAR   = "$($BT.FullName)\lib\d8.jar"
$SIGNER   = "$($BT.FullName)\lib\apksigner.jar"
$ANDROID_JAR = "$($PLATFORM.FullName)\android.jar"

# --- Project paths ---
$APP_DIR  = Join-Path $PSScriptRoot "app"
$OUT      = Join-Path $APP_DIR "build"

Write-Host ""
Write-Host "=== Cleaning ===" -ForegroundColor Yellow
if (Test-Path $OUT) { Remove-Item $OUT -Recurse -Force }
New-Item -ItemType Directory -Path "$OUT\compiled", "$OUT\classes", "$OUT\gen", "$OUT\lib\armeabi-v7a" -Force | Out-Null

Write-Host "=== Compiling resources ===" -ForegroundColor Yellow
& $AAPT2 compile --dir "$APP_DIR\res" -o "$OUT\compiled\"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "=== Linking resources ===" -ForegroundColor Yellow
$flatFiles = Get-ChildItem "$OUT\compiled\*.flat" | ForEach-Object { $_.FullName }
& $AAPT2 link -o "$OUT\resources.apk" -I $ANDROID_JAR --manifest "$APP_DIR\AndroidManifest.xml" --java "$OUT\gen" --auto-add-overlay @flatFiles
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "=== Compiling Java ===" -ForegroundColor Yellow
$srcFiles = @(
    "$APP_DIR\src\android_serialport_api\SerialPort.java",
    "$APP_DIR\src\com\cutter\plotterctl\PlotterProtocol.java",
    "$APP_DIR\src\com\cutter\plotterctl\CutJob.java",
    "$APP_DIR\src\com\cutter\plotterctl\HpglParser.java",
    "$APP_DIR\src\com\cutter\plotterctl\WebUI.java",
    "$APP_DIR\src\com\cutter\plotterctl\WebServer.java",
    "$APP_DIR\src\com\cutter\plotterctl\MainActivity.java",
    "$OUT\gen\com\cutter\plotterctl\R.java"
)
& $JAVAC -source 11 -target 11 -classpath $ANDROID_JAR -d "$OUT\classes" @srcFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "=== Converting to DEX ===" -ForegroundColor Yellow
$classFiles = Get-ChildItem "$OUT\classes" -Recurse -Filter "*.class" | ForEach-Object { $_.FullName }
& $JAVA -cp $D8_JAR com.android.tools.r8.D8 --output $OUT --min-api 24 @classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "=== Building APK ===" -ForegroundColor Yellow
Copy-Item "$OUT\resources.apk" "$OUT\plotterctl-unsigned.apk"

Push-Location $OUT
& $JAR uf "plotterctl-unsigned.apk" classes.dex
Pop-Location

Copy-Item "$APP_DIR\libs\armeabi-v7a\libserial_port.so" "$OUT\lib\armeabi-v7a\"
Push-Location $OUT
& $JAR uf "plotterctl-unsigned.apk" lib/armeabi-v7a/libserial_port.so
Pop-Location

Write-Host "=== Aligning ===" -ForegroundColor Yellow
& $ZIPALIGN -f 4 "$OUT\plotterctl-unsigned.apk" "$OUT\plotterctl-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "=== Signing ===" -ForegroundColor Yellow
$KEYSTORE = "$APP_DIR\debug.keystore"
if (-not (Test-Path $KEYSTORE)) {
    & $KEYTOOL -genkeypair -keystore $KEYSTORE -storepass android -keypass android -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Debug,O=OpenBladeRunner,C=US"
}
& $JAVA -jar $SIGNER sign --ks $KEYSTORE --ks-pass pass:android --key-pass pass:android --ks-key-alias debug --out "$OUT\plotterctl.apk" "$OUT\plotterctl-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

$apkSize = [math]::Round((Get-Item "$OUT\plotterctl.apk").Length / 1KB, 1)
Write-Host ""
Write-Host "=== Build successful! ($apkSize KB) ===" -ForegroundColor Green
Write-Host "APK: $OUT\plotterctl.apk"
Write-Host ""

# --- Deploy ---
if (-not (Test-Path $ADB)) {
    Write-Host "adb not found, skipping deploy." -ForegroundColor Yellow
    exit 0
}

# Find a physical device (skip emulators)
$deviceSerial = $null
$adbOutput = & $ADB devices | Where-Object { $_ -match "^\S+\s+device$" }
foreach ($line in $adbOutput) {
    $serial = ($line -split "\s+")[0]
    if ($serial -and $serial -notmatch "^emulator-") {
        $deviceSerial = $serial
        break
    }
}
if (-not $deviceSerial) {
    Write-Host "No physical device connected. Skipping deploy." -ForegroundColor Yellow
    Write-Host "To deploy manually:" -ForegroundColor Gray
    Write-Host "  adb push $OUT\plotterctl.apk /sdcard/plotterctl.apk" -ForegroundColor Gray
    Write-Host "  adb shell pm install -r /sdcard/plotterctl.apk" -ForegroundColor Gray
    Write-Host "  adb shell am start -n com.cutter.plotterctl/.MainActivity" -ForegroundColor Gray
    exit 0
}

Write-Host "=== Deploying to device $deviceSerial ===" -ForegroundColor Yellow
& $ADB -s $deviceSerial push "$OUT\plotterctl.apk" /sdcard/plotterctl.apk
& $ADB -s $deviceSerial shell pm install -r /sdcard/plotterctl.apk
if ($LASTEXITCODE -ne 0) {
    Write-Host "Install failed, trying uninstall first..." -ForegroundColor Yellow
    & $ADB -s $deviceSerial shell pm uninstall com.cutter.plotterctl
    & $ADB -s $deviceSerial shell pm install /sdcard/plotterctl.apk
}

Write-Host "=== Launching ===" -ForegroundColor Yellow
& $ADB -s $deviceSerial shell am force-stop com.plotter.pea.lamelplotterapp
& $ADB -s $deviceSerial shell am force-stop com.cutter.plotterctl
& $ADB -s $deviceSerial shell am start -n com.cutter.plotterctl/.MainActivity

Write-Host ""
Write-Host "=== Deploy complete! ===" -ForegroundColor Green
