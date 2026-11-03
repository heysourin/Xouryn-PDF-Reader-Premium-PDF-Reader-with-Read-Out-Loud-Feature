#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$PROJECT_DIR/.build-tools"
SDK_DIR="$TOOLS_DIR/android-sdk"
JDK_DIR="$TOOLS_DIR/jdk17"
GRADLE_DIR="$TOOLS_DIR/gradle-8.11.1"

echo "===================================================="
echo "   Xouryn PDF Reader Automated APK Build Script"
echo "===================================================="

mkdir -p "$TOOLS_DIR"

# 1. Setup JDK 17
if [ -f "$JDK_DIR/Contents/Home/bin/java" ]; then
    export JAVA_HOME="$JDK_DIR/Contents/Home"
elif [ -f "$JDK_DIR/bin/java" ]; then
    export JAVA_HOME="$JDK_DIR"
elif command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q "17"; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || dirname $(dirname $(which java)))"
fi

export PATH="$JAVA_HOME/bin:$PATH"
echo "✔ Using Java: $(java -version 2>&1 | head -n 1)"

# 2. Setup Android SDK
export ANDROID_HOME="$SDK_DIR"
export PATH="$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools:$PATH"
echo "sdk.dir=$SDK_DIR" > "$PROJECT_DIR/local.properties"
echo "✔ Using Android SDK: $ANDROID_HOME"

# 3. Setup Gradle
if [ ! -x "$GRADLE_DIR/bin/gradle" ]; then
    echo "⬇ Downloading Gradle 8.11.1..."
    GRADLE_ZIP="$TOOLS_DIR/gradle.zip"
    curl -L "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip" -o "$GRADLE_ZIP"
    unzip -q -o "$GRADLE_ZIP" -d "$TOOLS_DIR"
    rm -f "$GRADLE_ZIP"
fi

echo "✔ Using Gradle: $("$GRADLE_DIR/bin/gradle" --version 2>&1 | head -n 3 | tail -n 1)"

# Generate wrapper jar if needed
"$GRADLE_DIR/bin/gradle" wrapper --gradle-version 8.11.1 >/dev/null 2>&1 || true

# 4. Compile Xouryn PDF Reader APK
cd "$PROJECT_DIR"
echo "🔨 Compiling Xouryn PDF Reader APK..."
"$GRADLE_DIR/bin/gradle" assembleDebug

OUTPUT_APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
FINAL_APK="$PROJECT_DIR/Xouryn-PDF-Reader.apk"
LOWER_APK="$PROJECT_DIR/xouryn-pdf-reader.apk"
ALT_APK="$PROJECT_DIR/xouryn_pdf_reader.apk"
LEGACY_APK="$PROJECT_DIR/NovaPDF-debug.apk"

if [ -f "$OUTPUT_APK" ]; then
    cp "$OUTPUT_APK" "$FINAL_APK"
    cp "$OUTPUT_APK" "$LOWER_APK"
    cp "$OUTPUT_APK" "$ALT_APK"
    cp "$OUTPUT_APK" "$LEGACY_APK"
    echo ""
    echo "===================================================="
    echo "🎉 SUCCESS! Your APK is ready:"
    echo "   $FINAL_APK"
    echo "   $LOWER_APK"
    echo "===================================================="
    echo ""
    ls -lh "$FINAL_APK"
    echo ""
    echo "You can now install 'Xouryn-PDF-Reader.apk' directly on your phone or tablet!"
else
    echo "❌ Build did not produce an APK. Please check logs."
    exit 1
fi
