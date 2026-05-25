#!/bin/bash
# Run wwwjs with JavaFX module path from Maven local repository
set -e

DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/target/wwwjs-1.0-SNAPSHOT.jar"
MR="$HOME/.m2/repository/org/openjfx"
FV="21"

if [ ! -f "$JAR" ]; then
	echo "Building first..."
	cd "$DIR" && mvn clean package -q
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

exec java --module-path "$MODPATH" \
     --add-modules javafx.controls,javafx.web \
     -cp "$JAR" \
     com.example.wwwjs.wwwjs "$@"
