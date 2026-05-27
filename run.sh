#!/bin/bash
# Run wwwjs with JavaFX module path from Maven local repository
# Auto-detects headless environments and uses xvfb-run when needed.
set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/target/wwwjs-1.0-SNAPSHOT.jar"
MR="$HOME/.m2/repository/org/openjfx"
FV="21"

# Rebuild if classes are stale (incremental compile is fast)
cd "$DIR" && mvn compile -q 2>&1

if [ ! -f "$JAR" ]; then
	cd "$DIR" && mvn clean package -q -DskipTests
fi

# Find all javafx module jars
MODPATH=""
for mod in base graphics controls web media swing; do
	jar="$MR/javafx-$mod/$FV/javafx-$mod-$FV.jar"
	jar_linux="$MR/javafx-$mod/$FV/javafx-$mod-$FV-linux.jar"
	[ -f "$jar" ] && MODPATH="$MODPATH:$jar"
	[ -f "$jar_linux" ] && MODPATH="$MODPATH:$jar_linux"
done
MODPATH="$MODPATH:$JAR"

JAVA_CMD=(java --module-path "$MODPATH" \
     --add-modules javafx.controls,javafx.web \
     -cp "$DIR/target/classes:$JAR" \
     com.example.wwwjs.wwwjs "$@")

# If DISPLAY is not set, try xvfb-run for headless environments
if [ -z "${DISPLAY:-}" ]; then
	if command -v xvfb-run &>/dev/null; then
		echo "WARNING: DISPLAY not set, using xvfb-run (virtual framebuffer)." >&2
		exec xvfb-run "${JAVA_CMD[@]}"
	else
		echo "WARNING: DISPLAY not set and xvfb-run not found." >&2
		echo "  Install xvfb: sudo apt install xvfb" >&2
		echo "  Trying to run anyway (JavaFX will likely fail)..." >&2
	fi
fi

exec "${JAVA_CMD[@]}"
