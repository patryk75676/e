// Jezyk aplikacji po adresie IP dla cyphr-api: polski dla adresow z Polski, angielski dla reszty.
//
//   GET /v1/geo -> 200 {lang: "pl"|"en", country: "PL"|null, ip: "83.1.2.3"}
//                  503 {error: "geo_unavailable"}  gdy nie wiadomo (brak danych albo adresu klienta)
//
// Bez logowania — aplikacja pyta jeszcze przed ekranem logowania. Modul nie czyta .env ani bazy
// i nigdzie nie zapisuje adresow; `ip` w odpowiedzi to adres samego pytajacego.
//
// Polskie adresy: oficjalne statystyki RIPE NCC (rejestr adresow IP dla Europy) — zakresy IPv4
// i IPv6 przydzielone polskim sieciom. Plik jezyk-pl.json lezy obok modulu i odswieza sie sam
// co 30 dni; gdy RIPE nie odpowie, zostaje poprzedni.
//
// Adres klienta: pierwszy publiczny z X-Forwarded-For (tak podaje go serwer WWW przed aplikacja),
// potem X-Real-IP i adres polaczenia. Kto sam dopisze sobie naglowek, zmieni tylko wlasny jezyk.
'use strict';

const fs = require('fs');
const path = require('path');
const net = require('net');

const KATALOG = __dirname;
const PLIK = path.join(KATALOG, 'jezyk-pl.json');
const RIPE = process.env.JEZYK_RIPE_URL || 'https://ftp.ripe.net/pub/stats/ripencc/delegated-ripencc-latest';
const ODSWIEZ_CO = 30 * 24 * 3600e3;
// Polska ma ok. 20 mln adresow IPv4 — mniej niz 5 mln znaczy uciety albo zly plik.
const MIN_V4 = 5e6;
const MIN_V6 = 100;

// ---------- adresy ----------
function v4(t) {
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(t);
  if (!m) return null;
  const b = m.slice(1).map(Number);
  if (b.some((x) => x > 255)) return null;
  return ((b[0] << 24) >>> 0) + (b[1] << 16) + (b[2] << 8) + b[3];
}

function v6(t) {
  let s = String(t).split('%')[0];
  if (!net.isIPv6(s)) return null;
  // Osadzony IPv4 na koncu (::ffff:1.2.3.4) — na dwie grupy szesnastkowe.
  const m = /(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})$/.exec(s);
  if (m) {
    const x = v4(m[1]);
    if (x == null) return null;
    s = s.slice(0, -m[1].length) + (x >>> 16).toString(16) + ':' + (x & 0xffff).toString(16);
  }
  const dwa = s.indexOf('::');
  const glowa = (dwa < 0 ? s : s.slice(0, dwa)).split(':').filter((x) => x !== '');
  const ogon = dwa < 0 ? [] : s.slice(dwa + 2).split(':').filter((x) => x !== '');
  const czesci = dwa < 0 ? glowa : [...glowa, ...Array(8 - glowa.length - ogon.length).fill('0'), ...ogon];
  if (czesci.length !== 8) return null;
  let n = 0n;
  for (const c of czesci) n = (n << 16n) | BigInt(parseInt(c, 16));
  return n;
}

const tekstV4 = (n) => [n >>> 24, (n >>> 16) & 255, (n >>> 8) & 255, n & 255].join('.');
function tekstV6(n) {
  const g = [];
  for (let i = 7; i >= 0; i--) g.push(Number((n >> BigInt(i * 16)) & 0xffffn));
  // Najdluzszy ciag co najmniej dwoch zerowych grup skraca sie do "::" (RFC 5952).
  let od = -1;
  let dl = 0;
  for (let i = 0; i < 8;) {
    if (g[i] !== 0) { i += 1; continue; }
    let j = i;
    while (j < 8 && g[j] === 0) j += 1;
    if (j - i > dl) { od = i; dl = j - i; }
    i = j;
  }
  const h = g.map((x) => x.toString(16));
  if (dl < 2) return h.join(':');
  return h.slice(0, od).join(':') + '::' + h.slice(od + dl).join(':');
}

/** Adres z naglowka albo polaczenia: {v: 4|6, n, tekst} albo null. IPv4 w IPv6 staje sie IPv4. */
function adres(t) {
  let s = String(t || '').trim();
  if (!s) return null;
  // [2001:db8::1]:443 i 1.2.3.4:5678 — bez portu.
  const nawias = /^\[([^\]]+)\](?::\d+)?$/.exec(s);
  if (nawias) s = nawias[1];
  const port = /^(\d{1,3}(?:\.\d{1,3}){3}):\d+$/.exec(s);
  if (port) s = port[1];
  const a4 = v4(s);
  if (a4 != null) return { v: 4, n: a4, tekst: tekstV4(a4) };
  const a6 = v6(s);
  if (a6 == null) return null;
  if (a6 >> 32n === 0xffffn) {
    const n = Number(a6 & 0xffffffffn);
    return { v: 4, n, tekst: tekstV4(n) };
  }
  return { v: 6, n: a6, tekst: tekstV6(a6) };
}

