// Obrazy dla cyphr-api: tworzenie obrazow z dziennym limitem na konto i wieksze
// zapytania czatu, zeby zmiescily sie w nich zdjecia i zrzuty ekranu.
//
//   GET  /v1/images/quota                        -> 200 {limit, used, left, resets_on}
//   POST /v1/images/generations {prompt, aspect} -> 200 {images:[{b64, mime}], limit, used, left}
//
// Modul jest samodzielny, tak jak reset-hasla.js: wlasne polaczenie do bazy, klucz dostawcy
// z ~/cyphr-api/.env, jedna linia require na poczatku pliku startowego.
//
// Kto pyta, rozstrzyga istniejacy kod serwera, nie ten modul: wewnatrz aplikacji (bez sieci)
// idzie zwykle GET /me z tym samym naglowkiem Authorization, a z odpowiedzi bierzemy id konta.
// Wygasle, wylogowane i zablokowane konta odpadaja wiec dokladnie tak, jak wszedzie indziej.
//
// Limit dzienny pilnuje baza jednym UPDATE ... WHERE used < limit, wiec kilka rownoleglych
// zapytan go nie przeskoczy. Obraz, ktory sie nie udal, oddaje miejsce w limicie.
//
// Klucz dostawcy: ROUTEWAY_API_KEY (ten sam, ktorego uzywa czat). Innych kluczy z .env
// modul nie czyta i nigdzie nie wysyla.
//
// Ustawienia w .env (wszystkie opcjonalne):
//   IMAGE_DAILY_LIMIT=2          ile obrazow dziennie na konto (0 wylacza obrazy)
//   IMAGE_MODEL=z-image-turbo    model obrazow u dostawcy
//   IMAGE_TZ=Europe/Warsaw       o polnocy ktorej strefy limit sie odnawia
//   IMAGE_JSON_LIMIT=16mb        najwiekszy JSON w zapytaniu (zdjecia w czacie)
'use strict';

const fs = require('fs');
const path = require('path');
const http = require('http');
const net = require('net');

const KATALOG = __dirname;

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

const LIMIT = (() => {
  const v = we('IMAGE_DAILY_LIMIT');
  if (v === undefined) return 2;
  const n = Math.floor(Number(v));
  return Number.isFinite(n) && n > 0 ? n : 0;
})();
const MODEL = we('IMAGE_MODEL') || 'z-image-turbo';
const STREFA = we('IMAGE_TZ') || 'Europe/Warsaw';
const LIMIT_JSON = we('IMAGE_JSON_LIMIT') || '16mb';
// Tylko klucz przeznaczony dla dostawcy modeli. Zadnego zgadywania po nazwach:
// w .env leza tez klucze platnosci i token administratora — te nie moga wyjsc z serwera.
const ZRODLO_KLUCZA = ['IMAGE_API_KEY', 'ROUTEWAY_API_KEY', 'ROUTEWAY_KEY'].find((n) => env[n]);
const KLUCZ = ZRODLO_KLUCZA ? env[ZRODLO_KLUCZA] : undefined;
const BAZA_API = (we('IMAGE_BASE_URL', 'ROUTEWAY_BASE_URL') || 'https://api.routeway.ai/v1').replace(/\/+$/, '');
// Caly obraz musi zdazyc przed tym, jak telefon przestanie czekac (60 s).
const CZAS_OBRAZU = Math.min(55000, Math.max(1000, Number(we('IMAGE_TIMEOUT_MS')) || 50000));

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

let tabelaGotowa = false;
async function przygotujTabele() {
  if (tabelaGotowa) return;
  await baza().query(
    'CREATE TABLE IF NOT EXISTS image_quota (' +
    '  user_id BIGINT NOT NULL,' +
    '  day DATE NOT NULL,' +
    '  used INT NOT NULL DEFAULT 0,' +
    '  PRIMARY KEY (user_id, day)' +
    ') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4',
  );
  tabelaGotowa = true;
}

