"""
Hypixel SkyBlock Bazaar flip finder (alerts only).

Every poll it downloads the whole Bazaar from Hypixel's official API, works
out which items have a spread worth flipping after tax, and tells you:

  * what to buy, and the buy order price to set (top buy order + 0.1)
  * the sell offer price to set (lowest sell offer - 0.1)
  * how many to buy and the expected profit

Alerts go to the console and, optionally, to Discord. For alerts in your
Minecraft chat, use the Fabric mod in mod/ instead (it works on its own).

It NEVER places orders or touches the game. You do the clicking, so it stays
within Hypixel's rules.

Only the Python standard library is used. Settings live in config.json,
which is created with defaults on first run.
"""

import argparse
import json
import math
import os
import re
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import deque
from datetime import datetime

BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar"
ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items"
COFL_HISTORY_URL = "https://sky.coflnet.com/api/bazaar/{}/history/day"
USER_AGENT = "bazaar-flipper/1.0 (alerts only)"

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")

MIN_POLL_SECONDS = 20      # Hypixel only refreshes the Bazaar about every 20s
MAX_ORDER_AMOUNT = 71680   # Bazaar limit per order
HOURS_PER_WEEK = 168
COFL_CACHE_SECONDS = 20 * 60
UNCHANGED_RETRY_SECONDS = 5  # snapshot not refreshed yet: look again soon

DEFAULTS = {
    "poll_seconds": 20,
    "budget": 100_000_000,
    "max_coins_per_flip": 25_000_000,
    "min_profit_per_flip": 300_000,
    "min_margin_percent": 2.0,
    "min_weekly_volume": 200_000,
    "max_share_of_hourly_volume": 0.25,
    "tax_percent": 1.25,
    "price_step": 0.1,
    "top_n": 5,
    "realert_minutes": 30,
    "outlier_factor": 1.5,
    "use_coflnet": True,
    "blacklist": [],
    "discord_webhook": "",
    "beep": False,
}

ROMAN = {1: "I", 2: "II", 3: "III", 4: "IV", 5: "V", 6: "VI", 7: "VII",
         8: "VIII", 9: "IX", 10: "X"}


def log(msg):
    print(f"[{datetime.now():%H:%M:%S}] {msg}", flush=True)


def coins(n):
    """1234567 -> '1.23M', 4500 -> '4.5k', 812.3 -> '812.3'."""
    for size, suffix in ((1e9, "B"), (1e6, "M"), (1e3, "k")):
        if abs(n) >= size:
            return f"{n / size:.2f}".rstrip("0").rstrip(".") + suffix
    return f"{n:,.1f}"


# ---------------------------------------------------------------- config

_warned_unknown = set()


def load_config():
    if not os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(DEFAULTS, f, indent=2)
        log(f"Created {CONFIG_PATH} with default settings. Edit it any time; "
            "changes are picked up on the next poll.")
    with open(CONFIG_PATH, encoding="utf-8") as f:
        user = json.load(f)
    unknown = set(user) - set(DEFAULTS)
    if unknown and unknown != _warned_unknown:
        _warned_unknown.clear()
        _warned_unknown.update(unknown)
        log(f"Ignoring unknown setting(s) in config.json: {', '.join(sorted(unknown))}")
    cfg = {**DEFAULTS, **{k: v for k, v in user.items() if k in DEFAULTS}}
    cfg["poll_seconds"] = max(cfg["poll_seconds"], MIN_POLL_SECONDS)
    cfg["blacklist"] = {b.upper() for b in cfg["blacklist"]}
    return cfg


# ---------------------------------------------------------------- http