const W4 = (a, b, c, d) => ((a << 24) >>> 0) + (b << 16) + (c << 8) + d;
const NIEPUBLICZNE_V4 = [
  [W4(0, 0, 0, 0), 8], [W4(10, 0, 0, 0), 8], [W4(100, 64, 0, 0), 10], [W4(127, 0, 0, 0), 8],
  [W4(169, 254, 0, 0), 16], [W4(172, 16, 0, 0), 12], [W4(192, 0, 0, 0), 24], [W4(192, 0, 2, 0), 24],
  [W4(192, 168, 0, 0), 16], [W4(198, 18, 0, 0), 15], [W4(198, 51, 100, 0), 24], [W4(203, 0, 113, 0), 24],
  [W4(224, 0, 0, 0), 3],
];

function publiczny(a) {
  if (!a) return false;
  if (a.v === 4) {
    return !NIEPUBLICZNE_V4.some(([s, d]) => {
      const maska = d === 0 ? 0 : (~0 << (32 - d)) >>> 0;
      return ((a.n & maska) >>> 0) === s;
    });
  }
  // IPv6: tylko globalne 2000::/3, bez przykladowych 2001:db8::/32.
  return (a.n >> 125n) === 1n && (a.n >> 96n) !== 0x20010db8n;
}

/** Adres pytajacego: pierwszy publiczny z X-Forwarded-For, potem X-Real-IP i polaczenie. */
function adresKlienta(req) {
  const kandydaci = String(req.headers['x-forwarded-for'] || '').split(',');
  kandydaci.push(req.headers['x-real-ip'], req.socket && req.socket.remoteAddress);
  for (const k of kandydaci) {
    const a = adres(k);
    if (publiczny(a)) return a;
  }
  return null;
}

// ---------- dane o polskich adresach ----------
function scal(zakresy, jeden) {
  zakresy.sort((x, y) => (x[0] < y[0] ? -1 : x[0] > y[0] ? 1 : 0));
  const w = [];
  for (const z of zakresy) {
    const o = w[w.length - 1];
    if (o && z[0] <= o[1] + jeden) { if (z[1] > o[1]) o[1] = z[1]; } else w.push([z[0], z[1]]);
  }
  return w;
}

/** Zakresy PL ze statystyk RIPE (format delegated-ripencc). */
function przetworz(tekst) {
  const z4 = [];
  const z6 = [];
  let data = null;
  for (const l of String(tekst).split('\n')) {
    const p = l.trim().split('|');
    if (!data && p[0] === '2' && p[1] === 'ripencc' && /^\d{8}$/.test(p[5] || '')) data = p[5];
    if (p.length < 7 || p[0] !== 'ripencc' || p[1] !== 'PL') continue;
    if (p[6] !== 'allocated' && p[6] !== 'assigned') continue;
    if (p[2] === 'ipv4') {
      const s = v4(p[3]);
      const ile = Number(p[4]);
      if (s == null || !(ile > 0) || s + ile - 1 > 0xffffffff) continue;
      z4.push([s, s + ile - 1]);
    } else if (p[2] === 'ipv6') {
      const s = v6(p[3]);
      const dl = Number(p[4]);
      if (s == null || !(dl >= 1 && dl <= 128)) continue;
      z6.push([s, s + (1n << BigInt(128 - dl)) - 1n]);
    }
  }
  return { data, v4: scal(z4, 1), v6: scal(z6, 1n) };
}

function sprawdz(d) {
  const ile = d.v4.reduce((s, [a, b]) => s + (b - a + 1), 0);
  if (ile < MIN_V4 || d.v6.length < MIN_V6) {
    throw new Error(`podejrzane dane RIPE: ${ile} adresow IPv4, ${d.v6.length} zakresow IPv6`);
  }
  return ile;
}

const hex = (n) => n.toString(16).padStart(32, '0');
const doPliku = (d) => ({
  zrodlo: 'RIPE NCC delegated-ripencc-latest',
  data: d.data,
  pobrane: new Date().toISOString(),
  v4: d.v4,
  v6: d.v6.map(([a, b]) => [hex(a), hex(b)]),
});
const zPliku = (j) => ({
  data: j.data || null,
  pobrane: j.pobrane || null,
  v4: (j.v4 || []).map(([a, b]) => [Number(a), Number(b)]),
  v6: (j.v6 || []).map(([a, b]) => [BigInt('0x' + a), BigInt('0x' + b)]),
});

let dane = null;
let blad = null;

function wczytaj() {
  try {
    const d = zPliku(JSON.parse(fs.readFileSync(PLIK, 'utf8')));
    sprawdz(d);
    dane = d;
  } catch (e) {
    if (e.code !== 'ENOENT') blad = 'jezyk-pl.json: ' + e.message;
  }
  return dane;
}

