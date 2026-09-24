// Wpina wybor jezyka po IP do cyphr-api: GET /v1/geo mowi aplikacji, czy pytanie przyszlo
// z polskiego adresu (jezyk polski), czy z innego (angielski).
//
// Najpierw sprawdza dane o polskich adresach (jezyk-pl.json; gdy go brak albo jest stary,
// pobiera swiezy z RIPE NCC), potem dopisuje jedna linie na poczatku pliku startowego,
// przeladowuje i sprawdza od zewnatrz. Gdy cokolwiek sie nie zgadza, przywraca plik startowy
// i przeladowuje jeszcze raz.
//
// Uruchom:  cd ~/cyphr-api && node dodaj-jezyk.js
//   --usun   wypina modul (usuwa linie z pliku startowego)
'use strict';
const fs = require('fs'), path = require('path'), cp = require('child_process');

const APP = process.cwd();
const ADRES = (process.env.CYPHR_URL || 'https://billing.cyphr.com.pl').replace(/\/+$/, '');
const CZEKAJ_MS = Number(process.env.CYPHR_RESTART_MS || 9000);
const PONOW_MS = Number(process.env.CYPHR_PONOW_MS || 5000);
const LINIA = "require('./jezyk');";
const USUN = process.argv.includes('--usun');

const jest = (p) => { try { fs.accessSync(p); return true; } catch { return false; } };
const spij = (ms) => new Promise((r) => setTimeout(r, ms));

if (typeof fetch !== 'function') {
  console.error('Ten node jest za stary (brak fetch). Uzyj node z aplikacji:');
  console.error('  source ~/nodevenv/cyphr-api/20/bin/activate && node dodaj-jezyk.js');
  process.exit(1);
}

let pj = {};
try { pj = JSON.parse(fs.readFileSync(path.join(APP, 'package.json'), 'utf8')); } catch {}
const kandydaci = [pj.main, ((pj.scripts || {}).start || '').match(/[\w./-]+\.[cm]?js/)?.[0],
  'app.js', 'server.js', 'index.js', 'src/app.js', 'src/server.js', 'src/index.js'].filter(Boolean);
const start = kandydaci.map((k) => path.join(APP, k)).find(jest);

const przeladuj = () => {
  fs.mkdirSync(path.join(APP, 'tmp'), { recursive: true });
  fs.writeFileSync(path.join(APP, 'tmp', 'restart.txt'), String(Date.now()));
};