def get_json(url, timeout=15):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def post_json(url, payload, timeout=10):
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), method="POST",
                                 headers={"User-Agent": USER_AGENT, "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status


# ---------------------------------------------------------------- names

def load_names():
    try:
        data = get_json(ITEMS_URL, timeout=30)
        return {i["id"]: re.sub("§.", "", i["name"]) for i in data.get("items", [])}
    except (urllib.error.URLError, ValueError, OSError) as e:
        log(f"Couldn't load item names ({e}); using IDs instead.")
        return {}


def pretty_name(pid, names):
    if pid in names:
        return names[pid]
    words = pid
    level = ""
    m = re.match(r"^(.*)_(\d+)$", words)
    if m and words.startswith("ENCHANTMENT_"):
        words, n = m.group(1), int(m.group(2))
        level = " " + ROMAN.get(n, str(n))
    for prefix in ("ENCHANTMENT_ULTIMATE_", "ENCHANTMENT_"):
        if words.startswith(prefix):
            words = words[len(prefix):]
            break
    else:
        for prefix, suffix in (("ESSENCE_", "Essence"), ("SHARD_", "Shard")):
            if words.startswith(prefix):
                words = words[len(prefix):] + "_" + suffix
                break
    return words.replace("_", " ").title() + level


# ---------------------------------------------------------------- history

class History:
    """Recent top-of-book prices, used to spot manipulated price spikes.

    Uses Coflnet's 24h Bazaar history when available, otherwise the prices
    this script has seen itself since it started.
    """

    def __init__(self, use_coflnet):
        self.use_coflnet = use_coflnet
        self.local = {}   # pid -> deque[(buy_order, sell_offer)]
        self.cofl = {}    # pid -> (fetched_at, (median_buy_order, median_sell_offer)) or None
        self.cofl_failed_until = 0

    def record(self, pid, buy_order, sell_offer):
        self.local.setdefault(pid, deque(maxlen=1440)).append((buy_order, sell_offer))

    def medians(self, pid):
        """(median buy order, median sell offer, source) or None if no history yet."""
        if self.use_coflnet and time.time() >= self.cofl_failed_until:
            cached = self.cofl.get(pid)
            if cached is None or time.time() - cached[0] > COFL_CACHE_SECONDS:
                cached = (time.time(), self._fetch_cofl(pid))
                self.cofl[pid] = cached
            if cached[1]:
                return (*cached[1], "Coflnet 24h")
        samples = self.local.get(pid, ())
        if len(samples) >= 10:
            return (statistics.median(s[0] for s in samples),
                    statistics.median(s[1] for s in samples), "local")
        return None

    def _fetch_cofl(self, pid):
        try:
            rows = get_json(COFL_HISTORY_URL.format(urllib.parse.quote(pid)))
        except (urllib.error.URLError, ValueError, OSError) as e:
            log(f"Coflnet unavailable ({e}); using local history for 5 min.")
            self.cofl_failed_until = time.time() + 300
            return None
        # Coflnet: "sell" = top buy order, "buy" = lowest sell offer
        orders = [r["sell"] for r in rows if r.get("sell")]
        offers = [r["buy"] for r in rows if r.get("buy")]
        if len(orders) < 10 or len(offers) < 10:
            return None
        return statistics.median(orders), statistics.median(offers)


# ---------------------------------------------------------------- flips

_skip_logged = {}  # pid -> when we last said we skipped it

def find_flips(products, cfg, names, history):
    """Returns flips sorted best-first, each a dict ready to alert on."""
    tax = cfg["tax_percent"] / 100
    step = cfg["price_step"]
    candidates = []

    for pid, p in products.items():
        orders, offers = p.get("sell_summary"), p.get("buy_summary")
        if not orders or not offers:
            continue
        top_order = orders[0]["pricePerUnit"]   # highest buy order
        low_offer = offers[0]["pricePerUnit"]   # lowest sell offer
        history.record(pid, top_order, low_offer)
        if pid in cfg["blacklist"]:
            continue

        buy_at = round(top_order + step, 1)
        sell_at = round(low_offer - step, 1)
        if sell_at <= buy_at:
            continue
        profit_each = sell_at * (1 - tax) - buy_at
        margin = profit_each / buy_at * 100
        if profit_each <= 0 or margin < cfg["min_margin_percent"]:
            continue

        q = p["quick_status"]
        # Your buy order fills when people insta-sell; your sell offer fills
        # when people insta-buy. The slower side limits the flip.
        weekly = min(q["sellMovingWeek"], q["buyMovingWeek"])
        if weekly < cfg["min_weekly_volume"]:
            continue
        hourly = weekly / HOURS_PER_WEEK
        qty = math.floor(min(cfg["max_coins_per_flip"] / buy_at,
                             hourly * cfg["max_share_of_hourly_volume"],
                             MAX_ORDER_AMOUNT))
        if qty < 1 or qty * profit_each < cfg["min_profit_per_flip"]:
            continue

        candidates.append({
            "product": pid, "name": pretty_name(pid, names),
            "buy_price": buy_at, "sell_price": sell_at,
            "top_buy_order": top_order, "lowest_sell_offer": low_offer,
            "quantity": qty, "profit_each": round(profit_each, 1),
            "profit_total": round(qty * profit_each), "cost": round(qty * buy_at),
            "margin_percent": round(margin, 2), "hourly_volume": round(hourly),
            "volume_share_percent": round(qty / hourly * 100), "note": "",
        })

    candidates.sort(key=lambda f: f["profit_total"], reverse=True)

    # Price-history check on the best few, then fit them into the budget.
    flips, budget_left = [], cfg["budget"]
    for f in candidates[: cfg["top_n"] * 4]:
        if len(flips) >= cfg["top_n"]:
            break
        med = history.medians(f["product"])
        if med:
            med_order, med_offer, source = med
            if f["lowest_sell_offer"] > med_offer * cfg["outlier_factor"]:
                if time.time() - _skip_logged.get(f["product"], 0) > 1800:
                    _skip_logged[f["product"]] = time.time()
                    log(f"Skipping {f['name']}: sell price {coins(f['lowest_sell_offer'])} is "
                        f"way above its usual {coins(med_offer)} ({source}) - likely manipulated.")
                continue
            usual_spread = med_offer - med_order
            spread = f["lowest_sell_offer"] - f["top_buy_order"]
            if usual_spread > 0 and spread > usual_spread * 2:
                f["note"] = f"spread is {spread / usual_spread:.1f}x its usual - may not last"
        else:
            f["note"] = "no price history yet"
        if f["cost"] > budget_left:
            f["quantity"] = math.floor(budget_left / f["buy_price"])
            f["cost"] = round(f["quantity"] * f["buy_price"])
            f["profit_total"] = round(f["quantity"] * f["profit_each"])
            if f["profit_total"] < cfg["min_profit_per_flip"]:
                continue
        budget_left -= f["cost"]
        flips.append(f)
    return flips


# ---------------------------------------------------------------- alerts

def format_flip(f):
    lines = [
        f"{f['name']}  (+{coins(f['profit_total'])} profit, {f['margin_percent']}% margin)",
        f"  BUY ORDER  {f['quantity']:,} @ {f['buy_price']:,.1f}  (cost {coins(f['cost'])})",
        f"  SELL OFFER {f['quantity']:,} @ {f['sell_price']:,.1f}  "
        f"(+{coins(f['profit_each'])}/each after tax)",
        f"  size: {f['volume_share_percent']}% of the ~{f['hourly_volume']:,} that trade per hour",
    ]
    if f["note"]:
        lines.append(f"  note: {f['note']}")
    return "\n".join(lines)


def send_discord(webhook, flips):
    embeds = [{
        "title": f["name"],
        "color": 0x55FF55,
        "description": (f"**Buy order:** {f['quantity']:,} @ **{f['buy_price']:,.1f}**\n"
                        f"**Sell offer:** {f['quantity']:,} @ **{f['sell_price']:,.1f}**\n"
                        f"Profit: **+{coins(f['profit_total'])}** "
                        f"({coins(f['profit_each'])}/each, {f['margin_percent']}%)\n"
                        f"Cost {coins(f['cost'])} · {f['volume_share_percent']}% of hourly volume"
                        + (f"\n_{f['note']}_" if f["note"] else "")),
    } for f in flips[:10]]
    try:
        post_json(webhook, {"username": "Bazaar Flipper", "embeds": embeds})
    except (urllib.error.URLError, OSError) as e:
        log(f"Discord webhook failed: {e}")


def beep():
    try:
        import winsound
        winsound.Beep(1000, 200)
    except (ImportError, RuntimeError):
        print("\a", end="", flush=True)


def should_alert(flip, last_alerted, cfg):
    prev = last_alerted.get(flip["product"])
    if prev is None:
        return True
    when, buy, sell = prev
    if time.time() - when >= cfg["realert_minutes"] * 60:
        return True
    # Prices moved more than 3%: worth telling you the new numbers.
    return abs(flip["buy_price"] - buy) / buy > 0.03 or abs(flip["sell_price"] - sell) / sell > 0.03


# ---------------------------------------------------------------- main

def run(once=False):
    cfg = load_config()
    names = load_names()
    history = History(cfg["use_coflnet"])
    last_alerted = {}
    last_updated = None
    log("Watching the Bazaar. Alerts only - you place the orders yourself. Ctrl+C to stop.")

    while True:
        if not once:
            try:
                cfg = load_config()
            except json.JSONDecodeError as e:
                log(f"config.json has a typo ({e}); keeping the previous settings.")
        changed = True  # on errors, wait the full interval
        try:
            data = get_json(BAZAAR_URL)
            if not data.get("success"):
                raise ValueError(data.get("cause", "API said success=false"))
            changed = data["lastUpdated"] != last_updated
            if changed:
                last_updated = data["lastUpdated"]
                flips = find_flips(data["products"], cfg, names, history)
                new = [f for f in flips if should_alert(f, last_alerted, cfg)]
                if new:
                    print()
                    log(f"{len(new)} flip(s) worth doing:")
                    for f in new:
                        print(format_flip(f), flush=True)
                        last_alerted[f["product"]] = (time.time(), f["buy_price"], f["sell_price"])
                    if cfg["discord_webhook"]:
                        send_discord(cfg["discord_webhook"], new)
                    if cfg["beep"]:
                        beep()
                else:
                    log(f"Checked {len(data['products'])} items: "
                        f"{len(flips)} flip(s) still good, nothing new.")
        except (urllib.error.URLError, ValueError, OSError, KeyError) as e:
            log(f"Bazaar fetch failed: {e}. Retrying next poll.")
        if once:
            return
        time.sleep(cfg["poll_seconds"] if changed else UNCHANGED_RETRY_SECONDS)


def main():
    p = argparse.ArgumentParser(description="Hypixel SkyBlock Bazaar flip alerts (never places orders).")
    p.add_argument("--once", action="store_true", help="check once, print flips, and exit")
    args = p.parse_args()
    try:
        run(once=args.once)
    except KeyboardInterrupt:
        sys.exit("\nStopped.")
    except json.JSONDecodeError as e:
        sys.exit(f"config.json has a typo: {e}")


if __name__ == "__main__":
    main()
