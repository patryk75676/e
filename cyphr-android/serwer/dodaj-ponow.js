// Wpina ponow.js do cyphr-api: przy chwilowym 429/5xx od Routeway serwer sam ponawia
// zapytanie (krótki backoff), zamiast od razu zwracać użytkownikowi „poczekaj 5 min”.
//
// Nie zmienia proxy.js — owija globalny fetch (jedna linia na górze pliku startowego, jak
// jezyk.js). Przed dotknięciem serwera robi lokalny self-test (udawany Routeway: 429 -> 200).
//
// Uruchom:  cd ~/cyphr-api && node dodaj-ponow.js
//   --usun   wypina modul
'use strict';
const fs = require('fs'), path = require('path'), http = require('http');

const APP = process.cwd();
const ADRES = (process.env.CYPHR_URL || 'https://billing.cyphr.com.pl').replace(/\/+$/, '');
const CZEKAJ_MS = Number(process.env.CYPHR_RESTART_MS || 9000);
const PONOW_MS = Number(process.env.CYPHR_PONOW_MS || 5000);
const LINIA = "require('./ponow');";
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
const naszaLinia = (l) => /^\s*require\(\s*['"]\.\/ponow(?:\.js)?['"]\s*\)\s*;?/.test(l);

if (USUN) {
  if (!start) { console.error('Nie znalazlem pliku startowego.'); process.exit(1); }
  const linie = fs.readFileSync(start, 'utf8').split('\n');
  const zostaje = linie.filter((l) => !naszaLinia(l));
  if (zostaje.length === linie.length) { console.log('Ponawianie nie jest wpiete — nic do zrobienia.'); process.exit(0); }
  fs.copyFileSync(start, start + '.bak-' + Date.now());
  fs.writeFileSync(start, zostaje.join('\n'));
  przeladuj();
  console.log('Wypiete i przeladowane. Serwer znowu przekazuje 429 od Routeway wprost.');
  process.exit(0);
}

if (typeof fetch !== 'function') {
  console.error('Ten node jest za stary (brak fetch). Uzyj node z aplikacji:');
  console.error('  source ~/nodevenv/cyphr-api/20/bin/activate && node dodaj-ponow.js');
  process.exit(1);
}

async function selfTest() {
  // Udawany Routeway: pierwsze 2 zapytania -> 429, potem 200.
  let calls = 0;
  const srv = http.createServer((req, res) => {
    if (req.url.includes('/chat/completions')) {
      calls++;
      if (calls <= 2) { res.writeHead(429); return res.end('{"error":"rate limited"}'); }
      res.writeHead(200); return res.end('{"ok":1}');
    }
    res.writeHead(200); res.end('x');
  });
  await new Promise((r) => srv.listen(0, r));
  const base = 'http://127.0.0.1:' + srv.address().port;
  process.env.CYPHR_PONOW_BAZA_MS = process.env.CYPHR_PONOW_BAZA_MS || '10';
  process.env.CYPHR_PONOW_MAX_MS = process.env.CYPHR_PONOW_MAX_MS || '30';
  process.env.ROUTEWAY_BASE_URL = base;
  const P = require(path.join(APP, 'ponow.js')); // owija global fetch
  let status = 0;
  try { status = (await fetch(base + '/chat/completions', { method: 'POST', body: '{}' })).status; } catch (e) { status = -1; }
  srv.close();
  delete process.env.ROUTEWAY_BASE_URL; // zeby weryfikacja produkcji nie byla traktowana jak Routeway
  return { wpiete: P.stan().wpiete, status, calls };
}

(async () => {
  console.log('=== 1. Czego potrzebuje modul ===');
  if (!jest(path.join(APP, 'ponow.js'))) { console.error('  Brakuje ponow.js w ~/cyphr-api/. Wgraj go obok tego skryptu.'); process.exit(1); }
  console.log(`  package.json: main=${pj.main || '(brak)'}  type=${pj.type || 'commonjs'}`);
  if (pj.type === 'module') { console.error('\n  Projekt jest w trybie ESM (type: module) — ta wersja instalatora tego nie obsluguje. Przeslij te linie dalej.'); process.exit(1); }
  console.log(`  node               ${process.version}`);
  console.log(`  ROUTEWAY_BASE_URL  ${process.env.ROUTEWAY_BASE_URL ? 'ustawiony' : '(instalator go nie widzi — to normalne, serwer czyta go z .env w trakcie)'}`);
  if (!start) { console.error('  Nie znalazlem pliku startowego. Sprawdzalem: ' + kandydaci.join(', ')); process.exit(1); }
  console.log('  plik startowy: ' + path.relative(APP, start));

  console.log('\n=== 2. Test lokalny (udawany Routeway: 429,429,200) ===');
  let t;
  try { t = await selfTest(); } catch (e) { console.error('  Test sie wywrocil: ' + (e && e.message)); process.exit(1); }
  console.log(`  fetch owiniety:                 ${t.wpiete ? 'TAK' : 'NIE'}`);
  console.log(`  po dwoch 429 klient dostal:     HTTP ${t.status}`);
  console.log(`  zapytan do upstream:            ${t.calls} (spodziewane 3: 2 ponowienia)`);
  if (!(t.wpiete && t.status === 200 && t.calls === 3)) {
    console.error('\n  Ponawianie nie zadzialalo w tescie — NIC nie zmienilem. Przeslij mi ten wynik.');
    process.exit(1);
  }
  console.log('  Wynik: przelotne 429 od Routeway sa ponawiane i konczą sie sukcesem.');

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
    linie.splice(gdzie, 0, LINIA + '   // ponawianie 429 od Routeway');
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
      console.error('\n  Serwer nie odpowiada jak trzeba — cofnalem zmiane i przeladowalem.');
      process.exit(1);
    }
    console.log('\nGotowe. Przy chwilowym 429 od Routeway serwer sam ponawia (do 3 razy, krotki backoff),');
    console.log('wiec „poczekaj 5 min” przy agencie powinno zniknac albo mocno zmalec.');
    console.log('Jesli Routeway ma twardy, dluzszy limit na kluczu — podnies go w panelu Routeway.');
    if (kopia) console.log('Wypiecie w razie potrzeby: node dodaj-ponow.js --usun');
  } catch (e) {
    cofnij();
    console.error('BLAD:', e && e.message, '— cofnalem zmiane w pliku startowym.');
    process.exit(1);
  }
})().catch((e) => { console.error('BLAD:', e && e.message); process.exit(1); });
