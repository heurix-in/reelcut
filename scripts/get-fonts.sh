#!/usr/bin/env bash
# Downloads the Urbanist variable font (SIL Open Font License) into the app's font folder.
# Run once before building locally; the GitHub build runs it automatically.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/src/main/res/font
out=app/src/main/res/font/urbanist.ttf
if [ ! -s "$out" ]; then
  curl -fsSL -o "$out" "https://raw.githubusercontent.com/google/fonts/main/ofl/urbanist/Urbanist%5Bwght%5D.ttf"
fi
echo "Font ready: $out"
