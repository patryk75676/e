const { SlashCommandBuilder, PermissionFlagsBits, MessageFlags } = require('discord.js');

module.exports = {
  data: new SlashCommandBuilder()
    .setName('wyczysc')
    .setDescription('Usuwa ostatnie wiadomości z kanału (max 100, młodsze niż 14 dni)')
    .addIntegerOption((option) =>
      option.setName('ile').setDescription('Liczba wiadomości (1-100)').setMinValue(1).setMaxValue(100).setRequired(true)
    )
    .setDefaultMemberPermissions(PermissionFlagsBits.ManageMessages),

  async execute(interaction) {
    if (!interaction.inGuild()) {
      return interaction.reply({ content: 'Tej komendy używa się na serwerze.', flags: MessageFlags.Ephemeral });
    }

    const ile = interaction.options.getInteger('ile');

    // Discord odrzuca bulkDelete dla wiadomości starszych niż 14 dni — filtrujemy z góry.
    const deleted = await interaction.channel.bulkDelete(ile, true);

    await interaction.reply({
      content:
        deleted.size === 0
          ? 'Nie usunięto nic — wiadomości są starsze niż 14 dni.'
          : `Usunięto ${deleted.size} wiadomości.`,
      flags: MessageFlags.Ephemeral,
    });
  },
};
