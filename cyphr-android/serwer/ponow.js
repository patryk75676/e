'use strict';
// CYPHR: automatyczne ponawianie zapytań do dostawcy modeli (Routeway) przy chwilowym 429/5xx.
//
// Czat leci przez proxy.js: fetch(ROUTEWAY_BASE_URL + '/chat/completions'). Gdy Routeway
// przytnie zapytanie (429 „za szybko”) albo chwilowo padnie (502/503/504), proxy przekazuje
// to użytkownikowi jako błąd. Agent w terminalu wysyła po kilka zapytań pod rząd, więc te
// przelotne 429 sypią się bez przerwy.
//
// Ten moduł owija globalny fetch: dla zapytań do Routeway /chat/completions przy 429/5xx
// odczekuje ułamek sekundy i próbuje jeszcze raz (krótki backoff, twardy limit prób i czasu).
// Wszystkie inne zapytania i każdą udaną odpowiedź (200 — w tym strumień SSE) przepuszcza bez
// zmian. Nie zmienia proxy.js ani nie dubluje rozliczenia (rezerwacja salda jest raz, w proxy).

const STATUS_PONOW = new Set([429, 500, 502, 503, 504]);
const MAX_PROB = Number(process.env.CYPHR_PONOW_PROB || 3);   // tyle dodatkowych prób
const BAZA_MS = Number(process.env.CYPHR_PONOW_BAZA_MS || 700); // 0.7s, 1.4s, 2.8s...
const MAX_CZEKAJ_MS = Number(process.env.CYPHR_PONOW_MAX_MS || 4000); // nie czekaj dłużej niż tyle na próbę

function spij(ms, signal) {
  return new Promise((resolve) => {
    if (ms <= 0) return resolve();
    let t = null;
    const koniec = () => { if (t) clearTimeout(t); if (signal) signal.removeEventListener('abort', koniec); resolve(); };
    t = setTimeout(koniec, ms);
    if (signal) { if (signal.aborted) return koniec(); signal.addEventListener('abort', koniec, { once: true }); }
  });
}

function doRouteway(url) {
  const u = String(url || '');
  const baza = process.env.ROUTEWAY_BASE_URL;
  if (baza && u.startsWith(baza)) return true;
  return /\/chat\/completions(\?|$)/.test(u); // zapasowo: sam endpoint czatu
}

let wpiete = false;
let ponowien = 0;
const oryginalny = globalThis.fetch;
if (typeof oryginalny === 'function' && !oryginalny.__cyphrPonow) {
  const owiniety = async function (url, opts) {
    // Adres bierzemy też z obiektu Request/URL.
    const adres = typeof url === 'string' ? url : (url && (url.url || url.href)) || '';
    if (!doRouteway(adres)) return oryginalny(url, opts);
    const signal = opts && opts.signal;
    let res;
    for (let i = 0; ; i++) {
      try {
        res = await oryginalny(url, opts);
      } catch (e) {
        // Błąd sieci — ponów, chyba że klient się rozłączył albo koniec prób.
        if ((signal && signal.aborted) || i >= MAX_PROB) throw e;
        await spij(Math.min(BAZA_MS * 2 ** i, MAX_CZEKAJ_MS), signal);
        continue;
      }
      // Sukces albo błąd, którego nie ponawiamy, albo koniec prób — oddajemy jak jest.
      if (!STATUS_PONOW.has(res.status) || i >= MAX_PROB) return res;
      if (signal && signal.aborted) return res;
      // Odczekaj krótko (Retry-After tylko jeśli krótkie), potem ponów. Ciało 429 domykamy,
      // żeby nie zawiesić połączenia — ostatniej próby nie ruszamy (proxy odczyta jej treść).
      let czekaj = Math.min(BAZA_MS * 2 ** i, MAX_CZEKAJ_MS);
      const ra = Number(res.headers && res.headers.get && res.headers.get('retry-after'));
      if (ra > 0) czekaj = Math.min(ra * 1000, MAX_CZEKAJ_MS);
      try { if (res.body && res.body.cancel) await res.body.cancel(); else await res.arrayBuffer(); } catch (_) {}
      ponowien++;
      await spij(czekaj, signal);
    }
  };
  owiniety.__cyphrPonow = true;
  globalThis.fetch = owiniety;
  wpiete = true;
} else if (oryginalny && oryginalny.__cyphrPonow) {
  wpiete = true;
}

function stan() {
  return { wpiete, ponowien, maxProb: MAX_PROB, bazaMs: BAZA_MS, maxCzekajMs: MAX_CZEKAJ_MS, fetch: typeof globalThis.fetch };
}
try { global.__cyphrPonow = stan; } catch (_) {}

module.exports = { stan, doRouteway, spij };
