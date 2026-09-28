// Wpina limit.js do cyphr-api: luźniejszy limit zapytań dla czatu i obrazów, ciasny dla
// logowania/resetu. Dzięki temu agent w terminalu (kilka zapytań na jedną wiadomość) nie
// wywołuje bez przerwy 429 „odczekaj 5 min”, a łamanie hasła dalej jest ograniczone.
//
// Nie zmienia app.js — wpina jedną linię na górze pliku startowego (jak jezyk.js/obrazy.js).
// Przed dotknięciem serwera robi lokalny test na Twoim express + express-rate-limit, żeby
// upewnić się, że rozpozna limiter. Gdy coś się nie zgadza — nic nie zmienia albo cofa.
//
// Uruchom:  cd ~/cyphr-api && node dodaj-limit.js
//   --usun   wypina modul (usuwa linie z pliku startowego)
'use strict';
const fs = require('fs'), path = require('path'), http = require('http');

const APP = process.cwd();
const ADRES = (process.env.CYPHR_URL || 'https://billing.cyphr.com.pl').replace(/\/+$/, '');
const CZEKAJ_MS = Number(process.env.CYPHR_RESTART_MS || 9000);
const PONOW_MS = Number(process.env.CYPHR_PONOW_MS || 5000);
const LINIA = "require('./limit');";
const USUN = process.argv.includes('--usun');

const jest = (p) => { try { fs.accessSync(p); return true; } catch { return false; } };
const spij = (ms) => new Promise((r) => setTimeout(r, ms));

let pj = {};
try { pj = JSON.parse(fs.readFileSync(path.join(APP, 'package.json'), 'utf8')); } catch {}
const kandydaci = [pj.main, ((pj.scripts || {}).start || '').match(/[\w./-]+\.[cm]?js/)?.[0],
  'app.js', 'server.js', 'index.js', 'src/app.js', 'src/server.js', 'src/index.js'].filter(Boolean);
const start = kandydaci.map((k) => path.join(APP, k)).find(jest);

