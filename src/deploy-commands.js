// Rejestruje komendy slash (/) w Discordzie.
//   node src/deploy-commands.js            -> tylko na serwerze z GUILD_ID (natychmiast)
//   node src/deploy-commands.js --global   -> na wszystkich serwerach (propagacja do ~1h)
const fs = require('node:fs');
const path = require('node:path');
const { REST, Routes } = require('discord.js');
const { config, requireVars } = require('./config');

const global = process.argv.includes('--global');
requireVars(global ? ['token', 'clientId'] : ['token', 'clientId', 'guildId']);

const commands = [];
const commandsPath = path.join(__dirname, 'commands');
for (const file of fs.readdirSync(commandsPath).filter((f) => f.endsWith('.js'))) {
  const command = require(path.join(commandsPath, file));
  if (command.data && typeof command.execute === 'function') {
    commands.push(command.data.toJSON());
  }
}

const rest = new REST().setToken(config.token);

(async () => {
  try {
    console.log(`[INFO] Rejestruje ${commands.length} komend (${global ? 'globalnie' : 'na serwerze ' + config.guildId})...`);
    const route = global
      ? Routes.applicationCommands(config.clientId)
      : Routes.applicationGuildCommands(config.clientId, config.guildId);

    const data = await rest.put(route, { body: commands });
    console.log(`[OK] Zarejestrowano ${data.length} komend: ${data.map((c) => '/' + c.name).join(', ')}`);
  } catch (error) {
    console.error('[BLAD] Rejestracja komend nie powiodla sie:', error);
    process.exit(1);
  }
})();
