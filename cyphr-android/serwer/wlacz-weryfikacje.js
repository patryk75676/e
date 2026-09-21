// Wlacza potwierdzanie adresu e-mail przy rejestracji w cyphr-api.
//
// Najpierw wysyla prawdziwego maila, zeby sprawdzic ustawienia. Dopiero gdy
// to wyjdzie, wlacza weryfikacje i sprawdza rejestracje od zewnatrz. Gdyby
// rejestracja przestala dzialac, sam cofa zmiane — nie zostawia zepsutego
// zakladania kont.
//
// Uruchom:  cd ~/cyphr-api && node wlacz-weryfikacje.js
'use strict';
const fs = require('fs'), path = require('path'), os = require('os'), cp = require('child_process');

const APP = process.cwd();
const PLIK = path.join(APP, '.env');
const ADRES = process.env.CYPHR_URL || 'https://billing.cyphr.com.pl';
const POLA = ['SMTP_HOST', 'SMTP_PORT', 'SMTP_USER', 'SMTP_PASS', 'SMTP_PASSWORD', 'SMTP_FROM', 'SMTP_SECURE',
  'MAIL_HOST', 'MAIL_PORT', 'MAIL_USER', 'MAIL_PASS', 'MAIL_FROM'];

const czytaj = (t) => {
  const o = {};
  for (const l of t.split('\n')) {
    const m = l.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (m) o[m[1]] = m[2].replace(/^["']|["']$/g, '');
  }
  return o;
};
const zaslon = (v) => (!v ? '(puste)' : v.length <= 4 ? '****' : v.slice(0, 2) + '***' + v.slice(-2));

const tekst = fs.readFileSync(PLIK, 'utf8');
const env = czytaj(tekst);

console.log('=== 1. Ustawienia poczty w ~/cyphr-api/.env ===');
for (const k of POLA) if (k in env) console.log(`  ${k} = ${/PASS/i.test(k) ? zaslon(env[k]) : env[k] || '(puste)'}`);

// Brakujace ustawienia szukamy w innych .env w katalogu domowym — tam lezy
// dzialajaca konfiguracja innej aplikacji.
const zInnych = {};
(function szukaj(kat, glebokosc) {
  if (glebokosc > 3) return;
  let wpisy = [];
  try { wpisy = fs.readdirSync(kat, { withFileTypes: true }); } catch { return; }
  for (const w of wpisy) {
    const p = path.join(kat, w.name);
    if (w.isDirectory() && !/^(node_modules|\.git|tmp|logs|\.cache|public_html\/.*cache)$/.test(w.name)) szukaj(p, glebokosc + 1);
    else if (w.isFile() && /^\.env(\..+)?$/.test(w.name) && p !== PLIK) {
      try {
        const o = czytaj(fs.readFileSync(p, 'utf8'));
        if (o.SMTP_HOST || o.MAIL_HOST) for (const k of POLA) if (o[k] && !zInnych[k]) { zInnych[k] = o[k]; zInnych['__zrodlo'] = p; }
      } catch {}
    }
  }
})(os.homedir(), 0);
if (zInnych.__zrodlo) console.log(`\n  Zapasowa konfiguracja znaleziona w ${zInnych.__zrodlo}`);

const we = (...n) => { for (const x of n) if (env[x]) return env[x]; for (const x of n) if (zInnych[x]) return zInnych[x]; };
const host = we('SMTP_HOST', 'MAIL_HOST');
const port = Number(we('SMTP_PORT', 'MAIL_PORT') || 587);
const user = we('SMTP_USER', 'MAIL_USER');
const haslo = we('SMTP_PASS', 'SMTP_PASSWORD', 'MAIL_PASS');
const nadawca = we('SMTP_FROM', 'MAIL_FROM') || user;
if (!host || !user || !haslo) { console.error('\nBrak kompletnych ustawien poczty — nie mam czego wlaczyc. Przerywam.'); process.exit(1); }

let nodemailer;
try { nodemailer = require(path.join(APP, 'node_modules', 'nodemailer')); }
catch { try { nodemailer = require('nodemailer'); } catch { console.error('\nBrak nodemailera w cyphr-api. Przerywam.'); process.exit(1); } }

(async () => {
  // --- 2. prawdziwa wysylka ---
  console.log(`\n=== 2. Probna wysylka przez ${host}:${port} jako ${user} ===`);
  const poczta = nodemailer.createTransport({
    host, port, secure: port === 465,
    auth: { user, pass: haslo },
    connectionTimeout: 20000, greetingTimeout: 20000, socketTimeout: 20000,
  });
  try {
    await poczta.verify();
    const r = await poczta.sendMail({
      from: nadawca, to: user,
      subject: 'CYPHR — test weryfikacji',
      text: 'Jesli to czytasz, wysylka kodow przy rejestracji dziala.',
    });
    console.log('  Wyslane. Identyfikator:', r.messageId);
  } catch (e) {
    console.error('\n  NIE UDALO SIE. Pelny blad:');
    console.error('  ', e && e.message);
    if (e && e.response) console.error('   odpowiedz serwera:', e.response);
    if (e && e.code) console.error('   kod:', e.code);
    console.error('\n  Nic nie zmieniam. Przeslij ten blad dalej.');
    process.exit(1);
  }

  // --- 3. wlaczenie ---
  const kopia = PLIK + '.bak-' + Date.now();
  fs.copyFileSync(PLIK, kopia);
  console.log(`\n=== 3. Wlaczam weryfikacje (kopia .env: ${path.basename(kopia)}) ===`);
  let nowy = tekst;
  const ustaw = (klucz, wartosc) => {
    const re = new RegExp(`^\\s*${klucz}\\s*=.*$`, 'm');
    if (re.test(nowy)) nowy = nowy.replace(re, `${klucz}=${wartosc}`);
    else nowy = nowy.replace(/\n*$/, '\n') + `${klucz}=${wartosc}\n`;
    console.log(`  ${klucz} = ${/PASS/i.test(klucz) ? zaslon(wartosc) : wartosc}`);
  };
  ustaw('SMTP_HOST', host); ustaw('SMTP_PORT', String(port)); ustaw('SMTP_USER', user);
  ustaw(env.SMTP_PASSWORD !== undefined && env.SMTP_PASS === undefined ? 'SMTP_PASSWORD' : 'SMTP_PASS', haslo);
  ustaw('SMTP_FROM', nadawca);
  fs.writeFileSync(PLIK, nowy);

  const przeladuj = () => {
    fs.mkdirSync(path.join(APP, 'tmp'), { recursive: true });
    fs.writeFileSync(path.join(APP, 'tmp', 'restart.txt'), String(Date.now()));
  };
  przeladuj();
  console.log('  Przeladowane. Czekam, az aplikacja wstanie...');
  await new Promise((r) => setTimeout(r, 8000));

  // --- 4. sprawdzenie od zewnatrz ---
  console.log('\n=== 4. Probna rejestracja przez ' + ADRES + ' ===');
  const mail = `test-weryfikacji-${Date.now()}@cyphr.com.pl`;
  let kod = 0, tresc = '';
  try {
    const r = await fetch(ADRES + '/auth/register', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: mail, password: 'Test12345!', name: 'Test Weryfikacji' }),
    });
    kod = r.status; tresc = await r.text();
  } catch (e) { tresc = e.message; }
  console.log(`  HTTP ${kod}  ${tresc.slice(0, 200)}`);

  let dane = {};
  try { dane = JSON.parse(tresc); } catch {}
  const wpuszcza = !!dane.token;
  const zepsute = kod >= 500 || kod === 0;

  if (zepsute) {
    fs.copyFileSync(kopia, PLIK); przeladuj();
    console.error('\n  Rejestracja przestala dzialac — cofnalem zmiane w .env i przeladowalem.');
    console.error('  Log bledow:');
    for (const k of [path.join(os.homedir(), 'logs'), path.join(APP, 'logs'), APP]) {
      try {
        const pliki = fs.readdirSync(k).filter((f) => /error|stderr|log/i.test(f)).slice(0, 3);
        for (const f of pliki) {
          console.error(`  --- ${path.join(k, f)} ---`);
          console.error(cp.execSync(`tail -n 25 ${JSON.stringify(path.join(k, f))}`).toString());
        }
      } catch {}
    }
    process.exit(1);
  }

  if (wpuszcza) {
    console.log('\n  Rejestracja nadal wpuszcza bez kodu — serwer nie patrzy na SMTP_HOST.');
    console.log('  Zmiana w .env zostaje, ale warunek jest gdzie indziej w kodzie. Przeslij ten wynik dalej.');
    process.exit(2);
  }

  console.log('\n  Dobrze: rejestracja nie zwraca juz tokenu, tylko czeka na kod z maila.');
  console.log('\nGotowe. Konto probne mozesz usunac:');
  console.log(`  DELETE FROM users WHERE email = '${mail}';`);
})().catch((e) => { console.error('BLAD:', e && e.message); process.exit(1); });