// Linia moze stac przy innych rzeczach, np. "require('./jezyk');   // jezyk po IP".
const naszaLinia = (l) => /^\s*require\(\s*['"]\.\/jezyk(?:\.js)?['"]\s*\)\s*;?/.test(l);

if (USUN) {
  if (!start) { console.error('Nie znalazlem pliku startowego.'); process.exit(1); }
  const linie = fs.readFileSync(start, 'utf8').split('\n');
  const zostaje = linie.filter((l) => !naszaLinia(l));
  if (zostaje.length === linie.length) { console.log('Jezyk po IP nie jest wpiety — nic do zrobienia.'); process.exit(0); }
  fs.copyFileSync(start, start + '.bak-' + Date.now());
  fs.writeFileSync(start, zostaje.join('\n'));
  przeladuj();
  console.log('Wypiete i przeladowane. Aplikacja wybierze jezyk po ustawieniach telefonu.');
  process.exit(0);
}

console.log('=== 1. Czego potrzebuje modul ===');
if (!jest(path.join(APP, 'jezyk.js'))) {
  console.error('  Brakuje pliku jezyk.js w ~/cyphr-api/. Wgraj go obok tego skryptu.');
  process.exit(1);
}
console.log(`  package.json: main=${pj.main || '(brak)'}  type=${pj.type || 'commonjs'}  start=${(pj.scripts || {}).start || '(brak)'}`);
if (pj.type === 'module') {
  console.error('\n  Projekt jest w trybie ESM (type: module) — ta wersja instalatora tego nie obsluguje.');
  console.error('  Przeslij te linie dalej, przygotuje wariant pod ESM.');
  process.exit(1);
}
const ex = jest(path.join(APP, 'node_modules', 'express'));
console.log(`  express      ${ex ? 'jest' : 'BRAK'}`);
if (!ex) { console.error('\n  Bez express nie ruszam. Przerywam.'); process.exit(1); }
console.log(`  node         ${process.version}`);
if (!start) { console.error('  Nie znalazlem pliku startowego. Sprawdzalem: ' + kandydaci.join(', ')); process.exit(1); }
console.log('  plik startowy: ' + path.relative(APP, start));

const jezyk = require(path.join(APP, 'jezyk.js'));

(async () => {
  console.log('\n=== 2. Polskie adresy IP (RIPE NCC) ===');
  let s = jezyk.stan();
  if (!s.plik || s.stare || !s.v4) {
    console.log(`  ${s.plik ? 'Dane sa starsze niz 30 dni' : 'Brak jezyk-pl.json'} — pobieram swieze z RIPE NCC...`);
    try {
      await jezyk.odswiez();
      console.log('  pobrane');
    } catch (e) {
      console.log('  RIPE nie odpowiedzial: ' + (e && e.message));
      if (!jezyk.stan().v4) {
        console.error('  Bez danych o polskich adresach nie ruszam. Wgraj jezyk-pl.json obok jezyk.js');
        console.error('  (jest w paczce z modulem) i uruchom ponownie. Nic nie zmienilem.');
        process.exit(1);
      }
      console.log('  Zostaja dotychczasowe dane — modul sprobuje odswiezyc je sam pozniej.');
    }
    s = jezyk.stan();
  }
  console.log(`  dane z dnia     ${s.data}  (pobrane ${String(s.pobrane).slice(0, 10)})`);
  console.log(`  zakresy IPv4    ${s.v4}  (${(s.adresyV4 / 1e6).toFixed(1)} mln adresow)`);
  console.log(`  zakresy IPv6    ${s.v6}`);
  const proby = [
    ['212.77.98.9', true, 'wp.pl'], ['213.180.141.140', true, 'onet.pl'], ['83.24.0.1', true, 'Orange Polska'],
    ['37.47.0.1', true, 'Play'], ['2a02:a31a::1', true, 'IPv6 PL'],
    ['8.8.8.8', false, 'Google, USA'], ['81.2.69.142', false, 'Wielka Brytania'], ['2001:4860:4860::8888', false, 'IPv6 USA'],
  ];
  let zle = 0;
  for (const [ip, pl, opis] of proby) {
    const w = jezyk.polski(ip);
    if (w !== pl) zle += 1;
    console.log(`  ${ip.padEnd(22)} ${w ? 'polski   ' : 'angielski'} ${w === pl ? 'OK' : 'ZLE'}  (${opis})`);
  }
  if (zle) { console.error('\n  Dane nie rozpoznaja znanych adresow. Nic nie zmienilem. Przeslij ten wynik dalej.'); process.exit(1); }

  console.log('\n=== 3. Plik startowy ===');
  let tresc = fs.readFileSync(start, 'utf8');
  let kopia = null;
  if (tresc.split('\n').some(naszaLinia)) {
    console.log('  Linia juz tam jest — nic nie dopisuje.');
  } else {
    kopia = start + '.bak-' + Date.now();
    fs.copyFileSync(start, kopia);
    console.log('  kopia: ' + path.basename(kopia));
    // Na sama gore, za ewentualnym #! i 'use strict' — przed utworzeniem aplikacji.
    const linie = tresc.split('\n');
    let gdzie = 0;
    if (/^#!/.test(linie[0] || '')) gdzie = 1;
    if (/^\s*['"]use strict['"]\s*;?\s*$/.test(linie[gdzie] || '')) gdzie += 1;
    linie.splice(gdzie, 0, LINIA + '   // jezyk aplikacji po IP');
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

    console.log('\n=== 4. Czy nowy adres odpowiada ===');
    // Stary proces potrafi jeszcze chwile obslugiwac zapytania, wiec 404 tez przeczekujemy.
    let g = await zapytaj('GET', '/v1/geo');
    for (let i = 0; i < 8 && [0, 404, 502].includes(g.kod); i++) {
      await spij(PONOW_MS);
      g = await zapytaj('GET', '/v1/geo');
    }
    console.log(`  /v1/geo  HTTP ${g.kod}  ${g.tresc.slice(0, 120)}`);
    let j = {};
    try { j = JSON.parse(g.tresc); } catch {}
    const dziala = (g.kod === 200 && (j.lang === 'pl' || j.lang === 'en')) || (g.kod === 503 && j.error === 'geo_unavailable');
    if (!dziala) {
      cofnij();
      console.error(kopia
        ? '\n  Nowy adres nie dziala jak trzeba — cofnalem zmiane w pliku startowym i przeladowalem.'
        : '\n  Nowy adres nie dziala jak trzeba (linia byla juz wczesniej, niczego nie cofalem).');
      pokazBledy();
      process.exit(1);
    }
    if (g.kod === 503) {
      console.log('  Serwer nie widzi adresu, z ktorego sam do siebie pyta (to czesto sie zdarza) —');
      console.log('  sprawdz z telefonu: otworz w przegladarce ' + ADRES + '/v1/geo');
    } else {
      console.log(`  Serwer widzi pytanie z ${j.ip} -> ${j.lang === 'pl' ? 'polski' : 'angielski'}.`);
    }

    console.log('\n=== 5. Czy reszta serwera dziala jak dotad ===');
    const c = await zapytaj('POST', '/auth/register', { email: 'x', password: 'x', name: 'x' });
    console.log(`  /auth/register  HTTP ${c.kod}  ${c.tresc.slice(0, 100)}`);
    const m = await zapytaj('GET', '/v1/models');
    console.log(`  /v1/models      HTTP ${m.kod}`);
    if (c.kod === 0 || c.kod === 404 || c.kod >= 500 || m.kod === 0 || m.kod === 404 || m.kod >= 500) {
      cofnij();
      console.error('\n  Stare adresy przestaly odpowiadac — cofnalem zmiane i przeladowalem.');
      process.exit(1);
    }

    console.log('\nGotowe. Aplikacja CYPHR 1.8 wybierze jezyk po IP: polski dla polskich adresow, angielski dla reszty.');
    console.log('Dane o adresach odswiezaja sie same co 30 dni.');
    if (kopia) console.log('Wypiecie w razie potrzeby: node dodaj-jezyk.js --usun');
  } catch (e) {
    cofnij();
    console.error('BLAD:', e && e.message, '— cofnalem zmiane w pliku startowym.');
    process.exit(1);
  }
})().catch((e) => {
  console.error('BLAD:', e && e.message);
  process.exit(1);
});

function pokazBledy() {
  console.error('  Ostatnie bledy:');
  for (const k of [APP, path.join(APP, 'logs')]) {
    try {
      for (const f of fs.readdirSync(k).filter((f) => /stderr|error/i.test(f)).slice(0, 2)) {
        console.error(`  --- ${path.join(k, f)} ---`);
        console.error(cp.execSync(`tail -n 20 ${JSON.stringify(path.join(k, f))}`).toString());
      }
    } catch {}
  }
}