let odswiezanie = null;
/** Pobiera swieze dane z RIPE i zapisuje je obok modulu. Zly albo ucięty plik nie zastapi dobrego. */
function odswiez() {
  if (odswiezanie) return odswiezanie;
  odswiezanie = (async () => {
    const r = await fetch(RIPE, { signal: AbortSignal.timeout(180000) });
    if (!r.ok) throw new Error('RIPE HTTP ' + r.status);
    const d = przetworz(await r.text());
    sprawdz(d);
    const tmp = PLIK + '.tmp-' + process.pid;
    fs.writeFileSync(tmp, JSON.stringify(doPliku(d)));
    fs.renameSync(tmp, PLIK);
    d.pobrane = new Date().toISOString();
    dane = d;
    blad = null;
    return d;
  })().finally(() => { odswiezanie = null; });
  return odswiezanie;
}

function stare() {
  if (!dane) return true;
  const t = Date.parse(dane.pobrane || '');
  return !Number.isFinite(t) || Date.now() - t > ODSWIEZ_CO;
}

// Nieudana proba nie powtarza sie czesciej niz co godzine — RIPE nie jest do zasypywania.
let ostatniaProba = 0;
function wTle() {
  if (!stare() || odswiezanie || Date.now() - ostatniaProba < 3600e3) return;
  ostatniaProba = Date.now();
  odswiez().catch((e) => {
    blad = 'odswiezanie: ' + (e && e.message);
    console.error('[jezyk] nie udalo sie odswiezyc danych RIPE:', e && e.message);
  });
}

function w(zakresy, n) {
  let lo = 0;
  let hi = zakresy.length - 1;
  while (lo <= hi) {
    const m = (lo + hi) >> 1;
    if (n < zakresy[m][0]) hi = m - 1;
    else if (n > zakresy[m][1]) lo = m + 1;
    else return true;
  }
  return false;
}

/** Czy adres jest polski. null — brak danych. */
function polski(a) {
  const x = typeof a === 'string' ? adres(a) : a;
  if (!dane || !x) return null;
  return w(x.v === 4 ? dane.v4 : dane.v6, x.n);
}

// ---------- ograniczenie zapytan ----------
// Odpowiedz jest tania, ale bez limitu kazdy moglby zasypywac serwer. 30 na minute z adresu.
const LIMIT = 30;
const licznik = new Map();
function zaDuzo(klucz) {
  const t = Date.now();
  let x = licznik.get(klucz);
  if (!x || t - x.od > 60000) { x = { n: 0, od: t }; licznik.set(klucz, x); }
  x.n += 1;
  return x.n > LIMIT;
}
setInterval(() => {
  const t = Date.now();
  for (const [k, x] of licznik) if (t - x.od > 60000) licznik.delete(k);
  wTle();
}, 60000).unref();

// ---------- trasa ----------
function odpowiedz(res, kod, tresc) {
  res.statusCode = kod;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.end(JSON.stringify(tresc));
}

function zarejestruj(app) {
  app.get('/v1/geo', (req, res) => {
    try {
      const a = adresKlienta(req);
      if (zaDuzo(a ? a.tekst : '?')) return odpowiedz(res, 429, { error: 'rate_limited' });
      wTle();
      if (!a) return odpowiedz(res, 503, { error: 'geo_unavailable', message: 'no client address' });
      const pl = polski(a);
      if (pl == null) return odpowiedz(res, 503, { error: 'geo_unavailable', message: 'no data' });
      return odpowiedz(res, 200, { lang: pl ? 'pl' : 'en', country: pl ? 'PL' : null, ip: a.tekst });
    } catch (e) {
      console.error('[jezyk] /v1/geo', e && e.message);
      return odpowiedz(res, 503, { error: 'geo_unavailable' });
    }
  });
}

// ---------- wpiecie sie w Express ----------
// Jak obrazy.js: trasa rejestruje sie od razu po utworzeniu aplikacji, przed jej warstwami.
const aplikacje = [];
try {
  const sciezka = require.resolve(path.join(KATALOG, 'node_modules', 'express'));
  const oryginal = require(sciezka);
  if (!oryginal.__jezykWpiety) {
    const opakowany = function (...a) {
      const app = oryginal(...a);
      aplikacje.push(app);
      try { zarejestruj(app); } catch (e) { console.error('[jezyk] rejestracja', e && e.message); }
      return app;
    };
    Object.assign(opakowany, oryginal);
    Object.defineProperty(opakowany, '__jezykWpiety', { value: true });
    require.cache[sciezka].exports = opakowany;
  }
} catch (e) {
  console.error('[jezyk] nie udalo sie wpiac w express:', e && e.message);
}

wczytaj();
// Swieze dane pobieramy w tle, chwile po starcie — start aplikacji nie czeka na RIPE.
if (stare()) setTimeout(wTle, 30000).unref();

/** Stan modulu dla instalatora. */
function stan() {
  return {
    plik: fs.existsSync(PLIK),
    data: dane && dane.data,
    pobrane: dane && dane.pobrane,
    v4: dane ? dane.v4.length : 0,
    adresyV4: dane ? dane.v4.reduce((s, [a, b]) => s + (b - a + 1), 0) : 0,
    v6: dane ? dane.v6.length : 0,
    stare: stare(),
    blad,
    aplikacje: aplikacje.length,
  };
}

module.exports = { zarejestruj, adres, adresKlienta, publiczny, polski, przetworz, sprawdz, odswiez, wczytaj, stan, doPliku, PLIK };
