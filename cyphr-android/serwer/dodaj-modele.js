// Dopisuje nowe modele bez cenzury do listy dozwolonej w cyphr-api.
// Najpierw sprawdza kazdy model kluczem z .env — do bazy trafia tylko to,
// co naprawde odpowiedzialo. Uruchom: cd ~/cyphr-api && node dodaj-modele.js
'use strict';
const fs = require('fs'), path = require('path'), os = require('os');

const APP = process.cwd();
const NOWE = [
  'abliterated-model-large-v2',
  'qwen3.5-27b-claude-4.6-opus-reasoning-distilled-derestricted',
  'gemma-4-31b-sdft-heretic-rp',
];
const NAZWY = {
  'abliterated-model-large-v2': 'Abliterated Model Large V2',
  'qwen3.5-27b-claude-4.6-opus-reasoning-distilled-derestricted': 'Qwen3.5 27B Opus Distilled Derestricted',
  'gemma-4-31b-sdft-heretic-rp': 'Gemma 4 31B Heretic RP',
  // Te dwa juz sa, ale zapisane skrocona nazwa — tak samo nazywaja sie wersje
  // ocenzurowane, wiec prostujemy przy okazji.
  'glm-5.3-flash-uncensored': 'GLM 5.3 Flash Uncensored',
  'qwen3.8-27b-uncensored': 'Qwen3.8 27B Uncensored',
};
const WZORZEC = 'glm-5.3-flash-uncensored'; // po nim poznajemy, jak liczone sa ceny

