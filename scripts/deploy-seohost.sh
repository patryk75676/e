#!/usr/bin/env bash
# Aktualizacja bota na serwerze SeoHost po wypchnieciu zmian do gita.
# Uruchamiaj NA SERWERZE, w katalogu bota:
#   bash scripts/deploy-seohost.sh
set -euo pipefail

cd "$(dirname "$0")/.."

echo "==> Pobieram najnowszy kod"
git pull

echo "==> Aktualizuje zaleznosci"
npm install --omit=dev

echo "==> Odswiezam komendy slash"
npm run deploy

echo "==> Restartuje proces"
if command -v pm2 >/dev/null 2>&1 && pm2 describe discord-bot >/dev/null 2>&1; then
  pm2 restart discord-bot --update-env
  pm2 save
else
  echo "    pm2 nie prowadzi jeszcze procesu 'discord-bot' — startuje go"
  pm2 start ecosystem.config.js
  pm2 save
fi

echo "==> Gotowe. Logi: pm2 logs discord-bot"
