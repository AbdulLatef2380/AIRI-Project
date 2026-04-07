#!/bin/bash
set -e

SDK=/home/runner/workspace/.android-sdk
BUILD_TOOLS=$SDK/build-tools/34.0.0
ANDROID_JAR=$SDK/platforms/android-34/android.jar
COROUTINES_JAR=/home/runner/workspace/android/libs/kotlinx-coroutines-core-jvm.jar
COROUTINES_ANDROID_JAR=/home/runner/workspace/android/libs/kotlinx-coroutines-android.jar
SRC_DIR=/home/runner/workspace/android/app/src/main/java
RES_DIR=/home/runner/workspace/android/app/src/main/res
MANIFEST=/home/runner/workspace/android/app/src/main/AndroidManifest.xml
BUILD_DIR=/home/runner/workspace/android/build/apk
OUT_APK=/home/runner/workspace/android/airi-debug.apk

echo "=== AIRI Android APK Build ==="
echo ""

# 1. Setup
echo "[1/8] Setting up build directories..."
rm -rf $BUILD_DIR
mkdir -p $BUILD_DIR/compiled-res
mkdir -p $BUILD_DIR/classes
mkdir -p $BUILD_DIR/dex

# Ensure coroutines jars exist
mkdir -p /home/runner/workspace/android/libs
if [ ! -f "$COROUTINES_JAR" ]; then
    echo "  Downloading kotlinx-coroutines-core..."
    curl -sL -o $COROUTINES_JAR \
      "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.7.3/kotlinx-coroutines-core-jvm-1.7.3.jar"
fi
if [ ! -f "$COROUTINES_ANDROID_JAR" ]; then
    echo "  Downloading kotlinx-coroutines-android..."
    curl -sL -o $COROUTINES_ANDROID_JAR \
      "https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.7.3/kotlinx-coroutines-android-1.7.3.jar"
fi

# 2. Compile resources
echo "[2/8] Compiling resources with aapt2..."
$BUILD_TOOLS/aapt2 compile \
    --dir $RES_DIR \
    -o $BUILD_DIR/compiled-res/compiled.zip

# 3. Link resources
echo "[3/8] Linking resources with aapt2..."
$BUILD_TOOLS/aapt2 link \
    $BUILD_DIR/compiled-res/compiled.zip \
    -I $ANDROID_JAR \
    --manifest $MANIFEST \
    --java $BUILD_DIR/classes \
    --min-sdk-version 26 \
    --target-sdk-version 34 \
    --version-code 1 \
    --version-name "1.0" \
    -o $BUILD_DIR/resources.apk

echo "  Resources linked. R.java generated."

# 4. Compile Kotlin sources
echo "[4/8] Compiling Kotlin sources..."
SOURCES=$(find $SRC_DIR -name "*.kt" | tr '\n' ' ')
SOURCE_COUNT=$(find $SRC_DIR -name "*.kt" | wc -l)
echo "  Compiling $SOURCE_COUNT Kotlin files..."

kotlinc $SOURCES \
    -cp "$ANDROID_JAR:$COROUTINES_JAR:$COROUTINES_ANDROID_JAR" \
    -d $BUILD_DIR/classes.jar \
    -jvm-target 17 \
    2>&1

echo "  Kotlin compilation complete."

# 5. Dex with d8
echo "[5/8] Converting to DEX (d8)..."
$BUILD_TOOLS/d8 \
    --classpath $ANDROID_JAR \
    --min-api 26 \
    --output $BUILD_DIR/dex/ \
    $BUILD_DIR/classes.jar \
    $COROUTINES_JAR \
    $COROUTINES_ANDROID_JAR

echo "  DEX conversion complete."
ls -lh $BUILD_DIR/dex/classes.dex

# 6. Assemble APK
echo "[6/8] Assembling APK..."
cp $BUILD_DIR/resources.apk $BUILD_DIR/unsigned.apk
cd $BUILD_DIR/dex && zip -j $BUILD_DIR/unsigned.apk classes*.dex
cd /home/runner/workspace/android

# 7. Zipalign
echo "[7/8] Zipaligning APK..."
$BUILD_TOOLS/zipalign -v -f 4 $BUILD_DIR/unsigned.apk $BUILD_DIR/aligned.apk > /dev/null 2>&1
echo "  Zipalign complete."

# 8. Sign APK (debug keystore)
echo "[8/8] Signing APK..."
KEYSTORE=$BUILD_DIR/debug.keystore
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkey -v \
        -keystore $KEYSTORE \
        -alias androiddebugkey \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass android \
        -keypass android \
        -dname "CN=Android Debug,O=Android,C=US" \
        -noprompt 2>&1 | tail -2
fi

$BUILD_TOOLS/apksigner sign \
    --ks $KEYSTORE \
    --ks-pass pass:android \
    --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out $OUT_APK \
    $BUILD_DIR/aligned.apk

echo ""
echo "=== BUILD COMPLETE ==="
echo "APK: $OUT_APK"
ls -lh $OUT_APK
$BUILD_TOOLS/aapt dump badging $OUT_APK 2>/dev/null | grep -E "(package|application-label|sdkVersion|targetSdkVersion)" | head -8
