#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
fixture_dir="app/build/search-fixture"
mkdir -p "$fixture_dir/classes" app/src/androidTest/assets
bash gradlew :catvod:bundleLibCompileToJarDebug --offline --console=plain
javac -source 8 -target 8 -cp "catvod/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar:$sdk/platforms/android-36/android.jar" -d "$fixture_dir/classes" app/src/androidTest/fixtures/*.java
"$sdk/build-tools/36.0.0/d8" --min-api 24 --lib "$sdk/platforms/android-36/android.jar" --classpath catvod/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar --output "$fixture_dir/search-fixture.zip" "$fixture_dir/classes/com/github/catvod/spider/"*.class
cp "$fixture_dir/search-fixture.zip" app/src/androidTest/assets/search-fixture.zip