const przeladuj = () => {
  fs.mkdirSync(path.join(APP, 'tmp'), { recursive: true });
  fs.writeFileSync(path.join(APP, 'tmp', 'restart.txt'), String(Date.now()));
};
const naszaLinia = (l) => /^\s*require\(\s*['"]\.\/limit(?:\.js)?['"]\s*\)\s*;?/.test(l);

if (USUN) {
  if (!start) { console.error('Nie znalazlem pliku startowego.'); process.exit(1); }
  const linie = fs.readFileSync(start, 'utf8').split('\n');
  const zostaje = linie.filter((l) => !naszaLinia(l));
  if (zostaje.length === linie.length) { console.log('Limit nie jest wpiety — nic do zrobienia.'); process.exit(0); }
  fs.copyFileSync(start, start + '.bak-' + Date.now());
  fs.writeFileSync(start, zostaje.join('\n'));
  przeladuj();
  console.log('Wypiete i przeladowane. Wraca pierwotny limit z app.js.');
  process.exit(0);
}

if (typeof fetch !== 'function') {
  console.error('Ten node jest za stary (brak fetch). Uzyj node z aplikacji:');
  console.error('  source ~/nodevenv/cyphr-api/20/bin/activate && node dodaj-limit.js');
  process.exit(1);
}

// Lokalny test: prawdziwy express + express-rate-limit z serwera, udawany app.js z ciasnym
// limiterem. Potwierdza, ze limit.js rozpoznaje limiter i luzuje czat, a logowanie zostawia.
function req(port, metoda, sciezka) {
  return new Promise((resolve) => {
    const r = http.request({ host: '127.0.0.1', port, method: metoda, path: sciezka }, (res) => {
      res.on('data', () => {}); res.on('end', () => resolve(res.statusCode));
    });
    r.on('error', () => resolve(0));
    r.end();
  });
}
async function fire(port, metoda, sciezka, n) {
  const out = []; for (let i = 0; i < n; i++) out.push(await req(port, metoda, sciezka)); return out;
}
async function selfTest() {
  require(path.join(APP, 'limit.js')); // wpina sie w fabryke express
  const express = require(path.join(APP, 'node_modules', 'express'));
  const rl = require(path.join(APP, 'node_modules', 'express-rate-limit'));
  const fabryka = typeof rl === 'function' ? rl : rl.rateLimit;
  const app = express();
  app.set('trust proxy', false);
  app.use(fabryka({ windowMs: 60000, max: 2, validate: false }));
  app.post('/v1/chat/completions', (q, s) => s.json({ ok: 1 }));
  app.get('/me', (q, s) => s.json({ ok: 1 }));
  app.post('/auth/login', (q, s) => s.json({ ok: 1 }));
  const srv = app.listen(0);
  await new Promise((r) => srv.on('listening', r));
  const port = srv.address().port;
  const chat = await fire(port, 'POST', '/v1/chat/completions', 5);
  const me = await fire(port, 'GET', '/me', 5);
  const auth = await fire(port, 'POST', '/auth/login', 4);
  srv.close();
  const modul = require(path.join(APP, 'limit.js')).stan();
  return {
    czatLuzny: chat.every((c) => c === 200),
    meLuzny: me.every((c) => c === 200),
    logowanieCiasne: auth.filter((c) => c === 200).length <= 2 && auth.includes(429),
    modul,
  };
}

(async () => {
  console.log('=== 1. Czego potrzebuje modul ===');
  if (!jest(path.join(APP, 'limit.js'))) { console.error('  Brakuje limit.js w ~/cyphr-api/. Wgraj go obok tego skryptu.'); process.exit(1); }
  console.log(`  package.json: main=${pj.main || '(brak)'}  type=${pj.type || 'commonjs'}`);
  if (pj.type === 'module') { console.error('\n  Projekt jest w trybie ESM (type: module) — ta wersja instalatora tego nie obsluguje. Przeslij te linie dalej.'); process.exit(1); }
  const ex = jest(path.join(APP, 'node_modules', 'express'));
  const erl = jest(path.join(APP, 'node_modules', 'express-rate-limit'));
  console.log(`  express            ${ex ? 'jest' : 'BRAK'}`);
  console.log(`  express-rate-limit ${erl ? 'jest' : 'BRAK'}`);
  console.log(`  node               ${process.version}`);
  if (!ex) { console.error('\n  Bez express nie ruszam.'); process.exit(1); }
  if (!erl) { console.error('\n  Nie widze express-rate-limit — czyli limit w app.js jest zrobiony inaczej.'); console.error('  Przeslij mi fragment app.js z limiterem, zrobie wariant pod niego. Nic nie zmienilem.'); process.exit(1); }
  if (!start) { console.error('  Nie znalazlem pliku startowego. Sprawdzalem: ' + kandydaci.join(', ')); process.exit(1); }
  console.log('  plik startowy: ' + path.relative(APP, start));

  console.log('\n=== 2. Test lokalny na Twoich wersjach (bez ruszania serwera) ===');
  let t;
  try { t = await selfTest(); } catch (e) { console.error('  Test sie wywrocil: ' + (e && e.message)); process.exit(1); }
  console.log(`  express-rate-limit rozpoznany:  ${t.modul.owinietych >= 1 ? 'TAK' : 'NIE'}`);
  console.log(`  czat (6x, bez 429):             ${t.czatLuzny ? 'OK' : 'ZLE'}`);
  console.log(`  /me (6x, bez 429):              ${t.meLuzny ? 'OK' : 'ZLE'}`);
  console.log(`  logowanie dalej ciasne (429):   ${t.logowanieCiasne ? 'OK' : 'ZLE'}`);
  if (!(t.modul.owinietych >= 1 && t.czatLuzny && t.meLuzny && t.logowanieCiasne)) {
    console.error('\n  Modul nie zadzialal jak trzeba na Twoich wersjach — NIC nie zmienilem.');
    console.error('  Przeslij mi ten wynik i fragment app.js z limiterem (rateLimit/windowMs/max).');
    process.exit(1);
  }
  console.log('  Wynik: limit.js poprawnie luzuje czat i zostawia ciasne logowanie.');

  console.log('\n=== 3. Plik startowy ===');
  let tresc = fs.readFileSync(start, 'utf8');
  let kopia = null;
  if (tresc.split('\n').some(naszaLinia)) {
    console.log('  Linia juz tam jest — nic nie dopisuje.');
  } else {
    kopia = start + '.bak-' + Date.now();
    fs.copyFileSync(start, kopia);
    console.log('  kopia: ' + path.basename(kopia));
    const linie = tresc.split('\n');
    let gdzie = 0;
    if (/^#!/.test(linie[0] || '')) gdzie = 1;
    if (/^\s*['"]use strict['"]\s*;?\s*$/.test(linie[gdzie] || '')) gdzie += 1;
    linie.splice(gdzie, 0, LINIA + '   // luzniejszy limit dla czatu');
    tresc = linie.join('\n');
    fs.writeFileSync(start, tresc);
    console.log(`  dopisane w linii ${gdzie + 1}: ${LINIA}`);
  }
  const cofnij = () => { if (kopia) { fs.copyFileSync(kopia, start); przeladuj(); } };

  try {
    przeladuj();
    console.log('\n  Przeladowane. Czekam, az aplikacja wstanie...');
    await spij(CZEKAJ_MS);

    const zapytaj = async (metoda, sciezka, dane) => {
      try {
        const r = await fetch(ADRES + sciezka, {
          method: metoda,
          headers: dane === undefined ? {} : { 'Content-Type': 'application/json' },
          body: dane === undefined ? undefined : JSON.stringify(dane),
          signal: AbortSignal.timeout(30000),
        });
        return { kod: r.status, tresc: await r.text() };
      } catch (e) { return { kod: 0, tresc: e.message }; }
    };

    console.log('\n=== 4. Czy serwer dziala jak dotad ===');
    let m = await zapytaj('GET', '/v1/models');
    for (let i = 0; i < 8 && [0, 502].includes(m.kod); i++) { await spij(PONOW_MS); m = await zapytaj('GET', '/v1/models'); }
    console.log(`  /v1/models      HTTP ${m.kod}`);
    const c = await zapytaj('POST', '/auth/register', { email: 'x', password: 'x', name: 'x' });
    console.log(`  /auth/register  HTTP ${c.kod}  ${String(c.tresc).slice(0, 80)}`);
    if (m.kod === 0 || m.kod === 404 || m.kod >= 500 || c.kod === 0 || c.kod >= 500) {
      cofnij();
      console.error('\n  Serwer nie odpowiada jak trzeba — cofnalem zmiane w pliku startowym i przeladowalem.');
      process.exit(1);
    }

    console.log('\nGotowe. Czat, obrazy i /me maja teraz luzniejszy limit (do ' + require(path.join(APP, 'limit.js')).LIMIT + '/min z IP),');
    console.log('a logowanie, rejestracja i reset hasla zostaja ciasne. 429 „odczekaj 5 min” przy agencie powinno zniknac.');
    if (kopia) console.log('Wypiecie w razie potrzeby: node dodaj-limit.js --usun');
  } catch (e) {
    cofnij();
    console.error('BLAD:', e && e.message, '— cofnalem zmiane w pliku startowym.');
    process.exit(1);
  }
})().catch((e) => { console.error('BLAD:', e && e.message); process.exit(1); });
