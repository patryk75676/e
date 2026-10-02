// Wpina obrazy do cyphr-api: tworzenie obrazow z dziennym limitem na konto
// i wieksze zapytania czatu (zdjecia i zrzuty ekranu).
//
// Najpierw sprawdza klucz, model u dostawcy i baze, tworzy jeden probny obraz
// (ok. 0,01 USD), potem dopisuje jedna linie na poczatku pliku startowego,
// przeladowuje i sprawdza od zewnatrz. Gdy cokolwiek sie nie zgadza, przywraca
// plik startowy i przeladowuje jeszcze raz.
//
// Uruchom:  cd ~/cyphr-api && node dodaj-obrazy.js
//   --bez-testu   bez probnego obrazu (nic nie kosztuje)
//   --usun        wypina obrazy (usuwa linie z pliku startowego)
'use strict';
const fs = require('fs'), path = require('path'), cp = require('child_process');

const APP = process.cwd();
const ADRES = (process.env.CYPHR_URL || 'https://billing.cyphr.com.pl').replace(/\/+$/, '');
const CZEKAJ_MS = Number(process.env.CYPHR_RESTART_MS || 9000);
const PONOW_MS = Number(process.env.CYPHR_PONOW_MS || 5000);
const LINIA = "require('./obrazy');";
const BEZ_TESTU = process.argv.includes('--bez-testu');
const USUN = process.argv.includes('--usun');

const jest = (p) => { try { fs.accessSync(p); return true; } catch { return false; } };
const spij = (ms) => new Promise((r) => setTimeout(r, ms));

