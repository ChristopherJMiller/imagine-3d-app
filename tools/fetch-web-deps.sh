#!/usr/bin/env bash
# Fetch the third-party web assets bundled into the APK (OpenSCAD WebAssembly + three.js).
# They are committed to the repo because OpenSCAD snapshot builds get deleted upstream;
# this script documents provenance and re-creates them when bumping versions.
set -euo pipefail
cd "$(dirname "$0")/.."
OPENSCAD_ZIP="OpenSCAD-2026.10.15-WebAssembly-web.zip"
OPENSCAD_SHA256="01fa89a1d67ac484508ecc8168e6fe16b9fbeeea1089caf753de4f0e5448d435"
THREE_VERSION="0.186.0"

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
dest=app/src/main/assets/web

curl -sSfL -o "$tmp/$OPENSCAD_ZIP" "https://files.openscad.org/snapshots/$OPENSCAD_ZIP"
echo "$OPENSCAD_SHA256  $tmp/$OPENSCAD_ZIP" | sha256sum -c -
unzip -o -q "$tmp/$OPENSCAD_ZIP" -d "$tmp/openscad"
mkdir -p "$dest/openscad"
cp "$tmp/openscad/openscad.js" "$tmp/openscad/openscad.wasm" "$dest/openscad/"

curl -sSfL -o "$tmp/three.tgz" "https://registry.npmjs.org/three/-/three-$THREE_VERSION.tgz"
tar -xzf "$tmp/three.tgz" -C "$tmp"
mkdir -p "$dest/three"
cp "$tmp/package/build/three.module.js" "$tmp/package/build/three.core.js" "$dest/three/"
cp "$tmp/package/examples/jsm/controls/OrbitControls.js" "$tmp/package/examples/jsm/loaders/STLLoader.js" "$dest/three/"
cp "$tmp/package/LICENSE" "$dest/three/LICENSE"
echo "web deps ready in $dest"