/** Dzisiejsza data w strefie, w ktorej limit sie odnawia: "2026-09-23". */
function dzien(teraz = new Date(), strefa = STREFA) {
  try {
    const f = new Intl.DateTimeFormat('en-US', { timeZone: strefa, year: 'numeric', month: '2-digit', day: '2-digit' });
    const p = {};
    for (const x of f.formatToParts(teraz)) p[x.type] = x.value;
    if (/^\d{4}$/.test(p.year) && /^\d{2}$/.test(p.month) && /^\d{2}$/.test(p.day)) return `${p.year}-${p.month}-${p.day}`;
  } catch {}
  // Node bez danych o strefach: liczymy wedlug UTC.
  return teraz.toISOString().slice(0, 10);
}
function jutro(d) {
  const t = new Date(d + 'T12:00:00Z');
  t.setUTCDate(t.getUTCDate() + 1);
  return t.toISOString().slice(0, 10);
}

async function zuzyte(uid, d) {
  const [w] = await baza().query('SELECT used FROM image_quota WHERE user_id = ? AND day = ?', [uid, d]);
  return w.length ? Number(w[0].used) : 0;
}

/** Zajmuje miejsce w limicie. Jeden UPDATE z warunkiem — rownolegle zapytania go nie przeskocza. */
async function zarezerwuj(uid, d) {
  await baza().query('INSERT IGNORE INTO image_quota (user_id, day, used) VALUES (?, ?, 0)', [uid, d]);
  const [w] = await baza().query(
    'UPDATE image_quota SET used = used + 1 WHERE user_id = ? AND day = ? AND used < ?',
    [uid, d, LIMIT],
  );
  return w.affectedRows === 1;
}

async function oddaj(uid, d) {
  await baza().query('UPDATE image_quota SET used = used - 1 WHERE user_id = ? AND day = ? AND used > 0', [uid, d]);
}

// ---------- kto pyta: GET /me wewnatrz aplikacji ----------
// Wszystkie aplikacje Express utworzone w procesie. Glowna jest ta, ktora nasluchuje
// (app.listen) albo ta, ktora juz raz odpowiedziala na /me.
const aplikacje = [];
let glowna = null;
let zweryfikowana = null;

function kolejnosc() {
  const l = [];
  for (const a of [zweryfikowana, glowna, ...aplikacje]) if (a && !l.includes(a)) l.push(a);
  return l;
}

/**
 * Puszcza przez aplikacje Express zapytanie jak z zewnatrz, ale bez sieci — idzie przez
 * wszystkie jej warstwy (w tym ogranicznik zapytan i sprawdzanie sesji) z adresem
 * i naglowkami oryginalnego klienta. Dziala takze pod Passengerem, gdzie serwer nie ma portu.
 */
function wstrzyknij(aplikacja, zrodlo, metoda, sciezka) {
  return new Promise((gotowe, blad) => {
    if (!aplikacja) return blad(new Error('aplikacja jeszcze nie wstala'));
    const gniazdo = new net.Socket();
    const adres = (zrodlo.socket && zrodlo.socket.remoteAddress) || '127.0.0.1';
    Object.defineProperty(gniazdo, 'remoteAddress', { value: adres });
    Object.defineProperty(gniazdo, 'encrypted', { value: !!(zrodlo.socket && zrodlo.socket.encrypted) });
    const req = new http.IncomingMessage(gniazdo);
    req.method = metoda;
    req.url = sciezka;
    req.httpVersionMajor = 1;
    req.httpVersionMinor = 1;
    req.httpVersion = '1.1';
    const naglowki = { accept: 'application/json' };
    for (const k of ['authorization', 'x-forwarded-for', 'x-real-ip', 'x-forwarded-proto', 'user-agent', 'host']) {
      if (zrodlo.headers[k]) naglowki[k] = zrodlo.headers[k];
    }
    req.headers = naglowki;
    req.rawHeaders = Object.entries(naglowki).flat();
    req.push(null);
    req.complete = true;

    const res = new http.ServerResponse(req);
    const kawalki = [];
    let koniec = false;
    const zegar = setTimeout(() => { if (!koniec) { koniec = true; blad(new Error('/me nie odpowiedzialo')); } }, 15000);
    res.write = (c, kodowanie, cb) => {
      if (c != null && typeof c !== 'function') {
        kawalki.push(Buffer.isBuffer(c) ? c : Buffer.from(String(c), typeof kodowanie === 'string' ? kodowanie : 'utf8'));
      }
      if (typeof kodowanie === 'function') kodowanie(); else if (typeof cb === 'function') cb();
      return true;
    };
    res.end = (c, kodowanie, cb) => {
      if (c != null && typeof c !== 'function') res.write(c, kodowanie);
      if (!koniec) {
        koniec = true;
        clearTimeout(zegar);
        gotowe({ kod: res.statusCode, tresc: Buffer.concat(kawalki).toString('utf8') });
      }
      try { res.emit('finish'); } catch {}
      if (typeof c === 'function') c(); else if (typeof kodowanie === 'function') kodowanie(); else if (typeof cb === 'function') cb();
      return res;
    };
    try {
      aplikacja(req, res, (e) => {
        if (!koniec) { koniec = true; clearTimeout(zegar); gotowe({ kod: e ? 500 : 404, tresc: '' }); }
      });
    } catch (e) {
      if (!koniec) { koniec = true; clearTimeout(zegar); blad(e); }
    }
  });
}

