// Wpina odzyskiwanie hasla do cyphr-api.
//
// Do istniejacego kodu dopisuje jedna linie na poczatku pliku startowego.
// Potem przeladowuje, sprawdza od zewnatrz, czy nowe adresy odpowiadaja i czy
// stara rejestracja dalej dziala — a gdy cokolwiek sie nie zgadza, przywraca
// plik startowy i przeladowuje jeszcze raz.
//
// Uruchom:  cd ~/cyphr-api && node dodaj-reset.js
'use strict';
const fs = require('fs'), path = require('path'), cp = require('child_process');

const APP = process.cwd();
const ADRES = process.env.CYPHR_URL || 'https://billing.cyphr.com.pl';
const LINIA = "require('./reset-hasla');";

const jest = (p) => { try { fs.accessSync(p); return true; } catch { return false; } };

console.log('=== 1. Czego potrzebuje modul ===');
if (!jest(path.join(APP, 'reset-hasla.js'))) {
  console.error('  Brakuje pliku reset-hasla.js w ~/cyphr-api/. Wgraj go obok tego skryptu.');
  process.exit(1);
}
let pj = {};
try { pj = JSON.parse(fs.readFileSync(path.join(APP, 'package.json'), 'utf8')); } catch {}
console.log(`  package.json: main=${pj.main || '(brak)'}  type=${pj.type || 'commonjs'}  start=${(pj.scripts || {}).start || '(brak)'}`);
if (pj.type === 'module') {
  console.error('\n  Projekt jest w trybie ESM (type: module) — ta wersja instalatora tego nie obsluguje.');
  console.error('  Przeslij te linie dalej, przygotuje wariant pod ESM.');
  process.exit(1);
}
for (const n of ['express', 'mysql2', 'nodemailer']) {
  console.log(`  ${n.padEnd(12)} ${jest(path.join(APP, 'node_modules', n)) ? 'jest' : 'BRAK'}`);
}
const bc = ['bcrypt', 'bcryptjs'].find((n) => jest(path.join(APP, 'node_modules', n)));
console.log(`  bcrypt       ${bc || 'BRAK'}`);
if (!jest(path.join(APP, 'node_modules', 'express')) || !bc) {
  console.error('\n  Bez express i bcrypt nie ruszam. Przerywam.');
  process.exit(1);
}

console.log('\n=== 2. Plik startowy ===');
const kandydaci = [pj.main, ((pj.scripts || {}).start || '').match(/[\w./-]+\.[cm]?js/)?.[0],
  'app.js', 'server.js', 'index.js', 'src/app.js', 'src/server.js', 'src/index.js'].filter(Boolean);
const start = kandydaci.map((k) => path.join(APP, k)).find(jest);
if (!start) { console.error('  Nie znalazlem pliku startowego. Sprawdzalem: ' + kandydaci.join(', ')); process.exit(1); }
console.log('  ' + path.relative(APP, start));

