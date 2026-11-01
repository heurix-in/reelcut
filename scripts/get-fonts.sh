#!/usr/bin/env bash
# Downloads the Urbanist variable font (SIL Open Font License) and cuts fixed weights for
# Android (res/font) and Windows (desktop resources). Run once before building locally;
# the GitHub build runs it automatically.
set -euo pipefail
cd "$(dirname "$0")/.."
android=composeApp/src/androidMain/res/font
desktop=composeApp/src/desktopMain/resources/font
mkdir -p "$android" "$desktop"
if [ -s "$android/urbanist_semibold.ttf" ] && [ -s "$desktop/urbanist_semibold.ttf" ]; then
  echo "Fonts ready"; exit 0
fi
tmp="$(mktemp -d)"
curl -fsSL -o "$tmp/Urbanist.ttf" "https://raw.githubusercontent.com/google/fonts/main/ofl/urbanist/Urbanist%5Bwght%5D.ttf"
PY="$(command -v python3 || command -v python)"
"$PY" -m pip install --quiet fonttools
for pair in light:300 regular:400 medium:500 semibold:600 bold:700; do
  name="${pair%%:*}"; w="${pair##*:}"
  "$PY" -m fontTools.varLib.instancer "$tmp/Urbanist.ttf" "wght=$w" -q -o "$android/urbanist_$name.ttf"
  cp "$android/urbanist_$name.ttf" "$desktop/urbanist_$name.ttf"
done
echo "Fonts ready"
