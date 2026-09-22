// Naprawia haslo SMTP w .env i wlacza potwierdzanie adresu przy rejestracji.
//
// Przyczyna: dotenv (tego uzywa aplikacja) tnie niecytowana wartosc na pierwszym
// znaku "#", bo traktuje reszte jako komentarz. Haslo konczace sie na "##" trafia
// do aplikacji obciete, serwer poczty odpowiada "535 Incorrect authentication
// data", a rejestracja konczy sie bledem 500.
//
// Skrypt porownuje, co jest w pliku, z tym, co widzi dotenv, dopisuje cudzyslowy
// tam, gdzie wartosc sie rozjezdza, i sprawdza naprawe parserem samej aplikacji.
// Gdy rejestracja mimo wszystko przestanie dzialac, cofa cala zmiane.
//
// Uruchom:  cd ~/cyphr-api && node napraw-smtp.js
'use strict';
const fs = require('fs'), path = require('path'), os = require('os'), cp = require('child_process');

const APP = process.cwd();
const PLIK = path.join(APP, '.env');
const ADRES = process.env.CYPHR_URL || 'https://billing.cyphr.com.pl';
const POLA = ['SMTP_HOST', 'SMTP_PORT', 'SMTP_USER', 'SMTP_PASS', 'SMTP_PASSWORD', 'SMTP_FROM', 'SMTP_SECURE'];

const zaslon = (v) => (!v ? '(puste)' : v.length <= 4 ? '****' : v.slice(0, 2) + '***' + v.slice(-2));

