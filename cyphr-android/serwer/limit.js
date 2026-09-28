'use strict';
// CYPHR: luźniejszy limit zapytań dla czatu i obrazów, ciasny dla logowania.
//
// Problem: globalny limiter na IP w app.js jest bardzo ciasny, a agent w terminalu
// wysyła po kilka zapytań do modelu na jedną wiadomość (każde polecenie = osobne
// zapytanie), więc 429 „odczekaj 5 min” wyskakuje bez przerwy.
//
// Ten moduł nie zmienia app.js. Wpina się w fabrykę express (jak obrazy.js/jezyk.js)
// i owija middleware limitera: dla endpointów płatnych (rozliczanych z salda — czat,
// obrazy, /me itp.) używa własnego, luźniejszego limitera; dla logowania, rejestracji
// i resetu hasła zostaje pierwotny, ciasny limiter (to chroni przed łamaniem hasła).
//
// Gdy nie uda się wpiąć albo rozpoznać limitera — moduł nic nie psuje (no-op).

const path = require('path');
const KATALOG = __dirname;

// ---------- konfiguracja ----------
const OKNO_MS = 60 * 1000; // okno 1 minuty dla luźnego limitu
const LIMIT = 120;         // tyle zapytań/min z jednego IP dla czatu, obrazów itd.

// Endpointy z luźnym limitem: wszystko pod /v1/ (czat, obrazy, geo, modele) oraz
// kilka pojedynczych. Rozliczenie i tak idzie z salda konta, więc ciasny limit na IP
// jest tu zbędny, a psuje agenta.
const LUZNE_PREFIKS = /^\/v1\//;
const LUZNE_DOKLADNE = new Set(['/me', '/agents', '/profile', '/shop', '/models', '/v1/geo']);
// Nigdy nie luzujemy tych — to zabezpieczenie przed łamaniem hasła i spamem kodów.
const CIASNE = /^\/(auth|login|logowanie|register|rejestracja|signup|reset|forgot|haslo|password|verify|weryfik)/i;

function sciezkaZadania(req) {
  const raw = req.originalUrl || req.url || '';
  const q = raw.indexOf('?');
  return q === -1 ? raw : raw.slice(0, q);
}

function luzny(req) {
  const p = sciezkaZadania(req);
  if (CIASNE.test(p)) return false;
  return LUZNE_PREFIKS.test(p) || LUZNE_DOKLADNE.has(p);
}

// ---------- luźny limiter ----------
let luzy = null;
let powodBraku = null;
try {
  const rlSciezka = require.resolve(path.join(KATALOG, 'node_modules', 'express-rate-limit'));
  const rl = require(rlSciezka);
  const fabryka = typeof rl === 'function' ? rl : rl && rl.rateLimit;
  if (typeof fabryka !== 'function') throw new Error('express-rate-limit bez funkcji');
  luzy = fabryka({
    windowMs: OKNO_MS,
    max: LIMIT,
    standardHeaders: true,
    legacyHeaders: false,
    validate: false, // nie wywracaj startu na walidacji trust proxy — app.js już to ustawia
  });
} catch (e) {
  powodBraku = e && e.message;
  luzy = null; // bez własnego limitera po prostu przepuszczamy płatne endpointy (saldo je rozlicza)
}

// ---------- rozpoznanie i owinięcie limitera ----------
function toLimiter(fn) {
  return (
    typeof fn === 'function' &&
    (typeof fn.resetKey === 'function' || typeof fn.getKey === 'function' || fn.name === 'rateLimit')
  );
}

let owinietych = 0;
function owin(limiter) {
  const owinieta = function (req, res, next) {
    if (luzny(req)) {
      if (luzy) return luzy(req, res, next);
      return next();
    }
    return limiter(req, res, next); // logowanie itp. — pierwotny, ciasny limiter bez zmian
  };
  try { Object.assign(owinieta, limiter); } catch (e) {} // zachowaj resetKey/getKey itd.
  owinieta.__cyphrLimit = true;
  owinietych++;
  return owinieta;
}

// Metody rejestrujące middleware. `get` z jednym argumentem to getter ustawień
// (app.get('env')) — takiego nie ruszamy.
const METODY = ['use', 'all', 'post', 'put', 'patch', 'delete', 'get'];
function podepnij(cel) {
  METODY.forEach((m) => {
    const oryg = cel[m];
    if (typeof oryg !== 'function' || oryg.__cyphrOwiniete) return;
    const nowy = function (...args) {
      if (!(m === 'get' && args.length < 2)) {
        for (let i = 0; i < args.length; i++) {
          if (toLimiter(args[i]) && !args[i].__cyphrLimit) args[i] = owin(args[i]);
        }
      }
      return oryg.apply(this, args);
    };
    nowy.__cyphrOwiniete = true;
    try { cel[m] = nowy; } catch (e) {}
  });
}

// ---------- wpięcie w express ----------
const aplikacje = [];
let wpiete = false;
try {
  const sciezka = require.resolve(path.join(KATALOG, 'node_modules', 'express'));
  const oryginal = require(sciezka);
  if (!oryginal.__cyphrLimitWpiety) {
    const opakowany = function (...a) {
      const app = oryginal(...a);
      aplikacje.push(app);
      try { podepnij(app); } catch (e) { console.error('[limit] podepnij app', e && e.message); }
      return app;
    };
    Object.assign(opakowany, oryginal);
    // Routery też — limiter bywa wpinany na express.Router().
    if (typeof oryginal.Router === 'function') {
      const origRouter = oryginal.Router;
      const nowyRouter = function (...a) {
        const r = origRouter(...a);
        try { podepnij(r); } catch (e) { console.error('[limit] podepnij router', e && e.message); }
        return r;
      };
      Object.assign(nowyRouter, origRouter);
      opakowany.Router = nowyRouter;
    }
    Object.defineProperty(opakowany, '__cyphrLimitWpiety', { value: true });
    require.cache[sciezka].exports = opakowany;
    wpiete = true;
  } else {
    wpiete = true;
  }
} catch (e) {
  console.error('[limit] nie udalo sie wpiac w express:', e && e.message);
}

function stan() {
  return {
    wpiete,
    luzyDostepny: !!luzy,
    powodBraku,
    oknoMs: OKNO_MS,
    limit: LIMIT,
    owinietych,
    aplikacje: aplikacje.length,
  };
}

// Marker dla instalatora i diagnostyki.
try { global.__cyphrLimit = stan; } catch (e) {}

module.exports = { podepnij, owin, toLimiter, luzny, sciezkaZadania, stan, OKNO_MS, LIMIT };
