// Odzyskiwanie zapomnianego hasla dla cyphr-api.
//
//   POST /auth/forgot  { email }                   -> 200 {status:"ok"}
//   POST /auth/reset   { email, code, password }   -> 200 {status:"ok"}
//
// Modul jest samodzielny: ma wlasne polaczenie do bazy i wlasna poczte,
// czytane z ~/cyphr-api/.env. Nie siega do srodka istniejacego kodu, zeby
// nie dalo sie niczego w nim zepsuc. Wpina sie w chwili tworzenia aplikacji
// Express — w pliku startowym wystarczy jedna linia require na poczatku.
'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const KATALOG = __dirname;
const WAZNOSC_MINUT = 15;
const MAX_PROB = 5;
const ODSTEP_SEKUND = 60;          // miedzy kolejnymi wysylkami na ten sam adres
const LIMIT_IP_NA_GODZINE = 20;

// ---------- .env ----------
const env = (() => {
  const o = {};
  let t = '';
  try { t = fs.readFileSync(path.join(KATALOG, '.env'), 'utf8'); } catch { return o; }
  for (const l of t.split('\n')) {
    const m = l.match(/^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (!m) continue;
    let v = m[2];
    if (/^'.*'$/s.test(v) || /^".*"$/s.test(v)) v = v.slice(1, -1);
    o[m[1]] = v;
  }
  return o;
})();
const we = (...n) => { for (const x of n) if (env[x]) return env[x]; };

const wymagaj = (nazwa) => {
  for (const p of [path.join(KATALOG, 'node_modules', nazwa), nazwa]) {
    try { return require(p); } catch {}
  }
  return null;
};

// ---------- baza ----------
let pula = null;
function baza() {
  if (pula) return pula;
  const mysql = wymagaj('mysql2/promise') || wymagaj('mysql2');
  if (!mysql) throw new Error('brak mysql2');
  pula = (mysql.promise ? mysql : mysql).createPool({
    host: we('DB_HOST', 'MYSQL_HOST') || 'localhost',
    port: Number(we('DB_PORT') || 3306),
    user: we('DB_USER', 'MYSQL_USER'),
    password: we('DB_PASSWORD', 'DB_PASS', 'MYSQL_PASSWORD') || '',
    database: we('DB_NAME', 'DB_DATABASE', 'MYSQL_DATABASE'),
    connectionLimit: 3,
    waitForConnections: true,
  });
  return pula;
}

// ---------- poczta ----------
function wyslij(doKogo, kod) {
  const nodemailer = wymagaj('nodemailer');
  if (!nodemailer) throw new Error('brak nodemailer');
  const port = Number(we('SMTP_PORT') || 465);
  const t = nodemailer.createTransport({
    host: we('SMTP_HOST'), port, secure: port === 465,
    auth: { user: we('SMTP_USER'), pass: we('SMTP_PASS', 'SMTP_PASSWORD') },
    connectionTimeout: 15000, greetingTimeout: 15000, socketTimeout: 15000,
  });
  return t.sendMail({
    from: we('SMTP_FROM') || we('SMTP_USER'),
    to: doKogo,
    subject: `CYPHR — kod do zmiany hasła: ${kod}`,
    text:
      `Twój kod do ustawienia nowego hasła: ${kod}\n\n` +
      `Kod jest ważny ${WAZNOSC_MINUT} minut i można go użyć raz.\n` +
      `Jeśli to nie Ty prosiłeś o zmianę hasła, zignoruj tę wiadomość — ` +
      `dotychczasowe hasło dalej działa.\n`,
  });
}

// ---------- haslo ----------
async function haszujHaslo(haslo) {
  const bcrypt = wymagaj('bcrypt') || wymagaj('bcryptjs');
  if (!bcrypt) throw new Error('brak bcrypt');
  // W bazie leza wylacznie hasze "$2a$". Nowsze biblioteki tworza "$2b$" i
  // starsze wersje potrafia go nie przyjac przy logowaniu — a wtedy uzytkownik
  // zostalby zamkniety poza wlasnym kontem. Trzymamy sie wiec tego, co juz jest.
  const sol = (await bcrypt.genSalt(10)).replace(/^\$2[aby]\$/, '$2a$');
  return bcrypt.hash(haslo, sol);
}

const sha = (t) => crypto.createHash('sha256').update(String(t)).digest('hex');
const rowne = (a, b) => {
  const x = Buffer.from(String(a)), y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
};

// ---------- proste ograniczniki w pamieci ----------
const ostatnioNaAdres = new Map();
const licznikIp = new Map();
function wolnoIp(ip) {
  const teraz = Date.now();
  const w = licznikIp.get(ip) || { od: teraz, ile: 0 };
  if (teraz - w.od > 3600e3) { w.od = teraz; w.ile = 0; }
  w.ile += 1;
  licznikIp.set(ip, w);
  if (licznikIp.size > 5000) licznikIp.clear();
  return w.ile <= LIMIT_IP_NA_GODZINE;
}

// ---------- odczyt ciala zapytania ----------
// Nasze trasy sa rejestrowane przed express.json(), wiec czytamy strumien sami.
function cialo(req) {
  if (req.body && typeof req.body === 'object' && Object.keys(req.body).length) {
    return Promise.resolve(req.body);
  }
  return new Promise((gotowe) => {
    let dane = '';
    let zaduzo = false;
    req.on('data', (c) => {
      dane += c;
      if (dane.length > 10000) { zaduzo = true; req.destroy(); }
    });
    req.on('end', () => {
      if (zaduzo) return gotowe({});
      try { gotowe(JSON.parse(dane || '{}')); } catch { gotowe({}); }
    });
    req.on('error', () => gotowe({}));
  });
}

const odpowiedz = (res, kod, tresc) => {
  res.status(kod).set('Content-Type', 'application/json; charset=utf-8').send(JSON.stringify(tresc));
};
const POPRAWNY_ADRES = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// ---------- tabela na kody ----------
let tabelaGotowa = false;
async function przygotujTabele() {
  if (tabelaGotowa) return;
  await baza().query(
    'CREATE TABLE IF NOT EXISTS reset_codes (' +
    '  email VARCHAR(190) NOT NULL PRIMARY KEY,' +
    '  code_hash CHAR(64) NOT NULL,' +
    '  attempts INT NOT NULL DEFAULT 0,' +
    '  expires_at DATETIME NOT NULL,' +
    '  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP' +
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
  );
  tabelaGotowa = true;
}

// ---------- trasy ----------
function zarejestruj(app) {
  app.post('/auth/forgot', async (req, res) => {
    try {
      const c = await cialo(req);
      const email = String(c.email || '').trim().toLowerCase();
      const ip = (req.headers['x-forwarded-for'] || '').split(',')[0].trim() || req.ip || '';
      if (!POPRAWNY_ADRES.test(email)) {
        return odpowiedz(res, 400, { error: 'bad_email', message: 'Podaj poprawny adres e-mail.' });
      }
      if (!wolnoIp(ip)) {
        return odpowiedz(res, 429, { error: 'rate_limited', message: 'Za dużo prób. Spróbuj później.' });
      }
      const poprzednio = ostatnioNaAdres.get(email) || 0;
      if (Date.now() - poprzednio < ODSTEP_SEKUND * 1000) {
        // Odpowiadamy jak przy sukcesie, zeby nie dalo sie mierzyc czasem.
        return odpowiedz(res, 200, { status: 'ok' });
      }

      await przygotujTabele();
      const [konta] = await baza().query(
        'SELECT id, blocked FROM users WHERE email = ? LIMIT 1', [email],
      );
      // Zawsze ta sama odpowiedz — inaczej dalo by sie sprawdzac, kto ma konto.
      if (!konta.length || konta[0].blocked) return odpowiedz(res, 200, { status: 'ok' });

      const kod = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
      await baza().query(
        'INSERT INTO reset_codes (email, code_hash, attempts, expires_at) ' +
        'VALUES (?, ?, 0, DATE_ADD(NOW(), INTERVAL ? MINUTE)) ' +
        'ON DUPLICATE KEY UPDATE code_hash = VALUES(code_hash), attempts = 0, ' +
        'expires_at = VALUES(expires_at), created_at = NOW()',
        [email, sha(kod), WAZNOSC_MINUT],
      );
      ostatnioNaAdres.set(email, Date.now());
      await wyslij(email, kod);
      return odpowiedz(res, 200, { status: 'ok' });
    } catch (e) {
      console.error('[reset-hasla] /auth/forgot', e && e.message);
      return odpowiedz(res, 500, { error: 'server_error', message: 'Nie udało się wysłać kodu. Spróbuj za chwilę.' });
    }
  });

  app.post('/auth/reset', async (req, res) => {
    try {
      const c = await cialo(req);
      const email = String(c.email || '').trim().toLowerCase();
      const kod = String(c.code || '').trim();
      const haslo = String(c.password || '');
      if (!POPRAWNY_ADRES.test(email) || !/^\d{6}$/.test(kod)) {
        return odpowiedz(res, 400, { error: 'bad_request', message: 'Podaj adres i sześciocyfrowy kod.' });
      }
      if (haslo.length < 8) {
        return odpowiedz(res, 400, { error: 'weak_password', message: 'Hasło musi mieć co najmniej 8 znaków.' });
      }

      await przygotujTabele();
      const [wiersze] = await baza().query(
        'SELECT code_hash, attempts, expires_at < NOW() AS wygasl FROM reset_codes WHERE email = ? LIMIT 1',
        [email],
      );
      if (!wiersze.length || wiersze[0].wygasl) {
        return odpowiedz(res, 400, { error: 'code_expired', message: 'Kod wygasł. Poproś o nowy.' });
      }
      if (wiersze[0].attempts >= MAX_PROB) {
        await baza().query('DELETE FROM reset_codes WHERE email = ?', [email]);
        return odpowiedz(res, 400, { error: 'too_many', message: 'Za dużo błędnych prób. Poproś o nowy kod.' });
      }
      if (!rowne(wiersze[0].code_hash, sha(kod))) {
        await baza().query('UPDATE reset_codes SET attempts = attempts + 1 WHERE email = ?', [email]);
        return odpowiedz(res, 400, { error: 'code_invalid', message: 'Kod jest nieprawidłowy.' });
      }

      const hasz = await haszujHaslo(haslo);
      const [wynik] = await baza().query(
        'UPDATE users SET pass_hash = ?, verified = 1 WHERE email = ? AND blocked = 0',
        [hasz, email],
      );
      await baza().query('DELETE FROM reset_codes WHERE email = ?', [email]);
      if (!wynik.affectedRows) {
        return odpowiedz(res, 400, { error: 'no_account', message: 'Nie ma takiego konta.' });
      }
      // Stare sesje traca waznosc — zmiana hasla ma wyrzucac z cudzych urzadzen.
      try { await baza().query('DELETE FROM sessions WHERE user_id = (SELECT id FROM users WHERE email = ?)', [email]); } catch {}
      return odpowiedz(res, 200, { status: 'ok' });
    } catch (e) {
      console.error('[reset-hasla] /auth/reset', e && e.message);
      return odpowiedz(res, 500, { error: 'server_error', message: 'Nie udało się zmienić hasła. Spróbuj za chwilę.' });
    }
  });
}

// ---------- wpiecie sie w Express ----------
try {
  const sciezka = require.resolve(path.join(KATALOG, 'node_modules', 'express'));
  const oryginal = require(sciezka);
  if (!oryginal.__resetHaslaWpiety) {
    const opakowany = function (...a) {
      const app = oryginal(...a);
      try { zarejestruj(app); } catch (e) { console.error('[reset-hasla] rejestracja', e && e.message); }
      return app;
    };
    Object.assign(opakowany, oryginal);
    opakowany.__resetHaslaWpiety = true;
    require.cache[sciezka].exports = opakowany;
  }
} catch (e) {
  console.error('[reset-hasla] nie udalo sie wpiac w express:', e && e.message);
}

module.exports = { zarejestruj };