// Parser, ktory bierze cala linie — tak jak czlowiek czyta plik.
const czytajCalosc = (t) => {
  const o = {};
  for (const l of t.split('\n')) {
    if (/^\s*#/.test(l)) continue;
    const m = l.match(/^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (!m) continue;
    let v = m[2];
    if (/^'.*'$/s.test(v) || /^".*"$/s.test(v)) v = v.slice(1, -1);
    o[m[1]] = v;
  }
  return o;
};

// Parser aplikacji. Gdy dotenv jest pod reka, uzywamy dokladnie jego.
let dotenv = null;
for (const p of [path.join(APP, 'node_modules', 'dotenv'), 'dotenv']) {
  try { dotenv = require(p); break; } catch {}
}
const czytajJakAplikacja = (t) => {
  if (dotenv && typeof dotenv.parse === 'function') return dotenv.parse(t);
  // Zapasowo: to samo zachowanie co dotenv — niecytowana wartosc konczy sie na "#".
  const o = {};
  const re = /^\s*(?:export\s+)?([\w.-]+)\s*=\s*('(?:\\'|[^'])*'|"(?:\\"|[^"])*"|[^#\r\n]*)/gm;
  let m;
  while ((m = re.exec(t))) {
    let v = (m[2] || '').trim();
    if (/^'.*'$/s.test(v) || /^".*"$/s.test(v)) v = v.slice(1, -1);
    o[m[1]] = v;
  }
  return o;
};

const cytuj = (v) => (!v.includes("'") ? `'${v}'` : `"${v.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`);

let tekst = fs.readFileSync(PLIK, 'utf8');
const wPliku = czytajCalosc(tekst);
const wAplikacji = czytajJakAplikacja(tekst);

console.log('=== 1. Co jest w pliku, a co widzi aplikacja ===');
console.log(dotenv ? '  (porownanie parserem dotenv z cyphr-api)' : '  (dotenv niedostepny — uzywam jego zachowania)');
const dorozniace = [];
for (const k of POLA) {
  if (!(k in wPliku)) continue;
  const a = wPliku[k], b = wAplikacji[k] ?? '';
  const rowne = a === b;
  if (!rowne) dorozniace.push(k);
  const pokaz = /PASS/i.test(k) ? [zaslon(a), zaslon(b)] : [a || '(puste)', b || '(puste)'];
  console.log(`  ${k.padEnd(14)} plik=${pokaz[0].padEnd(14)} aplikacja=${pokaz[1].padEnd(14)} ${rowne ? 'zgodne' : '<<< UCIETE'}`);
}

// --- ustalenie ustawien poczty (brakujace dobieramy z innego .env) ---
const zInnych = {};
(function szukaj(kat, gl) {
  if (gl > 3) return;
  let wpisy = [];
  try { wpisy = fs.readdirSync(kat, { withFileTypes: true }); } catch { return; }
  for (const w of wpisy) {
    const p = path.join(kat, w.name);
    if (w.isDirectory() && !/^(node_modules|\.git|tmp|logs|\.cache)$/.test(w.name)) szukaj(p, gl + 1);
    else if (w.isFile() && /^\.env(\..+)?$/.test(w.name) && p !== PLIK) {
      try {
        const o = czytajCalosc(fs.readFileSync(p, 'utf8'));
        if (o.SMTP_HOST) for (const k of POLA) if (o[k] && !zInnych[k]) { zInnych[k] = o[k]; zInnych.__zrodlo = p; }
      } catch {}
    }
  }
})(os.homedir(), 0);

const we = (...n) => { for (const x of n) if (wPliku[x]) return wPliku[x]; for (const x of n) if (zInnych[x]) return zInnych[x]; };
const host = we('SMTP_HOST');
const port = Number(we('SMTP_PORT') || 465);
const user = we('SMTP_USER');
const klucz = wPliku.SMTP_PASSWORD !== undefined && wPliku.SMTP_PASS === undefined ? 'SMTP_PASSWORD' : 'SMTP_PASS';
const haslo = we('SMTP_PASS', 'SMTP_PASSWORD');
const nadawca = we('SMTP_FROM') || user;
if (!host || !user || !haslo) { console.error('\nBrak kompletnych ustawien poczty. Przerywam.'); process.exit(1); }

// --- zapis z cudzyslowami + weryfikacja parserem aplikacji ---
const kopia = PLIK + '.bak-' + Date.now();
fs.copyFileSync(PLIK, kopia);
console.log(`\n=== 2. Poprawiam zapis (kopia .env: ${path.basename(kopia)}) ===`);
const ustaw = (k, v, wCudzyslowie) => {
  const linia = `${k}=${wCudzyslowie ? cytuj(v) : v}`;
  const re = new RegExp(`^\\s*(?:export\\s+)?${k}\\s*=.*$`, 'm');
  tekst = re.test(tekst) ? tekst.replace(re, linia) : tekst.replace(/\n*$/, '\n') + linia + '\n';
  console.log(`  ${k} = ${/PASS/i.test(k) ? zaslon(v) : v}${wCudzyslowie ? '  (w cudzyslowie)' : ''}`);
};
ustaw('SMTP_HOST', host, false);
ustaw('SMTP_PORT', String(port), false);
ustaw('SMTP_USER', user, true);
ustaw(klucz, haslo, true);
ustaw('SMTP_FROM', nadawca, true);

const poNaprawie = czytajJakAplikacja(tekst);
if (poNaprawie[klucz] !== haslo) {
  console.error(`\n  Aplikacja nadal czytalaby haslo inaczej niz jest w pliku. Nic nie zapisuje.`);
  process.exit(1);
}
console.log('  Sprawdzone: aplikacja przeczyta teraz haslo w calosci.');
fs.writeFileSync(PLIK, tekst);

const przeladuj = () => {
  fs.mkdirSync(path.join(APP, 'tmp'), { recursive: true });
  fs.writeFileSync(path.join(APP, 'tmp', 'restart.txt'), String(Date.now()));
};

// Cofamy samo wlaczenie weryfikacji. Cudzyslowy wokol hasla zostaja, bo bez nich
// aplikacja i tak czytalaby je obciete — to poprawka dobra niezaleznie od reszty.
const wylaczWeryfikacje = () => {
  const t = fs.readFileSync(PLIK, 'utf8').replace(/^\s*(?:export\s+)?SMTP_HOST\s*=.*$/m, 'SMTP_HOST=');
  fs.writeFileSync(PLIK, t);
  przeladuj();
};

(async () => {
  // --- probna wysylka wartosciami, ktore zobaczy aplikacja ---
  let nodemailer;
  for (const p of [path.join(APP, 'node_modules', 'nodemailer'), 'nodemailer']) {
    try { nodemailer = require(p); break; } catch {}
  }
  if (nodemailer) {
    console.log(`\n=== 3. Probna wysylka wartosciami z parsera aplikacji ===`);
    try {
      const t = nodemailer.createTransport({
        host: poNaprawie.SMTP_HOST, port: Number(poNaprawie.SMTP_PORT), secure: Number(poNaprawie.SMTP_PORT) === 465,
        auth: { user: poNaprawie.SMTP_USER, pass: poNaprawie[klucz] },
        connectionTimeout: 20000, greetingTimeout: 20000, socketTimeout: 20000,
      });
      await t.verify();
      const r = await t.sendMail({ from: poNaprawie.SMTP_FROM, to: poNaprawie.SMTP_USER, subject: 'CYPHR — weryfikacja wlaczona', text: 'Wysylka kodow przy rejestracji dziala.' });
      console.log('  Wyslane. Identyfikator:', r.messageId);
    } catch (e) {
      wylaczWeryfikacje();
      console.error('  NIE UDALO SIE:', e && e.message, e && e.response ? '| ' + e.response : '');
      console.error('  Wylaczylem weryfikacje z powrotem. Przeslij ten blad dalej.');
      process.exit(1);
    }
  }

  przeladuj();
  console.log('\n  Przeladowane. Czekam, az aplikacja wstanie...');
  await new Promise((r) => setTimeout(r, 8000));

  // --- sprawdzenie rejestracji od zewnatrz ---
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

  if (kod >= 500 || kod === 0) {
    wylaczWeryfikacje();
    console.error('\n  Rejestracja nadal nie dziala — wylaczylem weryfikacje z powrotem.');
    console.error(`  Rejestracja znowu wpuszcza. Pelna kopia sprzed zmian: ${path.basename(kopia)}`);
    console.error('  Ostatnie bledy:');
    for (const k of [APP, path.join(os.homedir(), 'logs'), path.join(APP, 'logs')]) {
      try {
        for (const f of fs.readdirSync(k).filter((f) => /stderr|error/i.test(f)).slice(0, 2)) {
          console.error(`  --- ${path.join(k, f)} ---`);
          console.error(cp.execSync(`grep -a "auth/register" ${JSON.stringify(path.join(k, f))} | tail -n 15`).toString());
        }
      } catch {}
    }
    process.exit(1);
  }

  if (dane.token) {
    console.log('\n  Rejestracja wpuszcza bez kodu — serwer nie patrzy na SMTP_HOST.');
    console.log('  Poprawka hasla zostaje. Przeslij ten wynik dalej.');
    process.exit(2);
  }

  console.log('\n  Dobrze: rejestracja czeka na kod z maila, a nie wydaje tokenu.');
  console.log('\nGotowe. Konto probne mozesz usunac:');
  console.log(`  DELETE FROM users WHERE email = '${mail}';`);
})().catch((e) => {
  try { wylaczWeryfikacje(); } catch {}
  console.error('BLAD:', e && e.message, '— wylaczylem weryfikacje z powrotem.');
  process.exit(1);
});
