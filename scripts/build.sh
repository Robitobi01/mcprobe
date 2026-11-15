#!/bin/sh
# Build build/mcprobe.jar. Needs a Java 8 JDK (javac + jar) on PATH.
set -eu
cd "$(dirname "$0")/.."
rm -rf build
mkdir -p build/classes
javac -source 8 -target 8 -Xlint:-options -d build/classes $(find src/main/java -name '*.java')
cat > build/manifest.mf <<MF
Manifest-Version: 1.0
Premain-Class: net.dugged.mcprobe.McProbe
MF
jar cfm build/mcprobe.jar build/manifest.mf -C build/classes .
echo build/mcprobe.jar