if (typeof fetch !== 'function') {
  console.error('Ten node jest za stary (brak fetch). Uzyj node z aplikacji:');
  console.error('  source ~/nodevenv/cyphr-api/20/bin/activate && node dodaj-obrazy.js');
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

// Linia moze stac przy innych rzeczach, np. "require('./obrazy');   // obrazy".
const naszaLinia = (l) => /^\s*require\(\s*['"]\.\/obrazy(?:\.js)?['"]\s*\)\s*;?/.test(l);

if (USUN) {
  if (!start) { console.error('Nie znalazlem pliku startowego.'); process.exit(1); }
  const linie = fs.readFileSync(start, 'utf8').split('\n');
  const zostaje = linie.filter((l) => !naszaLinia(l));
  if (zostaje.length === linie.length) { console.log('Obrazy nie sa wpiete — nic do zrobienia.'); process.exit(0); }
  fs.copyFileSync(start, start + '.bak-' + Date.now());
  fs.writeFileSync(start, zostaje.join('\n'));
  przeladuj();
  console.log('Wypiete i przeladowane. Aplikacja po chwili schowa obrazy (serwer odpowie 404).');
  process.exit(0);
}

console.log('=== 1. Czego potrzebuje modul ===');
if (!jest(path.join(APP, 'obrazy.js'))) {
  console.error('  Brakuje pliku obrazy.js w ~/cyphr-api/. Wgraj go obok tego skryptu.');
  process.exit(1);
}
console.log(`  package.json: main=${pj.main || '(brak)'}  type=${pj.type || 'commonjs'}  start=${(pj.scripts || {}).start || '(brak)'}`);
if (pj.type === 'module') {
  console.error('\n  Projekt jest w trybie ESM (type: module) — ta wersja instalatora tego nie obsluguje.');
  console.error('  Przeslij te linie dalej, przygotuje wariant pod ESM.');
  process.exit(1);
}
for (const n of ['express', 'mysql2']) {
  const ok = jest(path.join(APP, 'node_modules', n));
  console.log(`  ${n.padEnd(12)} ${ok ? 'jest' : 'BRAK'}`);
  if (!ok) { console.error(`\n  Bez ${n} nie ruszam. Przerywam.`); process.exit(1); }
}
console.log(`  node         ${process.version}`);
if (!start) { console.error('  Nie znalazlem pliku startowego. Sprawdzalem: ' + kandydaci.join(', ')); process.exit(1); }
console.log('  plik startowy: ' + path.relative(APP, start));

const obrazy = require(path.join(APP, 'obrazy.js'));

(async () => {
  console.log('\n=== 2. Ustawienia (.env) ===');
  const s = obrazy.stan();
  console.log(`  klucz dostawcy  ${s.klucz ? s.klucz + ' (wartosci nie pokazuje)' : 'BRAK'}`);
  console.log(`  dostawca        ${s.api}`);
  console.log(`  model obrazow   ${s.model}`);
  console.log(`  limit dzienny   ${s.limit} na konto${s.limit === 0 ? '  (IMAGE_DAILY_LIMIT=0 — obrazy beda wylaczone)' : ''}`);
  console.log(`  doba liczona    ${s.strefa}, dzis ${s.dzis}`);
  console.log(`  JSON czatu do   ${s.limitJson}`);
  if (!s.klucz) {
    console.error('\n  W .env nie ma ROUTEWAY_API_KEY — bez niego nie ma czym tworzyc obrazow. Przerywam.');
    process.exit(1);
  }

  console.log('\n=== 3. Model u dostawcy ===');
  const rozmiary = await obrazy.rozmiary();
  if (!rozmiary.length) {
    console.error(`  Dostawca nie zna modelu "${s.model}" albo nie odpowiada.`);
    try {
      const r = await fetch(s.api + '/models', { signal: AbortSignal.timeout(15000) });
      const j = await r.json();
      const inne = (j.data || []).filter((m) => (m.endpoints || []).includes('/v1/images/generations') && m.available !== false)
        .map((m) => m.id).slice(0, 10);
      if (inne.length) console.error('  Dostepne modele obrazow: ' + inne.join(', ') + '\n  Ustaw jeden w .env: IMAGE_MODEL=...');
    } catch {}
    process.exit(1);
  }
  console.log(`  rozmiary: ${rozmiary.join(', ')}`);
  console.log(`  1:1 -> ${obrazy.wybierzRozmiar(rozmiary, '1:1') || 'domyslny'}   ` +
    `16:9 -> ${obrazy.wybierzRozmiar(rozmiary, '16:9') || 'domyslny'}   ` +
    `9:16 -> ${obrazy.wybierzRozmiar(rozmiary, '9:16') || 'domyslny'}`);

  console.log('\n=== 4. Probny obraz ===');
  if (BEZ_TESTU) {
    console.log('  pominiety (--bez-testu)');
  } else {
    const t = Date.now();
    try {
      const o = await obrazy.stworz('A red apple on a white wooden table, soft daylight, studio photo', '1:1');
      const bajty = Buffer.from(o.b64, 'base64');
      const plik = path.join(APP, 'tmp', 'obraz-testowy.' + o.mime.split('/')[1]);
      fs.mkdirSync(path.dirname(plik), { recursive: true });
      fs.writeFileSync(plik, bajty);
      console.log(`  OK: ${o.mime}, ${Math.round(bajty.length / 1024)} KB, ${((Date.now() - t) / 1000).toFixed(1)} s`);
      console.log(`  zapisany: ${path.relative(APP, plik)}`);
    } catch (e) {
      console.error('  Dostawca nie stworzyl obrazu: ' + (e && e.message));
      console.error('  Nic nie zmienilem. Sprawdz srodki na koncie dostawcy albo ustaw inny IMAGE_MODEL.');
      process.exit(1);
    }
  }

  console.log('\n=== 5. Tabela na limity ===');
  try {
    await obrazy.przygotujTabele();
    const [w] = await obrazy.baza().query('SELECT COUNT(*) AS n FROM image_quota');
    console.log(`  image_quota: gotowa, zapisanych dni z obrazami: ${w[0].n}`);
    await obrazy.baza().end();
  } catch (e) {
    console.error('  Nie udalo sie przygotowac tabeli: ' + (e && e.message));
    console.error('  Nic nie zmienilem w plikach. Sprawdz DB_* w .env i uprawnienia uzytkownika bazy (CREATE).');
    process.exit(1);
  }

  console.log('\n=== 6. Plik startowy ===');
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
    linie.splice(gdzie, 0, LINIA + '   // obrazy i zdjecia w czacie');
    tresc = linie.join('\n');
    fs.writeFileSync(start, tresc);
    console.log(`  dopisane w linii ${gdzie + 1}: ${LINIA}`);
  }
  const cofnij = () => { if (kopia) { fs.copyFileSync(kopia, start); przeladuj(); } };

  try {
    przeladuj();
    console.log('\n  Przeladowane. Czekam, az aplikacja wstanie...');
    await spij(CZEKAJ_MS);

    const zapytaj = async (metoda, sciezka, dane, naglowki = {}) => {
      try {
        const r = await fetch(ADRES + sciezka, {
          method: metoda,
          headers: Object.assign(dane === undefined ? {} : { 'Content-Type': 'application/json' }, naglowki),
          body: dane === undefined ? undefined : (typeof dane === 'string' ? dane : JSON.stringify(dane)),
          signal: AbortSignal.timeout(30000),
        });
        return { kod: r.status, tresc: await r.text() };
      } catch (e) { return { kod: 0, tresc: e.message }; }
    };

    console.log('\n=== 7. Czy nowe adresy odpowiadaja ===');
    // Bez zalogowania: nasza trasa odpowie 401, a gdy jej nie ma — 404. Nic nie kosztuje.
    // Stary proces potrafi jeszcze chwile obslugiwac zapytania, wiec 404 tez przeczekujemy.
    let a = await zapytaj('GET', '/v1/images/quota');
    for (let i = 0; i < 8 && [0, 404, 502, 503].includes(a.kod); i++) {
      await spij(PONOW_MS);
      a = await zapytaj('GET', '/v1/images/quota');
    }
    console.log(`  /v1/images/quota        HTTP ${a.kod}  ${a.tresc.slice(0, 100)}`);
    const b = await zapytaj('POST', '/v1/images/generations', { prompt: 'test', aspect: '1:1' });
    console.log(`  /v1/images/generations  HTTP ${b.kod}  ${b.tresc.slice(0, 100)}`);
    // Z niewaznym tokenem modul pyta /me serwera — 401 znaczy, ze sprawdzanie sesji dziala.
    const t = await zapytaj('GET', '/v1/images/quota', undefined, { Authorization: 'Bearer to-nie-jest-token' });
    console.log(`  sesja przez /me         HTTP ${t.kod}  ${t.tresc.slice(0, 100)}`);
    const zamontowane = a.kod === 401 && /unauthorized/.test(a.tresc) && b.kod === 401;
    const sesje = t.kod === 401 || t.kod === 429;
    if (!zamontowane || !sesje) {
      cofnij();
      console.error(kopia
        ? '\n  Nowe adresy nie dzialaja jak trzeba — cofnalem zmiane w pliku startowym i przeladowalem.'
        : '\n  Nowe adresy nie dzialaja jak trzeba (linia byla juz wczesniej, niczego nie cofalem).');
      if (zamontowane && !sesje) console.error('  Modul nie umie zapytac serwera o sesje (/me). Przeslij ten wynik dalej.');
      pokazBledy();
      process.exit(1);
    }

    console.log('\n=== 8. Czy reszta serwera dziala jak dotad ===');
    const c = await zapytaj('POST', '/auth/register', { email: 'x', password: 'x', name: 'x' });
    console.log(`  /auth/register  HTTP ${c.kod}  ${c.tresc.slice(0, 100)}`);
    const m = await zapytaj('GET', '/v1/models');
    console.log(`  /v1/models      HTTP ${m.kod}`);
    if (c.kod === 0 || c.kod === 404 || c.kod >= 500 || m.kod === 0 || m.kod === 404 || m.kod >= 500) {
      cofnij();
      console.error('\n  Stare adresy przestaly odpowiadac — cofnalem zmiane i przeladowalem.');
      process.exit(1);
    }

    console.log('\n=== 9. Zdjecia w czacie (duze zapytanie) ===');
    // 1,5 MB bez zalogowania: przepuszczone dalej -> 401, za duze -> 413.
    const duze = JSON.stringify({ model: 'x', messages: [{ role: 'user', content: 'x'.repeat(1500000) }] });
    const d = await zapytaj('POST', '/v1/chat/completions', duze);
    console.log(`  1,5 MB  HTTP ${d.kod}  ${d.tresc.slice(0, 100)}`);
    if (d.kod === 413 || d.kod >= 500 || d.kod === 0) {
      console.log('  UWAGA: serwer nie przyjmuje duzych zapytan. Obrazy beda dzialac, ale zdjecia w czacie');
      console.log('  aplikacja wysle wtedy bez obrazu (model zobaczy sam tekst). Przeslij ten wynik dalej.');
    } else {
      console.log('  OK — zdjecia w czacie przejda.');
    }

    console.log(`\nGotowe. Obrazy dzialaja: ${s.limit} dziennie na konto, model ${s.model}.`);
    console.log('W aplikacji: "+" -> "Stworz obraz" albo popros model w rozmowie o obraz.');
    if (kopia) console.log(`Wypiecie w razie potrzeby: node dodaj-obrazy.js --usun`);
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
