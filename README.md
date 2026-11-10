# Bazaar Flipper (alerts only)

Watches the whole Hypixel SkyBlock Bazaar and tells you which flips are worth doing right now: what to buy, the exact buy order and sell offer prices, how many, and the profit after tax. Alerts show up **in your Minecraft chat** through a small companion mod, and also in the console window.

```
[Flip] Soulflow  +4.77M profit
  Buy order: 1,122 @ 22,264.5 [copy]  Sell offer: 1,122 @ 26,855.9 [copy]
```

- Click **[copy]** to copy that exact price, then paste it into the custom price sign.
- Hover the item name to see more details. Click the item name to open `/bz <item>`.

## Hypixel rules: it only alerts you
This tool **never places orders, clicks, opens menus or automates the game**. Automating the Bazaar is a macro and gets accounts banned. It only reads public prices and shows you text. You still go to the Bazaar and place every order yourself. The mod is display-only, like the Bazaar features in SkyHanni or Skyblocker. The one thing it ever sends to Hypixel is a `/bz` command, and only when you click an item name.

## What's in here
| Part | What it does |
|---|---|
| `flipper.py` | Runs on your PC. Every 60s it downloads the Bazaar from Hypixel's official API (no API key needed) and finds the flips. It uses 24h price history from [Coflnet](https://sky.coflnet.com) to skip manipulated items. |
| `mod/` | Fabric mod for Minecraft 26.3. It reads alerts from `flipper.py` and prints them in your chat. It only talks to your own PC (`127.0.0.1`). |
| `config.json` | Your settings. It's created the first time you run `flipper.py`. |

## Setup (one time)

### 1. Python
You already have Python if `sniper.py` works. If not, install Python 3.8+ from https://www.python.org and tick **"Add python.exe to PATH"**. No extra packages are needed.

### 2. The mod
1. Build it. Open a terminal in the `mod` folder and run:
   ```bash
   gradlew build
   ```
   This writes the mod to `mod/build/libs/bazaarflipper-1.0.0.jar`.
2. In the Modrinth app, open your **26.3 Fabric** SkyBlock profile (the one you use with Ghostblocker), then **Add content → from file** and pick that jar. It needs Fabric API, which that profile already has.

## Every time you play
1. Double-click **`run.bat`** (or run `python flipper.py`). Keep that window open.
2. Launch Minecraft and join Hypixel. Alerts appear in chat within about a minute.

If the game says *"Can't reach flipper.py"*, the window from step 1 isn't running. Start it again and the mod reconnects by itself.

### In-game commands
| Command | What it does |
|---|---|
| `/flips` | Show the current best flips |
| `/flips off` / `/flips on` | Pause or resume alerts |
| `/flips sound` | Turn the alert sound on or off |

## Settings (`config.json`)
Edit with Notepad. Changes apply on the next check, so you don't need to restart.

| Setting | Default | Meaning |
|---|---|---|
| `poll_seconds` | 60 | How often to check (minimum 20) |
| `budget` | 100000000 | Max coins across all suggestions at once (100M) |
| `max_coins_per_flip` | 25000000 | Max coins in any single flip (25M) |
| `min_profit_per_flip` | 300000 | Skip flips that make less than this in total |
| `min_margin_percent` | 2.0 | Skip flips with a smaller margin than this, after tax |
| `min_weekly_volume` | 200000 | Skip items with fewer instabuys or instasells per week than this, so you don't get dead items |
| `max_share_of_hourly_volume` | 0.25 | Suggest at most 25% of what trades per hour, so orders actually fill |
| `tax_percent` | 1.25 | Bazaar tax on sell offers. Set **1.125** or **1.0** if you have the Bazaar Flipper upgrade from the Community Shop |
| `top_n` | 5 | How many flips to suggest per check |
| `realert_minutes` | 30 | Don't repeat the same item sooner than this, unless its prices move more than 3% |
| `outlier_factor` | 1.5 | Skip an item if its sell price is more than 1.5x its 24h median (likely manipulated) |
| `use_coflnet` | true | Use Coflnet's 24h history. If it's off or Coflnet is down, the script uses the prices it has seen itself, which kicks in after about 10 checks |
| `blacklist` | [] | Item IDs to never suggest, e.g. `["GREEN_GIFT", "SOULFLOW"]` |
| `discord_webhook` | "" | Optional. Paste a Discord webhook URL to also get alerts on Discord or your phone |
| `mod_port` | 8765 | Local port the mod reads from. If you change it, also change `port` in `.minecraft/config/bazaarflipper.json` |
| `beep` | false | Beep in the console window on new alerts |

## How the numbers are worked out
- **Buy order price** = current top buy order + 0.1. **Sell offer price** = current lowest sell offer - 0.1.
- **Profit per item** = sell price × (1 - tax) - buy price. Buy orders have no tax.
- **Quantity** is the smallest of four limits: `max_coins_per_flip`, your share of hourly volume, what's left of `budget`, and the Bazaar's 71,680 per-order limit.
- **Volume** is the slower side of the flip. Your buy order fills when people insta-sell, and your sell offer fills when people insta-buy.
- A **note** shows when the spread is much wider than usual. That flip may be gone in minutes, so check before committing.

## Tips
- Prices move. Before you place an order, check that you'd still be top of the order book. If someone outbids you, use **[copy]** on the new alert, or re-check with `/flips`.
- Start with a small budget until you trust the suggestions, then raise `budget` and `max_coins_per_flip`.
- `python flipper.py --once` does a single check and exits. It's handy for trying out settings.