/** Id konta z odpowiedzi /me albo odpowiedz dla klienta, gdy sie nie da. */
async function ktoPyta(req) {
  if (!req.headers.authorization) return { blad: [401, 'unauthorized', 'Zaloguj się ponownie.'] };
  let r = { kod: 404, tresc: '' };
  for (const a of kolejnosc()) {
    r = await wstrzyknij(a, req, 'GET', '/me');
    // 404 znaczy, ze ta aplikacja nie ma /me (np. podaplikacja) — pytamy nastepna.
    if (r.kod !== 404) { if (r.kod === 200) zweryfikowana = a; break; }
  }
  if (r.kod === 401 || r.kod === 403) return { blad: [401, 'unauthorized', 'Sesja wygasła. Zaloguj się ponownie.'] };
  if (r.kod === 429) return { blad: [429, 'rate_limited', 'Za szybko pod rząd. Odczekaj chwilę.'] };
  if (r.kod !== 200) return { blad: [502, 'server_error', 'Serwer chwilowo nie odpowiada. Spróbuj za moment.'] };
  let j = {};
  try { j = JSON.parse(r.tresc); } catch {}
  const u = (j && (j.user || j.data || j)) || {};
  const id = Number(u.id != null ? u.id : (u.user_id != null ? u.user_id : u.uid));
  if (!Number.isFinite(id) || id <= 0) return { blad: [502, 'server_error', 'Nie udało się rozpoznać konta.'] };
  return { id };
}

// ---------- dostawca ----------
let rozmiaryModelu = null;
let rozmiaryCzas = 0;

async function rozmiary() {
  if (rozmiaryModelu && Date.now() - rozmiaryCzas < 3600e3) return rozmiaryModelu;
  try {
    const r = await fetch(BAZA_API + '/models', {
      headers: KLUCZ ? { Authorization: 'Bearer ' + KLUCZ } : {},
      signal: AbortSignal.timeout(15000),
    });
    const j = await r.json();
    const m = (j.data || []).find((x) => x.id === MODEL);
    rozmiaryModelu = (m && Array.isArray(m.supported_sizes)) ? m.supported_sizes : [];
    rozmiaryCzas = Date.now();
  } catch {
    rozmiaryModelu = rozmiaryModelu || [];
  }
  return rozmiaryModelu;
}

/** Rozmiar z listy modelu najblizszy proporcjom: "1024x1024", "16:9"... albo nic (model wybierze sam). */
function wybierzRozmiar(dostepne, proporcje) {
  const [aw, ah] = proporcje.split(':').map(Number);
  const cel = aw / ah;
  const wxh = dostepne.map((s) => /^(\d+)x(\d+)$/.exec(String(s))).filter(Boolean)
    .map((m) => ({ s: m[0], w: +m[1], h: +m[2] }));
  if (wxh.length) {
    wxh.sort((a, b) => {
      const ra = Math.abs(Math.log(a.w / a.h / cel)), rb = Math.abs(Math.log(b.w / b.h / cel));
      if (Math.abs(ra - rb) > 1e-6) return ra - rb;
      return Math.abs(a.w * a.h - 1048576) - Math.abs(b.w * b.h - 1048576);
    });
    return wxh[0].s;
  }
  if (dostepne.includes(proporcje)) return proporcje;
  return undefined;
}

