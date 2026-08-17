require('dotenv').config();

const config = {
  token: process.env.DISCORD_TOKEN,
  clientId: process.env.CLIENT_ID,
  guildId: process.env.GUILD_ID,
};

function requireVars(names) {
  const missing = names.filter((name) => !config[name]);
  if (missing.length > 0) {
    const envNames = { token: 'DISCORD_TOKEN', clientId: 'CLIENT_ID', guildId: 'GUILD_ID' };
    console.error(
      `[BLAD] Brakuje zmiennych w pliku .env: ${missing.map((n) => envNames[n]).join(', ')}\n` +
        'Uruchom: cp .env.example .env && nano .env'
    );
    process.exit(1);
  }
}

module.exports = { config, requireVars };
