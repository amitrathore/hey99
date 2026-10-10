#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p resources/ui
cp index.html styles.css game.js game.css resources/ui/
for dir in assets join market economics; do
  mkdir -p "resources/ui/$dir"
  cp -R "$dir/." "resources/ui/$dir/"
done
