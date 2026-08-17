const { SlashCommandBuilder, EmbedBuilder } = require('discord.js');

module.exports = {
  data: new SlashCommandBuilder().setName('ping').setDescription('Sprawdza czy bot żyje i jakie ma opóźnienie'),

  async execute(interaction, client) {
    await interaction.reply({ content: 'Mierzę...' });
    const roundtrip = Date.now() - interaction.createdTimestamp;

    const embed = new EmbedBuilder()
      .setColor(0x5865f2)
      .setTitle('Pong!')
      .addFields(
        { name: 'Opóźnienie', value: `${roundtrip} ms`, inline: true },
        { name: 'API Discorda', value: `${Math.round(client.ws.ping)} ms`, inline: true },
        { name: 'Uptime', value: formatUptime(client.uptime), inline: true }
      );

    await interaction.editReply({ content: '', embeds: [embed] });
  },
};

function formatUptime(ms) {
  const s = Math.floor(ms / 1000);
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const parts = [];
  if (d) parts.push(`${d}d`);
  if (h) parts.push(`${h}h`);
  if (m) parts.push(`${m}m`);
  parts.push(`${s % 60}s`);
  return parts.join(' ');
}
