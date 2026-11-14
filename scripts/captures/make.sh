#!/bin/bash
# Captures de la documentation (docs/captures) : foyer fictif, theme sombre, cadre de navigateur.
# Prerequis : target/patria.jar construit (mvn -q package -DskipTests), chromium, node 22 ou plus.
# Usage : scripts/captures/make.sh   (depuis la racine du depot)
set -euo pipefail
SCRIPTS=$(cd "$(dirname "$0")" && pwd)
PORT=${PORT:-8096}
export WORK=$(mktemp -d) BASE=http://localhost:$PORT
trap 'kill "$SERVER" 2>/dev/null || true; rm -rf "${WORK:?}"' EXIT

HTTP_PORT=$PORT APP_PASSWORD=test DATABASE_PATH=$WORK/db.sqlite3 java -jar target/patria.jar >"$WORK/server.log" 2>&1 &
SERVER=$!
for _ in $(seq 1 60); do curl -s -o /dev/null "$BASE/" && break; sleep 0.5; done
bash "$SCRIPTS/seed.sh"

SESSION=$(awk '$6 == "patria_session" { print $7 }' "$WORK/claire.cookies" | tail -1)
# Bandeaux du guide et de la revue annuelle retires : ils encombrent sans rien montrer.
CLEAN="document.documentElement.style.scrollbarGutter='auto';document.querySelectorAll('.guide-strip').forEach(e=>e.remove());\
[...document.querySelectorAll('main .notice, main section')].filter(e=>/Revue annuelle conseillée/.test(e.textContent)&&e.textContent.length<400).forEach(e=>e.remove());"
mkdir -p "$WORK/shots"
PAGES=("previsionnel:/" "patrimoine:/patrimoine" "bilan:/bilan" "economies:/economies" "bourse:/bourse" "immobilier:/immobilier" "guide:/assistant")
ARGS=()
for page in "${PAGES[@]}"; do ARGS+=("$BASE${page#*:}|${page%%:*}|$CLEAN"); done
SCRATCH=$WORK node "$SCRIPTS/shoot.mjs" "$SESSION" "$WORK/shots" 1280 800 "${ARGS[@]}"

LOCK='<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/></svg>'
for page in "${PAGES[@]}"; do
    name=${page%%:*}; path=${page#*:}
    sed -e "s|{{IMAGE}}|$WORK/shots/$name.png|" -e "s|{{URL}}|patria.maison$path|" -e "s|{{LOCK}}|$LOCK|" "$SCRIPTS/cadre.html" >"$WORK/$name.html"
    chromium --headless --no-sandbox --hide-scrollbars --force-device-scale-factor=1 --window-size=1440,980 \
        --screenshot="docs/captures/$name.png" "file://$WORK/$name.html" 2>/dev/null
    echo "docs/captures/$name.png"
done
