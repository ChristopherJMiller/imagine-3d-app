#!/usr/bin/env bash
# Run the eval for every model given (default: all GGUFs in models/), sequentially.
cd "$(dirname "$0")/../.."
models=("$@"); [ ${#models[@]} -eq 0 ] && models=(models/*.gguf)
for m in "${models[@]}"; do
  case "$m" in *mmproj*) continue;; esac
  echo "=== $m"
  python3 tools/eval/run_eval.py --model "$m"
done
