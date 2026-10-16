#!/usr/bin/env bash
# Screenshot the connected device/emulator, scaled down for quick review: tools/emu/shot.sh out.png
out="${1:-/tmp/shot.png}"
adb exec-out screencap -p > "$out.full.png"
python3 -c "
from PIL import Image; import sys
im = Image.open(sys.argv[1]); w, h = im.size; s = 900 / h
im.resize((int(w * s), 900)).save(sys.argv[2])" "$out.full.png" "$out"
rm -f "$out.full.png"
echo "$out"
