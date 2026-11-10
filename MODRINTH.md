# Modrinth listing

Paste these into the Modrinth project page.

- **Name:** Bazaar Flipper Alerts
- **Summary:** Pings you in chat with Hypixel SkyBlock Bazaar flips: exact buy and sell prices, quantity and profit after tax. Alerts only, no automation.
- **Categories:** Utility, Economy
- **Environment:** Client-side only (server: unsupported)
- **Loader / version:** Fabric, Minecraft 26.1.2. Depends on Fabric API.
- **License:** MIT
- **Source:** https://github.com/pacandize-tech/bazaar-flipper
- **Gallery:** `docs/chat.png`
- **Upload:** `mod/build/libs/bazaarflipper-1.0.0.jar` (version number 1.0.0, release channel)

## Description

Bazaar Flipper watches every Hypixel SkyBlock Bazaar item and pings you in chat when a flip is worth doing. Each alert tells you:

- **what to buy** and the exact **buy order** price (top buy order + 0.1)
- the exact **sell offer** price (lowest sell offer - 0.1)
- **how many** to buy, and the **profit after Bazaar tax**

Click **[copy]** to put a price on your clipboard. Hover an item for details, and click it to open `/bz`.

### Smart filters
- Skips dead items with a minimum weekly volume, and sizes each order to a share of hourly volume so it actually fills.
- Uses Coflnet's 24h price history to skip manipulated price spikes, and warns you when a spread is unusually wide.
- Lets you set a budget cap, a max per flip, a minimum profit and a minimum margin, either in game with `/flips set budget 50m` or in the config file.
- Checks about every 20 seconds, as often as Hypixel refreshes the Bazaar, and doesn't repeat the same alert.

### Alerts only: safe for Hypixel
This mod **never places orders, clicks or opens menus for you**. It reads Hypixel's public Bazaar API and shows you text in chat. You place every order yourself. The only thing it ever sends to the server is `/bz <item>`, and only when you click an item name.

### Commands
`/flips` · `/flips on|off` · `/flips sound` · `/flips settings` · `/flips set <setting> <value>` · `/flips reload`

*Not affiliated with Hypixel or Mojang. Price data comes from the Hypixel public API and [Coflnet](https://sky.coflnet.com).*