function rodzaj(buf) {
  if (buf.length > 4 && buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47) return 'image/png';
  if (buf.length > 3 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return 'image/jpeg';
  if (buf.length > 12 && buf.toString('ascii', 0, 4) === 'RIFF' && buf.toString('ascii', 8, 12) === 'WEBP') return 'image/webp';
  return null;
}

/** Blad dostawcy, ktory klient powinien zobaczyc inaczej niz zwykla awarie. */
class BladDostawcy extends Error {
  constructor(message, rodzaj) { super(message); this.rodzaj = rodzaj; }
}

/** Adres obrazu, ktory wolno pobrac: https albo ten sam serwer co API dostawcy. */
function wolnoPobrac(adres) {
  try {
    const u = new URL(adres);
    return u.protocol === 'https:' || u.origin === new URL(BAZA_API).origin;
  } catch { return false; }
}

async function stworz(opis, proporcje) {
  const koniec = Date.now() + CZAS_OBRAZU;
  const zostalo = () => Math.max(1000, koniec - Date.now());
  const rozmiar = wybierzRozmiar(await rozmiary(), proporcje);
  const cialo = { model: MODEL, prompt: opis, n: 1, response_format: 'b64_json' };
  if (rozmiar) cialo.size = rozmiar;
  const r = await fetch(BAZA_API + '/images/generations', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + KLUCZ, 'Content-Type': 'application/json' },
    body: JSON.stringify(cialo),
    signal: AbortSignal.timeout(zostalo()),
  });
  const tekst = await r.text();
  if (!r.ok) {
    if (r.status === 429) throw new BladDostawcy(`dostawca HTTP 429: ${tekst.slice(0, 200)}`, 'zajety');
    if ((r.status === 400 || r.status === 422) && /policy|safety|nsfw|moderat|inappropriate|not allowed|prohibited/i.test(tekst)) {
      throw new BladDostawcy(`dostawca odmowil: ${tekst.slice(0, 200)}`, 'odmowa');
    }
    throw new Error(`dostawca HTTP ${r.status}: ${tekst.slice(0, 300)}`);
  }
  let j = {};
  try { j = JSON.parse(tekst); } catch { throw new Error('dostawca: nie JSON'); }
  const d = (j.data || [])[0] || {};
  let buf = null;
  if (d.b64_json) {
    buf = Buffer.from(String(d.b64_json).replace(/^data:[^,]*,/, ''), 'base64');
  } else if (d.url && wolnoPobrac(d.url)) {
    // Niektore modele oddaja adres zamiast danych — pobieramy od razu, zeby aplikacja
    // nie widziala adresu dostawcy, a obraz nie zniknal razem z wygasajacym linkiem.
    const o = await fetch(d.url, { signal: AbortSignal.timeout(zostalo()) });
    if (!o.ok) throw new Error(`pobranie obrazu HTTP ${o.status}`);
    buf = Buffer.from(await o.arrayBuffer());
  }
  if (!buf || buf.length < 100) throw new Error('dostawca nie oddal obrazu');
  if (buf.length > 15 * 1024 * 1024) throw new Error('obraz za duzy');
  const mime = rodzaj(buf);
  if (!mime) throw new Error('to nie jest obraz');
  return { b64: buf.toString('base64'), mime };
}

// ---------- czego nie tworzymy ----------
// Tresci seksualne z udzialem dzieci — nigdy, w zadnym jezyku, niezaleznie od modelu.
const NIELETNI = /\b(child|children|kid|kids|minor|minors|underage|under-age|teen|teens|teenager|toddler|infant|baby|babies|loli|lolita|shota|preteen|pre-teen|schoolgirl|schoolboy|young girl|young boy|little girl|little boy)\b|\b1[0-7]\s*(yo|y\/o|years?\s*old|lat|letni)|\b[1-9]\s*(yo|y\/o|years?\s*old|lat|letni)|dzieck|dzieci|dziecię|nieletni|małolat|malolat|dziewczynk|chłopczyk|chlopczyk|nastolat|uczennic|przedszkol|niemowl/i;
// Polskie slowa tylko jako cale wyrazy: "nago" tak, "teenager" czy "Nagasaki" nie.
const SEKS = /\b(nude|nudes|naked|nsfw|sex|sexy|sexual|porn|porno|erotic|lingerie|topless|explicit|genital|genitals|breasts?|nipples?|undress|undressed|seductive|fetish)\b|\bnag(?:a|i|ie|o|ą|ich|imi|ość|osc)(?![a-ząćęłńóśźż])|\bseks|\bporno|\berotycz|\brozebran|\bbieli[źz]n|\bpiersi(?![a-ząćęłńóśźż])|\bzmys[łl]ow[yaei]/i;
const zakazane = (opis) => NIELETNI.test(opis) && SEKS.test(opis);

