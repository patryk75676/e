const { SlashCommandBuilder, EmbedBuilder } = require('discord.js');

module.exports = {
  data: new SlashCommandBuilder().setName('pomoc').setDescription('Lista wszystkich komend bota'),

  async execute(interaction, client) {
    const lista = [...client.commands.values()]
      .map((cmd) => `**/${cmd.data.name}** — ${cmd.data.description}`)
      .sort()
      .join('\n');

    const embed = new EmbedBuilder()
      .setColor(0x5865f2)
      .setTitle('Dostępne komendy')
      .setDescription(lista || 'Brak komend.')
      .setFooter({ text: `${client.user.username}` })
      .setTimestamp();

    await interaction.reply({ embeds: [embed] });
  },
};