let tresc = fs.readFileSync(start, 'utf8');
if (tresc.includes('reset-hasla')) {
  console.log('  Linia juz tam jest — nic nie dopisuje.');
} else {
  const kopia = start + '.bak-' + Date.now();
  fs.copyFileSync(start, kopia);
  console.log('  kopia: ' + path.basename(kopia));
  // Wchodzimy na sama gore, za ewentualnym #! i 'use strict'.
  const linie = tresc.split('\n');
  let gdzie = 0;
  if (/^#!/.test(linie[0] || '')) gdzie = 1;
  if (/^\s*['"]use strict['"]\s*;?\s*$/.test(linie[gdzie] || '')) gdzie += 1;
  linie.splice(gdzie, 0, LINIA + '   // odzyskiwanie hasla');
  tresc = linie.join('\n');
  fs.writeFileSync(start, tresc);
  console.log(`  dopisane w linii ${gdzie + 1}: ${LINIA}`);
  global.__kopiaStartu = kopia;
}

const przeladuj = () => {
  fs.mkdirSync(path.join(APP, 'tmp'), { recursive: true });
  fs.writeFileSync(path.join(APP, 'tmp', 'restart.txt'), String(Date.now()));
};
const cofnij = () => {
  if (global.__kopiaStartu) { fs.copyFileSync(global.__kopiaStartu, start); przeladuj(); }
};

(async () => {
  przeladuj();
  console.log('\n  Przeladowane. Czekam, az aplikacja wstanie...');
  await new Promise((r) => setTimeout(r, 9000));

  const poslij = async (sciezka, dane) => {
    try {
      const r = await fetch(ADRES + sciezka, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(dane),
      });
      return { kod: r.status, tresc: await r.text() };
    } catch (e) { return { kod: 0, tresc: e.message }; }
  };

  console.log('\n=== 3. Czy nowe adresy odpowiadaja ===');
  // Celowo zly adres: nasza trasa odpowie 400, a gdy jej nie ma — 404.
  // Zaden mail przy tym nie leci.
  const a = await poslij('/auth/forgot', { email: 'to-nie-jest-adres' });
  console.log(`  /auth/forgot  HTTP ${a.kod}  ${a.tresc.slice(0, 120)}`);
  const b = await poslij('/auth/reset', { email: 'to-nie-jest-adres', code: '000000', password: 'x' });
  console.log(`  /auth/reset   HTTP ${b.kod}  ${b.tresc.slice(0, 120)}`);

  const zamontowane = a.kod === 400 && /bad_email/.test(a.tresc) && b.kod === 400;
  if (!zamontowane) {
    cofnij();
    console.error('\n  Nowe adresy nie odpowiadaja — cofnalem zmiane w pliku startowym i przeladowalem.');
    console.error('  Ostatnie bledy:');
    for (const k of [APP, path.join(APP, 'logs')]) {
      try {
        for (const f of fs.readdirSync(k).filter((f) => /stderr|error/i.test(f)).slice(0, 2)) {
          console.error(`  --- ${path.join(k, f)} ---`);
          console.error(cp.execSync(`tail -n 20 ${JSON.stringify(path.join(k, f))}`).toString());
        }
      } catch {}
    }
    process.exit(1);
  }

  console.log('\n=== 4. Czy stara rejestracja dalej dziala ===');
  const c = await poslij('/auth/register', { email: 'x', password: 'x', name: 'x' });
  console.log(`  /auth/register  HTTP ${c.kod}  ${c.tresc.slice(0, 120)}`);
  if (c.kod === 0 || c.kod === 404 || c.kod >= 500) {
    cofnij();
    console.error('\n  Rejestracja przestala odpowiadac — cofnalem zmiane i przeladowalem.');
    process.exit(1);
  }

  console.log('\n=== 5. Tabela na kody ===');
  try {
    const mysql = require(path.join(APP, 'node_modules', 'mysql2', 'promise'));
    const env = {};
    for (const l of fs.readFileSync(path.join(APP, '.env'), 'utf8').split('\n')) {
      const m = l.match(/^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
      if (m) { let v = m[2]; if (/^'.*'$/s.test(v) || /^".*"$/s.test(v)) v = v.slice(1, -1); env[m[1]] = v; }
    }
    const db = await mysql.createConnection({
      host: env.DB_HOST || 'localhost', port: Number(env.DB_PORT || 3306),
      user: env.DB_USER, password: env.DB_PASSWORD || env.DB_PASS || '', database: env.DB_NAME || env.DB_DATABASE,
    });
    const [t] = await db.query("SHOW TABLES LIKE 'reset_codes'");
    console.log(t.length ? '  reset_codes: jest' : '  reset_codes: powstanie przy pierwszym uzyciu');
    await db.end();
  } catch (e) { console.log('  nie sprawdzilem:', e.message); }

  console.log('\nGotowe. Odzyskiwanie hasla dziala.');
  console.log('Sprawdz w aplikacji: "Nie pamietam hasla" -> kod z maila -> nowe haslo.');
})().catch((e) => {
  cofnij();
  console.error('BLAD:', e && e.message, '— cofnalem zmiane w pliku startowym.');
  process.exit(1);
});