// ---------- odczyt ciala zapytania ----------
// Nasze trasy sa rejestrowane przed parserem aplikacji, wiec czytamy strumien sami.
// Za duze cialo dalej odbieramy (do 1 MB), zeby klient dostal odpowiedz, a nie zerwane polaczenie.
const MAX_CIALO = 20000;
function cialo(req) {
  if (req.body && typeof req.body === 'object' && Object.keys(req.body).length) return Promise.resolve(req.body);
  return new Promise((gotowe) => {
    const kawalki = [];
    let dlugosc = 0;
    req.on('data', (c) => {
      dlugosc += c.length;
      if (dlugosc <= MAX_CIALO) kawalki.push(c);
      else if (dlugosc > 1024 * 1024) req.destroy();
    });
    req.on('end', () => {
      if (dlugosc > MAX_CIALO) return gotowe({ zaduzo: true });
      try { gotowe(JSON.parse(Buffer.concat(kawalki).toString('utf8') || '{}')); } catch { gotowe({}); }
    });
    req.on('close', () => gotowe({ zaduzo: dlugosc > MAX_CIALO }));
    req.on('error', () => gotowe({}));
  });
}

const odpowiedz = (res, kod, tresc) => {
  res.status(kod).set('Content-Type', 'application/json; charset=utf-8').send(JSON.stringify(tresc));
};
const bladKlienta = (res, [kod, error, message]) => odpowiedz(res, kod, { error, message });

// ---------- trasy ----------
function zarejestruj(app) {
  app.get('/v1/images/quota', async (req, res) => {
    try {
      // Bez klucza dostawcy nie ma czego oferowac — aplikacja po 404 chowa obrazy.
      if (!KLUCZ || LIMIT === 0) return odpowiedz(res, 404, { error: 'not_configured', message: 'Obrazy są wyłączone.' });
      const kto = await ktoPyta(req);
      if (kto.blad) return bladKlienta(res, kto.blad);
      await przygotujTabele();
      const d = dzien();
      const used = Math.min(LIMIT, await zuzyte(kto.id, d));
      return odpowiedz(res, 200, { limit: LIMIT, used, left: LIMIT - used, resets_on: jutro(d) });
    } catch (e) {
      console.error('[obrazy] /v1/images/quota', e && e.message);
      return odpowiedz(res, 500, { error: 'server_error', message: 'Nie udało się sprawdzić limitu obrazów.' });
    }
  });

  app.post('/v1/images/generations', async (req, res) => {
    let kto = null;
    let d = null;
    let zajete = false;
    try {
      if (!KLUCZ || LIMIT === 0) return odpowiedz(res, 404, { error: 'not_configured', message: 'Obrazy są wyłączone.' });
      const c = await cialo(req);
      if (c.zaduzo) return odpowiedz(res, 400, { error: 'bad_request', message: 'Opis obrazu jest za długi.' });
      const opis = String(c.prompt || '').replace(/\s+/g, ' ').trim().slice(0, 1500);
      const proporcje = ['1:1', '16:9', '9:16'].includes(c.aspect) ? c.aspect : '1:1';
      if (opis.length < 3) return odpowiedz(res, 400, { error: 'bad_request', message: 'Brak opisu obrazu.' });

      kto = await ktoPyta(req);
      if (kto.blad) return bladKlienta(res, kto.blad);
      if (zakazane(opis)) {
        return odpowiedz(res, 400, { error: 'image_refused', message: 'Tego obrazu nie stworzę.' });
      }

      await przygotujTabele();
      d = dzien();
      zajete = await zarezerwuj(kto.id, d);
      if (!zajete) {
        return odpowiedz(res, 429, {
          error: 'image_limit',
          message: `Na dziś wykorzystano limit obrazów (${LIMIT}). Kolejne od jutra.`,
          limit: LIMIT, used: LIMIT, left: 0, resets_on: jutro(d),
        });
      }
      const obraz = await stworz(opis, proporcje);
      const used = Math.min(LIMIT, await zuzyte(kto.id, d));
      return odpowiedz(res, 200, { images: [obraz], limit: LIMIT, used, left: LIMIT - used });
    } catch (e) {
      console.error('[obrazy] /v1/images/generations', e && e.message);
      // Nieudany obraz nie zjada limitu.
      if (zajete && kto && kto.id && d) { try { await oddaj(kto.id, d); } catch {} }
      if (e && e.rodzaj === 'zajety') {
        return odpowiedz(res, 503, { error: 'image_busy', message: 'Tworzenie obrazów jest chwilowo zajęte. Spróbuj za minutę.' });
      }
      if (e && e.rodzaj === 'odmowa') {
        return odpowiedz(res, 400, { error: 'image_refused', message: 'Tego obrazu nie stworzę.' });
      }
      return odpowiedz(res, 502, { error: 'image_failed', message: 'Nie udało się stworzyć obrazu. Spróbuj ponownie.' });
    }
  });
}

