const { SlashCommandBuilder, EmbedBuilder, MessageFlags } = require('discord.js');

module.exports = {
  data: new SlashCommandBuilder().setName('serwer').setDescription('Informacje o tym serwerze Discord'),

  async execute(interaction) {
    if (!interaction.inGuild()) {
      return interaction.reply({ content: 'Tej komendy używa się na serwerze.', flags: MessageFlags.Ephemeral });
    }

    const guild = interaction.guild;
    const owner = await guild.fetchOwner();

    const embed = new EmbedBuilder()
      .setColor(0x57f287)
      .setTitle(guild.name)
      .setThumbnail(guild.iconURL({ size: 256 }))
      .addFields(
        { name: 'Właściciel', value: owner.user.tag, inline: true },
        { name: 'Członkowie', value: `${guild.memberCount}`, inline: true },
        { name: 'Kanały', value: `${guild.channels.cache.size}`, inline: true },
        { name: 'Role', value: `${guild.roles.cache.size}`, inline: true },
        { name: 'Poziom boostów', value: `${guild.premiumTier} (${guild.premiumSubscriptionCount ?? 0} boostów)`, inline: true },
        { name: 'Utworzony', value: `<t:${Math.floor(guild.createdTimestamp / 1000)}:D>`, inline: true }
      )
      .setFooter({ text: `ID: ${guild.id}` });

    await interaction.reply({ embeds: [embed] });
  },
};
