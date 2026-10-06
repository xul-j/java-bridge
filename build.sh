#!/bin/sh
# Builds the Swing bridge and the demo inside the xulj-jdk image (JDK 17, Java 8 bytecode).
# The browser client is embedded from the xul-j base repo: XULJ_BASE (default ../xul-j).
set -e
cd "$(dirname "$0")"
BASE="$(cd "${XULJ_BASE:-../xul-j}" && pwd -P)"
[ -f "$BASE/public/xulj.js" ] || { echo "xul-j base not found at $BASE (set XULJ_BASE)"; exit 1; }
mkdir -p out
docker run --rm -v "$PWD":/src -v "$BASE/public":/client:ro -w /src xulj-jdk sh -c '
  set -e
  rm -rf out/classes out/demo-classes && mkdir -p out/classes/public out/demo-classes
  javac --release 8 -Xlint:-options -d out/classes $(find src -name "*.java")
  cp /client/index.html /client/xulj.js /client/xul.css out/classes/public/
  printf "Main-Class: org.xulj.bridge.Launcher\n" > out/manifest.txt
  jar cfm out/xulj-swing.jar out/manifest.txt -C out/classes .
  javac --release 8 -Xlint:-options -d out/demo-classes $(find demo/src -name "*.java")
  printf "Main-Class: legacy.InventoryFrame\n" > out/demo-manifest.txt
  jar cfm out/LegacyInventory.jar out/demo-manifest.txt -C out/demo-classes .
'
ls -la out/*.jar
