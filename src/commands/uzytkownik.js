const { SlashCommandBuilder, EmbedBuilder } = require('discord.js');

module.exports = {
  data: new SlashCommandBuilder()
    .setName('uzytkownik')
    .setDescription('Informacje o użytkowniku')
    .addUserOption((option) =>
      option.setName('kto').setDescription('Użytkownik do sprawdzenia (domyślnie Ty)').setRequired(false)
    ),

  async execute(interaction) {
    const user = interaction.options.getUser('kto') ?? interaction.user;
    const member = interaction.inGuild() ? await interaction.guild.members.fetch(user.id).catch(() => null) : null;

    const embed = new EmbedBuilder()
      .setColor(member?.displayColor || 0x5865f2)
      .setTitle(user.tag)
      .setThumbnail(user.displayAvatarURL({ size: 256 }))
      .addFields({ name: 'Konto utworzone', value: `<t:${Math.floor(user.createdTimestamp / 1000)}:D>`, inline: true });

    if (member) {
      embed.addFields(
        { name: 'Dołączył na serwer', value: `<t:${Math.floor(member.joinedTimestamp / 1000)}:D>`, inline: true },
        {
          name: `Role (${member.roles.cache.size - 1})`,
          value:
            member.roles.cache
              .filter((r) => r.id !== interaction.guild.id)
              .map((r) => r.toString())
              .join(' ') || 'brak',
        }
      );
    }

    embed.setFooter({ text: `ID: ${user.id}` });
    await interaction.reply({ embeds: [embed] });
  },
};
