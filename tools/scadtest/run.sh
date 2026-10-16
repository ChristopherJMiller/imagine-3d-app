#!/usr/bin/env bash
# Render each helper-library part and print its bounding box. Usage: tools/scadtest/run.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
lib="$here/../../app/src/main/assets/scad"
out="${OUT:-/tmp/scadtest}"; mkdir -p "$out"
parts=(
  "wall_hook();"
  "wall_hook(holes=0);"
  "over_door_hook(door_t=18);"
  "shelf_clip_hook(board_t=19);"
  "rail_hook(rail_d=25);"
  "ring_holder(d=65);"
  "ring_holder(d=30, cup=true, holes=1);"
  "cable_clip(cable_d=5, count=3, hole_d=3);"
  "tray([120,80,30], div=[3,2]);"
  "l_bracket();"
  "mount_plate();"
  "wedge(height=24, tip=3);"
  "door_stop(gap=14);"
  "tube_plug(outer=25, wall=1.5);"
  "tube_plug(outer=22, wall=1.2, round=true);"
  "cup(d=95, h=15, drain=0);"
  "cup(d=60, h=90, sides=6);"
)
fail=0
for i in "${!parts[@]}"; do
  f="$out/part$i.scad"
  printf 'include <imagine.scad>\n%s\n' "${parts[$i]}" > "$f"
  if OPENSCADPATH="$lib" openscad --backend=manifold -o "$out/part$i.stl" --summary=bounding-box --summary-file="$out/part$i.json" "$f" >"$out/part$i.log" 2>&1; then
    bb=$(jq -c '.geometry.bounding_box | [.min, .max] | map(map(.*10|round/10))' "$out/part$i.json" 2>/dev/null || echo "?")
    warn=$(grep -c WARNING "$out/part$i.log" || true)
    echo "OK   ${parts[$i]}  bbox=$bb warnings=$warn"
  else
    echo "FAIL ${parts[$i]}"; tail -5 "$out/part$i.log"; fail=1
  fi
done
exit $fail