// ---------- .env ----------
const env = {};
for (const linia of fs.readFileSync(path.join(APP, '.env'), 'utf8').split('\n')) {
  const m = linia.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
  if (m) env[m[1]] = m[2].replace(/^["']|["']$/g, '');
}
const we = (...n) => { for (const x of n) if (env[x]) return env[x]; };

const klucz = we('ROUTEWAY_API_KEY', 'ROUTEWAY_KEY', 'UPSTREAM_API_KEY', 'PROVIDER_API_KEY', 'OPENAI_API_KEY', 'API_KEY')
  || Object.entries(env).filter(([k, v]) => /KEY|TOKEN/i.test(k) && /^[A-Za-z0-9._-]{20,}$/.test(v)).map(([, v]) => v)[0];
const baza = (we('ROUTEWAY_BASE_URL', 'UPSTREAM_BASE_URL', 'OPENAI_BASE_URL') || 'https://api.routeway.ai/v1').replace(/\/+$/, '');
if (!klucz) { console.error('Nie znalazlem klucza API w .env — przerywam.'); process.exit(1); }

// ---------- baza danych ----------
let mysql;
try { mysql = require(path.join(APP, 'node_modules', 'mysql2', 'promise')); }
catch { mysql = require('mysql2/promise'); }

const cfg = {
  host: we('DB_HOST', 'MYSQL_HOST', 'DATABASE_HOST') || 'localhost',
  port: Number(we('DB_PORT', 'MYSQL_PORT') || 3306),
  user: we('DB_USER', 'MYSQL_USER', 'DATABASE_USER'),
  password: we('DB_PASSWORD', 'DB_PASS', 'MYSQL_PASSWORD', 'DATABASE_PASSWORD') || '',
  database: we('DB_NAME', 'DB_DATABASE', 'MYSQL_DATABASE', 'DATABASE_NAME'),
};
if (!cfg.user || !cfg.database) { console.error('Brak danych bazy w .env — przerywam.'); process.exit(1); }

const licz = (v) => (v == null ? null : Number(v));

(async () => {
  // 1. cennik dostawcy
  const kat = await (await fetch(baza + '/models')).json();
  const uDostawcy = new Map((kat.data || []).map((m) => [m.id, m]));

  // 2. sprawdzenie kazdego modelu prawdziwym zapytaniem
  const zdaly = [];
  for (const id of NOWE) {
    if (!uDostawcy.has(id)) { console.log(`POMIJAM  ${id} — nie ma go w katalogu dostawcy`); continue; }
    let r;
    try {
      r = await fetch(baza + '/chat/completions', {
        method: 'POST',
        headers: { Authorization: 'Bearer ' + klucz, 'Content-Type': 'application/json' },
        body: JSON.stringify({ model: id, messages: [{ role: 'user', content: 'Napisz: ok' }], max_tokens: 20, temperature: 0.7 }),
      });
    } catch (e) { console.log(`ODPADA   ${id} — ${e.message}`); continue; }
    const tresc = await r.text();
    if (!r.ok) { console.log(`ODPADA   ${id} — HTTP ${r.status} ${tresc.slice(0, 140)}`); continue; }
    let odp = '';
    try { const j = JSON.parse(tresc); odp = j.choices?.[0]?.message?.content || j.choices?.[0]?.message?.reasoning || ''; } catch {}
    if (!odp.trim()) { console.log(`ODPADA   ${id} — pusta odpowiedz`); continue; }
    console.log(`DZIALA   ${id} — "${odp.trim().slice(0, 40)}"`);
    zdaly.push(id);
  }
  if (!zdaly.length) { console.log('\nZaden model nie przeszedl testu. Nic nie zmieniam.'); process.exit(1); }

  // 3. baza: jakie kolumny i jak liczone sa ceny
  const db = await mysql.createConnection(cfg);
  const [kol] = await db.query('SHOW COLUMNS FROM models');
  const nazwyKol = kol.map((k) => k.Field);
  const kolWe = nazwyKol.find((k) => /in(put)?.*(usd|price|per_m|cost)|price.*in/i.test(k));
  const kolWy = nazwyKol.find((k) => /out(put)?.*(usd|price|per_m|cost)|price.*out/i.test(k));
  const kolNazwa = nazwyKol.find((k) => /^(name|label|title|display_name)$/i.test(k));
  const kolId = nazwyKol.find((k) => /^(id|model_id|slug)$/i.test(k)) || 'id';

  let mnoznik = 1;
  if (kolWe) {
    const [w] = await db.query(`SELECT * FROM models WHERE ${kolId} = ?`, [WZORZEC]);
    const wzor = uDostawcy.get(WZORZEC)?.pricing?.input?.price_per_million_t;
    if (w[0] && wzor) {
      mnoznik = Number(w[0][kolWe]) / Number(wzor);
      if (!isFinite(mnoznik) || mnoznik <= 0) mnoznik = 1;
    }
  }
  console.log(`\nKolumny: ${nazwyKol.join(', ')}`);
  console.log(`Narzut na cenie liczony jak w istniejacym wierszu: x${mnoznik.toFixed(2)}`);

  // 4. wpis
  for (const id of zdaly) {
    const p = uDostawcy.get(id).pricing || {};
    const dane = { [kolId]: id };
    if (kolNazwa) dane[kolNazwa] = NAZWY[id] || uDostawcy.get(id).name || id;
    if (kolWe) dane[kolWe] = +(licz(p.input?.price_per_million_t) * mnoznik).toFixed(6);
    if (kolWy) dane[kolWy] = +(licz(p.output?.price_per_million_t) * mnoznik).toFixed(6);
    for (const k of kol) {
      if (dane[k.Field] !== undefined) continue;
      if (/^(enabled|active|is_active|visible)$/i.test(k.Field)) dane[k.Field] = 1;
      else if (/context|ctx/i.test(k.Field)) dane[k.Field] = uDostawcy.get(id).context_length || null;
      else if (k.Null === 'NO' && k.Default === null && !/auto_increment/i.test(k.Extra || '')) {
        // Kolumna wymagana, o ktorej nic nie wiem — bez tego INSERT by sie wywrocil.
        if (/provider|dostawca|owner/i.test(k.Field)) dane[k.Field] = 'routeway';
        else if (/^(int|bigint|decimal|float|double|tinyint)/i.test(k.Type)) dane[k.Field] = 0;
        else if (/^(datetime|timestamp)/i.test(k.Type)) dane[k.Field] = new Date();
        else dane[k.Field] = '';
        console.log(`   (wymagana kolumna ${k.Field} wypelniona wartoscia ${JSON.stringify(dane[k.Field])})`);
      }
    }
    const pola = Object.keys(dane);
    const znaki = pola.map(() => '?').join(', ');
    const nadpisz = pola.filter((f) => f !== kolId).map((f) => `\`${f}\` = VALUES(\`${f}\`)`).join(', ');
    await db.query(
      `INSERT INTO models (${pola.map((f) => '`' + f + '`').join(', ')}) VALUES (${znaki})` +
      (nadpisz ? ` ON DUPLICATE KEY UPDATE ${nadpisz}` : ''),
      pola.map((f) => dane[f]),
    );
    console.log(`DODANO   ${id}  ${kolWe ? dane[kolWe] : '?'} / ${kolWy ? dane[kolWy] : '?'} $ za mln`);
  }

  // Prostujemy skrocone nazwy tego, co juz bylo na liscie.
  if (kolNazwa) {
    for (const [id, nazwa] of Object.entries(NAZWY)) {
      const [w] = await db.query(`UPDATE models SET \`${kolNazwa}\` = ? WHERE ${kolId} = ? AND \`${kolNazwa}\` <> ?`, [nazwa, id, nazwa]);
      if (w.affectedRows) console.log(`NAZWA    ${id} -> ${nazwa}`);
    }
  }

  const [lista] = await db.query('SELECT * FROM models');
  console.log('\nLista modeli po zmianie:');
  for (const w of lista) console.log('  ' + (w[kolId] ?? '') + '   ' + (kolNazwa ? w[kolNazwa] : ''));
  await db.end();
  console.log('\nGotowe. Teraz przeladuj aplikacje: touch ~/cyphr-api/tmp/restart.txt');
})().catch((e) => { console.error('BLAD:', e.message); process.exit(1); });