// ---------- wieksze zapytania czatu ----------
// Parser JSON aplikacji domyslnie przyjmuje 100 KB — jedno zdjecie w czacie jest wieksze.
// Podnosimy limit kazdego parsera, ktory aplikacja utworzy (express.json i body-parser),
// nigdy go nie obnizajac.
function bajty(v) {
  if (typeof v === 'number') return v;
  const m = /^(\d+(?:\.\d+)?)\s*(b|kb|mb|gb)?$/i.exec(String(v || '').trim());
  if (!m) return 100 * 1024;
  const x = { b: 1, kb: 1024, mb: 1024 * 1024, gb: 1024 * 1024 * 1024 }[(m[2] || 'b').toLowerCase()];
  return Math.floor(Number(m[1]) * x);
}
function podnies(opcje) {
  const o = Object.assign({}, opcje || {});
  if (bajty(o.limit != null ? o.limit : '100kb') < bajty(LIMIT_JSON)) o.limit = LIMIT_JSON;
  return o;
}

// ---------- wpiecie sie w Express ----------
try {
  const sciezka = require.resolve(path.join(KATALOG, 'node_modules', 'express'));
  const oryginal = require(sciezka);
  if (!oryginal.__obrazyWpiete) {
    const opakowany = function (...a) {
      const app = oryginal(...a);
      aplikacje.push(app);
      const listen = app.listen;
      if (typeof listen === 'function') {
        app.listen = function (...b) { if (!glowna) glowna = app; return listen.apply(this, b); };
      }
      try { zarejestruj(app); } catch (e) { console.error('[obrazy] rejestracja', e && e.message); }
      return app;
    };
    Object.assign(opakowany, oryginal);
    if (typeof oryginal.json === 'function') {
      const json = oryginal.json;
      opakowany.json = function (opcje) { return json.call(this, podnies(opcje)); };
    }
    opakowany.__obrazyWpiete = true;
    require.cache[sciezka].exports = opakowany;
  }
} catch (e) {
  console.error('[obrazy] nie udalo sie wpiac w express:', e && e.message);
}

try {
  const sciezka = require.resolve(path.join(KATALOG, 'node_modules', 'body-parser'));
  const bp = require(sciezka);
  if (!bp.__obrazyWpiete) {
    const json = bp.json;
    Object.defineProperty(bp, 'json', {
      configurable: true, enumerable: true, value: function (opcje) { return json.call(this, podnies(opcje)); },
    });
    Object.defineProperty(bp, '__obrazyWpiete', { value: true });
  }
} catch {}

/** Stan modulu dla instalatora — bez wartosci kluczy. */
function stan() {
  return {
    klucz: ZRODLO_KLUCZA || null,
    model: MODEL,
    limit: LIMIT,
    strefa: STREFA,
    dzis: dzien(),
    api: BAZA_API,
    limitJson: LIMIT_JSON,
    aplikacje: aplikacje.length,
  };
}

module.exports = { zarejestruj, wybierzRozmiar, zakazane, dzien, bajty, podnies, stan, rozmiary, stworz, przygotujTabele, baza };
