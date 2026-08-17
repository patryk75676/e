const fs = require('node:fs');
const path = require('node:path');
const { Client, Collection, GatewayIntentBits, Events, MessageFlags } = require('discord.js');
const { config, requireVars } = require('./config');

requireVars(['token']);

const client = new Client({
  intents: [GatewayIntentBits.Guilds],
});

client.commands = new Collection();

const commandsPath = path.join(__dirname, 'commands');
for (const file of fs.readdirSync(commandsPath).filter((f) => f.endsWith('.js'))) {
  const command = require(path.join(commandsPath, file));
  if (!command.data || typeof command.execute !== 'function') {
    console.warn(`[UWAGA] Pomijam ${file} — brak "data" lub "execute".`);
    continue;
  }
  client.commands.set(command.data.name, command);
}

client.once(Events.ClientReady, (c) => {
  console.log(`[OK] Zalogowano jako ${c.user.tag}`);
  console.log(`[OK] Zaladowano komend: ${client.commands.size}`);
  console.log(`[OK] Serwery: ${c.guilds.cache.size}`);
  c.user.setActivity('/pomoc');
});

client.on(Events.InteractionCreate, async (interaction) => {
  if (!interaction.isChatInputCommand()) return;

  const command = client.commands.get(interaction.commandName);
  if (!command) {
    console.warn(`[UWAGA] Nieznana komenda: ${interaction.commandName}`);
    return;
  }

  try {
    await command.execute(interaction, client);
  } catch (error) {
    console.error(`[BLAD] Komenda /${interaction.commandName}:`, error);
    const payload = { content: 'Coś poszło nie tak przy wykonywaniu tej komendy.', flags: MessageFlags.Ephemeral };
    if (interaction.replied || interaction.deferred) {
      await interaction.followUp(payload).catch(() => {});
    } else {
      await interaction.reply(payload).catch(() => {});
    }
  }
});

client.on(Events.Error, (error) => console.error('[BLAD] Klient Discord:', error));
process.on('unhandledRejection', (reason) => console.error('[BLAD] Nieobsluzony rejection:', reason));

// Czyste zamknięcie — pm2 restart/stop nie zostawia zawieszonego połączenia z Discordem.
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    console.log(`[INFO] Odebrano ${signal}, zamykam bota...`);
    client.destroy();
    process.exit(0);
  });
}

client.login(config.token);
