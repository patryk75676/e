# Bot Discord na hostingu SeoHost

Gotowy bot na discord.js v14 z komendami slash (`/`), skryptami instalacyjnymi i konfiguracją pm2 do działania 24/7.

Komendy w zestawie: `/ping`, `/pomoc`, `/serwer`, `/uzytkownik`, `/wyczysc`.

---

## Krok 1 — Załóż bota w Discordzie (przeglądarka, ~3 min)

1. Wejdź na https://discord.com/developers/applications → **New Application**, nadaj nazwę.
2. Zakładka **General Information** → skopiuj **Application ID** (to jest `CLIENT_ID`).
3. Zakładka **Bot** → **Reset Token** → **Copy**. To jest `DISCORD_TOKEN`.
   Token widzisz tylko raz — zapisz go od razu. Nigdy nie wrzucaj go do gita.
4. Nadal w **Bot**: wyłącz **Public Bot**, jeśli bot ma być tylko na Twoim serwerze.
5. Zakładka **OAuth2 → URL Generator**:
   - **Scopes**: `bot` + `applications.commands`
   - **Bot Permissions**: `Send Messages`, `Embed Links`, `Read Message History`, `Manage Messages` (to ostatnie tylko jeśli chcesz `/wyczysc`)
   - Skopiuj wygenerowany link na dole, otwórz go i dodaj bota na swój serwer.
6. ID serwera: w Discordzie **Ustawienia → Zaawansowane → Tryb dewelopera** ON, potem prawy klik na ikonę serwera → **Kopiuj ID serwera**. To jest `GUILD_ID`.

## Krok 2 — Wgraj kod na SeoHost

Połącz się przez SSH (dane do logowania znajdziesz w panelu SeoHost):

```bash
ssh uzytkownik@twoj-serwer.seohost.pl
```

Jeśli serwer ma niestandardowy port SSH: `ssh -p PORT uzytkownik@twoj-serwer.seohost.pl`.

Sklonuj repozytorium:

```bash
cd ~
git clone https://github.com/patryk75676/e.git discord-bot
cd discord-bot
```

> Nie masz gita na serwerze? Wyślij pliki z komputera:
> `rsync -av --exclude node_modules --exclude .env ./ uzytkownik@twoj-serwer.seohost.pl:~/discord-bot/`

## Krok 3 — Node.js

Sprawdź, czy jest zainstalowany i w wersji 18+:

```bash
node -v
```

**Na VPS/serwerze dedykowanym SeoHost** (masz `sudo`), jeśli brakuje lub wersja jest za stara:

```bash
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs
node -v
```

**Na hostingu współdzielonym** wersję Node.js wybiera się w panelu (DirectAdmin → *Setup Node.js App*). Ustaw tam **Node.js 18 lub nowszy**, katalog aplikacji `discord-bot`, plik startowy `src/index.js`. Szczegóły niżej, w sekcji *Hosting współdzielony*.

## Krok 4 — Konfiguracja i uruchomienie

```bash
bash scripts/setup.sh
```

Skrypt zainstaluje zależności i utworzy `.env`. Uzupełnij go:

```bash
nano .env
```

Wklej `DISCORD_TOKEN`, `CLIENT_ID`, `GUILD_ID`, zapisz (`Ctrl+O`, `Enter`, `Ctrl+X`) i uruchom skrypt ponownie:

```bash
bash scripts/setup.sh
```

Tym razem zarejestruje komendy slash. Sprawdź, czy bot wstaje:

```bash
npm start
```

Powinieneś zobaczyć `[OK] Zalogowano jako TwojBot#1234`. Wejdź na Discorda i wpisz `/ping`. Działa? `Ctrl+C` i przechodzimy do trwałego uruchomienia.

## Krok 5 — Praca 24/7 przez pm2 (VPS / serwer dedykowany)

```bash
npm install -g pm2          # jeśli brak uprawnień: sudo npm install -g pm2
pm2 start ecosystem.config.js
pm2 save
pm2 startup                 # wypisze jedną komendę — skopiuj ją i wykonaj
```

`pm2 startup` sprawia, że bot wstaje sam po restarcie serwera.

Codzienna obsługa:

| Co chcesz zrobić | Komenda |
|---|---|
| Podejrzeć logi na żywo | `pm2 logs discord-bot` |
| Status i zużycie zasobów | `pm2 status` |
| Restart | `pm2 restart discord-bot` |
| Zatrzymanie | `pm2 stop discord-bot` |
| Usunięcie z pm2 | `pm2 delete discord-bot` |

## Krok 6 — Aktualizacje

Po wypchnięciu zmian do gita, na serwerze:

```bash
cd ~/discord-bot
bash scripts/deploy-seohost.sh
```

Skrypt pobiera kod, aktualizuje paczki, odświeża komendy i restartuje proces.

---

## Hosting współdzielony SeoHost (bez SSH z `sudo`)

Bot Discord to proces działający bez przerwy, a hosting współdzielony jest zaprojektowany pod strony WWW, nie pod stale działające procesy. Dwie realne opcje:

**A. DirectAdmin → Setup Node.js App** (jeśli Twój pakiet to ma):
1. *Application root*: `discord-bot`, *Application startup file*: `src/index.js`, wersja Node **18+**.
2. Zmienne `DISCORD_TOKEN`, `CLIENT_ID`, `GUILD_ID` dodaj w sekcji **Environment variables** panelu — wtedy plik `.env` nie jest potrzebny.
3. Kliknij **Run NPM Install**, potem **Restart**.
4. Komendy slash zarejestruj raz z terminala panelu: `node src/deploy-commands.js`.
5. Uwaga: panel potrafi usypiać aplikację przy braku ruchu HTTP — bot Discord nie dostaje ruchu HTTP, więc jeśli po kilku godzinach przestaje odpowiadać, to jest właśnie ten limit. Wtedy → opcja B.

**B. Najpewniejsze: VPS w SeoHost.** Najtańszy pakiet VPS w zupełności wystarcza — bot zjada ~80–150 MB RAM. Wtedy działa Krok 5 z pm2 i nie ma żadnych limitów.

Jeśli nie wiesz, który pakiet masz — napisz do supportu SeoHost pytanie: *„Czy mój pakiet pozwala uruchomić stale działającą aplikację Node.js (proces w tle, bez serwera HTTP)?"*. Odpowiedź przesądza, czy wystarczy opcja A.

---

## Bezpieczeństwo

- `.env` jest w `.gitignore` — **nigdy** nie commituj tokena.
- Jeśli token gdzieś wyciekł: Developer Portal → **Bot** → **Reset Token**, wklej nowy do `.env`, `pm2 restart discord-bot`.
- Trzymaj uprawnienia bota na minimum — nie nadawaj `Administrator`, jeśli nie musisz.

## Rozwiązywanie problemów

| Objaw | Przyczyna i rozwiązanie |
|---|---|
| `Used disallowed intents` | Bot prosi o intent bez włączenia go w Developer Portal → Bot → *Privileged Gateway Intents*. Ten projekt używa tylko `Guilds`, więc nie powinno wystąpić — chyba że dodałeś własne intenty. |
| `Invalid token` / `401` | Zły `DISCORD_TOKEN` w `.env`. Zresetuj token i wklej ponownie — bez cudzysłowów i spacji. |
| Komendy `/` nie widać | Uruchom `npm run deploy` i odśwież Discorda (`Ctrl+R`). Sprawdź, czy `GUILD_ID` to ID serwera, na który dodałeś bota, i czy zaproszenie zawierało scope `applications.commands`. |
| `Missing Permissions` przy `/wyczysc` | Rola bota musi mieć `Manage Messages` i stać **wyżej** na liście ról niż autorzy usuwanych wiadomości. |
| Bot startuje i zaraz pada | `pm2 logs discord-bot --lines 50` pokaże właściwy błąd. |
| `EACCES` przy `npm install -g pm2` | Dodaj `sudo` lub zainstaluj lokalnie: `npm i pm2` i używaj `npx pm2 ...`. |

## Dodanie własnej komendy

Utwórz plik w `src/commands/`, np. `src/commands/kostka.js`:

```js
const { SlashCommandBuilder } = require('discord.js');

module.exports = {
  data: new SlashCommandBuilder().setName('kostka').setDescription('Rzuca kostką k6'),
  async execute(interaction) {
    await interaction.reply(`Wypadło: ${Math.ceil(Math.random() * 6)}`);
  },
};
```

Potem `npm run deploy && pm2 restart discord-bot`. Plik jest wczytywany automatycznie — nic więcej nie trzeba rejestrować w kodzie.
