// Konfiguracja pm2 — utrzymuje bota przy życiu 24/7 na VPS-ie SeoHost.
//   pm2 start ecosystem.config.js
//   pm2 logs discord-bot
//   pm2 restart discord-bot
module.exports = {
  apps: [
    {
      name: 'discord-bot',
      script: 'src/index.js',
      instances: 1,
      autorestart: true,
      watch: false,
      max_memory_restart: '300M',
      restart_delay: 5000,
      max_restarts: 20,
      env: {
        NODE_ENV: 'production',
      },
      error_file: 'logs/error.log',
      out_file: 'logs/out.log',
      time: true,
    },
  ],
};
