#!/usr/bin/env bash
# Downloads the fonts the app bundles (all SIL Open Font License) into Android (res/font) and
# Windows (desktop resources): Urbanist for the interface and text, plus the text-style fonts.
# Run once before building locally; the GitHub build runs it automatically.
set -euo pipefail
cd "$(dirname "$0")/.."
android=composeApp/src/androidMain/res/font
desktop=composeApp/src/desktopMain/resources/font
mkdir -p "$android" "$desktop"
need="urbanist_semibold bebas_neue anton playfair_display_bold pacifico caveat_bold fredoka_semibold"
missing=0
for n in $need; do
  [ -s "$android/$n.ttf" ] && [ -s "$desktop/$n.ttf" ] || missing=1
done
if [ "$missing" = 0 ]; then echo "Fonts ready"; exit 0; fi

base="https://raw.githubusercontent.com/google/fonts/main/ofl"
tmp="$(mktemp -d)"
PY="$(command -v python3 || command -v python)"
"$PY" -m pip install --quiet fonttools

# put <file> into both font folders under <name>.ttf
put() { cp "$1" "$android/$2.ttf"; cp "$1" "$desktop/$2.ttf"; }
fetch() { curl -fsSL -o "$tmp/$2" "$base/$1"; }

# Urbanist (variable): fixed weights for the interface
fetch "urbanist/Urbanist%5Bwght%5D.ttf" Urbanist.ttf
for pair in light:300 regular:400 medium:500 semibold:600 bold:700; do
  name="${pair%%:*}"; w="${pair##*:}"
  "$PY" -m fontTools.varLib.instancer "$tmp/Urbanist.ttf" "wght=$w" -q -o "$tmp/urbanist_$name.ttf"
  put "$tmp/urbanist_$name.ttf" "urbanist_$name"
done

# Text-style fonts. Static ones are copied as they are; variable ones are cut to one weight.
fetch "bebasneue/BebasNeue-Regular.ttf" bebas.ttf;   put "$tmp/bebas.ttf" bebas_neue
fetch "anton/Anton-Regular.ttf" anton.ttf;           put "$tmp/anton.ttf" anton
fetch "pacifico/Pacifico-Regular.ttf" pacifico.ttf;  put "$tmp/pacifico.ttf" pacifico
fetch "playfairdisplay/PlayfairDisplay%5Bwght%5D.ttf" playfair.ttf
"$PY" -m fontTools.varLib.instancer "$tmp/playfair.ttf" "wght=700" -q -o "$tmp/playfair_b.ttf"; put "$tmp/playfair_b.ttf" playfair_display_bold
fetch "caveat/Caveat%5Bwght%5D.ttf" caveat.ttf
"$PY" -m fontTools.varLib.instancer "$tmp/caveat.ttf" "wght=700" -q -o "$tmp/caveat_b.ttf"; put "$tmp/caveat_b.ttf" caveat_bold
fetch "fredoka/Fredoka%5Bwdth,wght%5D.ttf" fredoka.ttf
"$PY" -m fontTools.varLib.instancer "$tmp/fredoka.ttf" "wght=600" "wdth=100" -q -o "$tmp/fredoka_s.ttf"; put "$tmp/fredoka_s.ttf" fredoka_semibold
echo "Fonts ready"
