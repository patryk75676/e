// Tylko czyta i wypisuje. Nic nie zmienia, nic nie wysyla.
// Uruchom:  cd ~/cyphr-api && node pokaz-serwer.js
'use strict';
const fs = require('fs'), path = require('path');
const APP = process.cwd();

const czytaj = (t) => {
  const o = {};
  for (const l of t.split('\n')) {
    const m = l.match(/^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (!m) continue;
    let v = m[2];
    if (/^'.*'$/s.test(v) || /^".*"$/s.test(v)) v = v.slice(1, -1);
    o[m[1]] = v;
  }
  return o;
};
const env = czytaj(fs.readFileSync(path.join(APP, '.env'), 'utf8'));
const we = (...n) => { for (const x of n) if (env[x]) return env[x]; };

console.log('=== 1. Pliki zrodlowe ===');
(function chodz(kat, gl, przedrostek) {
  if (gl > 2) return;
  let w = [];
  try { w = fs.readdirSync(kat, { withFileTypes: true }); } catch { return; }
  for (const e of w) {
    if (/^(node_modules|\.git|tmp|logs|public|uploads)$/.test(e.name)) continue;
    const p = path.join(kat, e.name);
    if (e.isDirectory()) { console.log(przedrostek + e.name + '/'); chodz(p, gl + 1, przedrostek + '  '); }
    else if (/\.(js|mjs|cjs|ts|json|sql)$/.test(e.name) && e.name !== 'package-lock.json') {
      let linie = 0;
      try { linie = fs.readFileSync(p, 'utf8').split('\n').length; } catch {}
      console.log(`${przedrostek}${e.name}  (${linie} linii)`);
    }
  }
})(APP, 0, '  ');

console.log('\n=== 2. Biblioteki ===');
try {
  const pj = JSON.parse(fs.readFileSync(path.join(APP, 'package.json'), 'utf8'));
  console.log('  main:', pj.main || '(brak)');
  console.log('  zaleznosci:', Object.keys(pj.dependencies || {}).join(', '));
} catch (e) { console.log('  brak package.json:', e.message); }

console.log('\n=== 3. Wszystkie trasy /auth ===');
(function szukaj(kat, gl) {
  if (gl > 2) return;
  let w = [];
  try { w = fs.readdirSync(kat, { withFileTypes: true }); } catch { return; }
  for (const e of w) {
    if (/^(node_modules|\.git|tmp|logs)$/.test(e.name)) continue;
    const p = path.join(kat, e.name);
    if (e.isDirectory()) szukaj(p, gl + 1);
    else if (/\.(js|mjs|cjs)$/.test(e.name)) {
      try {
        const t = fs.readFileSync(p, 'utf8');
        const tr = t.match(/\b(?:app|router|r)\.(get|post|put|patch|delete)\(\s*['"`][^'"`]*['"`]/g) || [];
        if (tr.length) {
          console.log(`  ${path.relative(APP, p)}:`);
          for (const x of tr) console.log('     ' + x.replace(/\s+/g, ' '));
        }
      } catch {}
    }
  }
})(APP, 0);

console.log('\n=== 4. Jak haszowane sa hasla (sama nazwa funkcji, bez hasel) ===');
(function szukaj(kat, gl) {
  if (gl > 2) return;
  let w = [];
  try { w = fs.readdirSync(kat, { withFileTypes: true }); } catch { return; }
  for (const e of w) {
    if (/^(node_modules|\.git|tmp|logs)$/.test(e.name)) continue;
    const p = path.join(kat, e.name);
    if (e.isDirectory()) szukaj(p, gl + 1);
    else if (/\.(js|mjs|cjs)$/.test(e.name)) {
      try {
        const t = fs.readFileSync(p, 'utf8');
        for (const l of t.split('\n')) {
          if (/bcrypt|argon2|scrypt|pbkdf2|createHash|hashSync|\.hash\(|compare\(/.test(l) && !/^\s*\/\//.test(l)) {
            console.log(`  ${path.relative(APP, p)}: ${l.trim().slice(0, 130)}`);
          }
        }
      } catch {}
    }
  }
})(APP, 0);

console.log('\n=== 5. Jak wysylane sa kody na maila ===');
(function szukaj(kat, gl) {
  if (gl > 2) return;
  let w = [];
  try { w = fs.readdirSync(kat, { withFileTypes: true }); } catch { return; }
  for (const e of w) {
    if (/^(node_modules|\.git|tmp|logs)$/.test(e.name)) continue;
    const p = path.join(kat, e.name);
    if (e.isDirectory()) szukaj(p, gl + 1);
    else if (/\.(js|mjs|cjs)$/.test(e.name)) {
      try {
        const t = fs.readFileSync(p, 'utf8');
        for (const l of t.split('\n')) {
          if (/sendMail|createTransport|nodemailer|verification_code|verify_code|kod/i.test(l) && !/^\s*\/\//.test(l)) {
            console.log(`  ${path.relative(APP, p)}: ${l.trim().slice(0, 130)}`);
          }
        }
      } catch {}
    }
  }
})(APP, 0);

console.log('\n=== 6. Tabela users i tabele z kodami ===');
(async () => {
  let mysql;
  for (const p of [path.join(APP, 'node_modules', 'mysql2', 'promise'), 'mysql2/promise']) {
    try { mysql = require(p); break; } catch {}
  }
  if (!mysql) { console.log('  brak mysql2 — pomijam'); return; }
  const db = await mysql.createConnection({
    host: we('DB_HOST', 'MYSQL_HOST') || 'localhost',
    port: Number(we('DB_PORT') || 3306),
    user: we('DB_USER', 'MYSQL_USER'),
    password: we('DB_PASSWORD', 'DB_PASS', 'MYSQL_PASSWORD') || '',
    database: we('DB_NAME', 'DB_DATABASE', 'MYSQL_DATABASE'),
  });
  const [tabele] = await db.query('SHOW TABLES');
  const nazwy = tabele.map((r) => Object.values(r)[0]);
  console.log('  tabele:', nazwy.join(', '));
  for (const t of nazwy.filter((n) => /user|verif|code|token|reset|session/i.test(n))) {
    const [k] = await db.query(`SHOW COLUMNS FROM \`${t}\``);
    console.log(`\n  ${t}:`);
    for (const c of k) console.log(`     ${c.Field.padEnd(22)} ${c.Type}  ${c.Null === 'NO' ? 'NOT NULL' : ''} ${c.Default !== null ? 'default=' + c.Default : ''}`);
  }
  // Sam poczatek hasza mowi, jaki to algorytm. Reszta jest zaslonieta.
  try {
    const [w] = await db.query('SELECT password_hash FROM users WHERE password_hash IS NOT NULL LIMIT 1');
    if (w[0]) {
      const h = String(Object.values(w[0])[0]);
      console.log(`\n  poczatek hasza: ${h.slice(0, 7)}...  (dlugosc ${h.length})`);
    }
  } catch {
    try {
      const [k] = await db.query('SHOW COLUMNS FROM users');
      const kol = k.map((c) => c.Field).find((f) => /pass|hash/i.test(f));
      if (kol) {
        const [w] = await db.query(`SELECT \`${kol}\` FROM users WHERE \`${kol}\` IS NOT NULL LIMIT 1`);
        if (w[0]) {
          const h = String(Object.values(w[0])[0]);
          console.log(`\n  kolumna ${kol}, poczatek hasza: ${h.slice(0, 7)}...  (dlugosc ${h.length})`);
        }
      }
    } catch (e) { console.log('  nie odczytalem hasza:', e.message); }
  }
  await db.end();
})().catch((e) => console.error('BLAD bazy:', e.message));
