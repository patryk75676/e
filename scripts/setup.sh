#!/usr/bin/env bash
# Pierwsze uruchomienie bota na serwerze (SeoHost VPS lub lokalnie).
#   bash scripts/setup.sh
set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> Sprawdzam Node.js"
if ! command -v node >/dev/null 2>&1; then
  echo "BLAD: brak Node.js. Zainstaluj go najpierw, np.:"
  echo "  curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash - && sudo apt install -y nodejs"
  exit 1
fi

NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
if [ "$NODE_MAJOR" -lt 18 ]; then
  echo "BLAD: discord.js v14 wymaga Node.js 18+. Masz $(node -v)."
  exit 1
fi
echo "    Node.js $(node -v) OK"

echo "==> Instaluje zaleznosci"
npm install --omit=dev

if [ ! -f .env ]; then
  cp .env.example .env
  echo ""
  echo "==> Utworzono plik .env — uzupelnij go teraz:"
  echo "      nano .env"
  echo "    Potrzebujesz: DISCORD_TOKEN, CLIENT_ID, GUILD_ID"
  echo "    Potem uruchom ponownie: bash scripts/setup.sh"
  exit 0
fi

if grep -q "wklej_tutaj" .env; then
  echo ""
  echo "BLAD: plik .env ma jeszcze wartosci zastepcze. Uzupelnij go: nano .env"
  exit 1
fi

echo "==> Rejestruje komendy slash"
npm run deploy

mkdir -p logs

echo ""
echo "==> Gotowe. Uruchom bota:"
echo "    npm start                       # na chwile, w terminalu"
echo "    pm2 start ecosystem.config.js   # na stale, 24/7 (najpierw: npm i -g pm2)"
