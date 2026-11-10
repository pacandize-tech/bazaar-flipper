package com.bazaarflipper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the public Bazaar API and works out flips. Same maths as flipper.py.
 * Read-only: it downloads prices and nothing else.
 */
public class FlipFinder {
	private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
	private static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
	private static final String COFL_URL = "https://sky.coflnet.com/api/bazaar/%s/history/day";
	private static final int MAX_ORDER_AMOUNT = 71680;
	private static final double HOURS_PER_WEEK = 168;
	private static final double PRICE_STEP = 0.1;
	private static final long COFL_CACHE_MS = 20 * 60 * 1000;
	private static final Pattern LEVEL = Pattern.compile("^(.*)_(\\d+)$");
	private static final String[] ROMAN = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

	public record Flip(String product, String name, double buyPrice, double sellPrice, double topBuyOrder,
					   double lowestSellOffer, long quantity, double profitEach, double profitTotal, double cost,
					   double marginPercent, long hourlyVolume, String note) {
		Flip withQuantity(long q) {
			return new Flip(product, name, buyPrice, sellPrice, topBuyOrder, lowestSellOffer, q, profitEach,
					q * profitEach, q * buyPrice, marginPercent, hourlyVolume, note);
		}

		Flip withNote(String n) {
			return new Flip(product, name, buyPrice, sellPrice, topBuyOrder, lowestSellOffer, quantity, profitEach,
					profitTotal, cost, marginPercent, hourlyVolume, n);
		}
	}

	/** One poll's result. lastUpdated lets the caller skip unchanged snapshots. */
	public record Result(long lastUpdated, int itemCount, List<Flip> flips, List<String> skipped) {}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final Map<String, String> names = new HashMap<>();
	private final Map<String, ArrayDeque<double[]>> localHistory = new HashMap<>();
	private final Map<String, Object[]> coflCache = new HashMap<>(); // pid -> {fetchedAt, double[]{order, offer} or null}
	private long coflFailedUntil;
	private boolean namesLoaded;

	private JsonElement get(String url) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
				.header("User-Agent", "bazaarflipper-mod/1.0 (alerts only)").GET().build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		if (resp.statusCode() != 200) throw new IllegalStateException("HTTP " + resp.statusCode());
		return JsonParser.parseString(resp.body());
	}

	private void loadNames() {
		namesLoaded = true;
		try {
			for (JsonElement e : get(ITEMS_URL).getAsJsonObject().getAsJsonArray("items")) {
				JsonObject item = e.getAsJsonObject();
				names.put(item.get("id").getAsString(), item.get("name").getAsString().replaceAll("§.", ""));
			}
		} catch (Exception e) {
			BazaarFlipper.LOGGER.warn("Couldn't load item names, using IDs", e);
		}
	}

	String prettyName(String pid) {
		String known = names.get(pid);
		if (known != null) return known;
		String words = pid;
		String level = "";
		Matcher m = LEVEL.matcher(words);
		if (m.matches() && words.startsWith("ENCHANTMENT_")) {
			words = m.group(1);
			int n = Integer.parseInt(m.group(2));
			level = " " + (n < ROMAN.length ? ROMAN[n] : String.valueOf(n));
		}
		if (words.startsWith("ENCHANTMENT_ULTIMATE_")) words = words.substring(21);
		else if (words.startsWith("ENCHANTMENT_")) words = words.substring(12);
		else if (words.startsWith("ESSENCE_")) words = words.substring(8) + "_ESSENCE";
		else if (words.startsWith("SHARD_")) words = words.substring(6) + "_SHARD";
		StringBuilder sb = new StringBuilder();
		for (String w : words.toLowerCase(Locale.ROOT).split("_")) {
			if (w.isEmpty()) continue;
			if (!sb.isEmpty()) sb.append(' ');
			sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return sb + level;
	}

	/** Downloads the Bazaar. Returns null if the snapshot hasn't changed since previousUpdate. */
	public Result poll(FlipConfig cfg, long previousUpdate) throws Exception {
		if (!namesLoaded) loadNames();
		JsonObject data = get(BAZAAR_URL).getAsJsonObject();
		if (!data.get("success").getAsBoolean()) throw new IllegalStateException("Bazaar API said success=false");
		long lastUpdated = data.get("lastUpdated").getAsLong();
		if (lastUpdated == previousUpdate) return null;
		JsonObject products = data.getAsJsonObject("products");

		double tax = cfg.taxPercent / 100;
		List<Flip> candidates = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : products.entrySet()) {
			String pid = entry.getKey();
			JsonObject p = entry.getValue().getAsJsonObject();
			JsonArray orders = p.getAsJsonArray("sell_summary"); // buy orders, highest first
			JsonArray offers = p.getAsJsonArray("buy_summary");  // sell offers, lowest first
			if (orders == null || offers == null || orders.isEmpty() || offers.isEmpty()) continue;
			double topOrder = orders.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble();
			double lowOffer = offers.get(0).getAsJsonObject().get("pricePerUnit").getAsDouble();
			ArrayDeque<double[]> hist = localHistory.computeIfAbsent(pid, k -> new ArrayDeque<>());
			hist.addLast(new double[]{topOrder, lowOffer});
			if (hist.size() > 1440) hist.removeFirst();
			if (cfg.blacklist.stream().anyMatch(pid::equalsIgnoreCase)) continue;

			double buyAt = round1(topOrder + PRICE_STEP);
			double sellAt = round1(lowOffer - PRICE_STEP);
			if (sellAt <= buyAt) continue;
			double profitEach = sellAt * (1 - tax) - buyAt;
			double margin = profitEach / buyAt * 100;
			if (profitEach <= 0 || margin < cfg.minMarginPercent) continue;

			JsonObject q = p.getAsJsonObject("quick_status");
			// Your buy order fills from insta-sells, your sell offer from insta-buys; the slower side limits you.
			double weekly = Math.min(q.get("sellMovingWeek").getAsDouble(), q.get("buyMovingWeek").getAsDouble());
			if (weekly < cfg.minWeeklyVolume) continue;
			double hourly = weekly / HOURS_PER_WEEK;
			long qty = (long) Math.floor(Math.min(Math.min(cfg.maxCoinsPerFlip / buyAt,
					hourly * cfg.maxShareOfHourlyVolume), MAX_ORDER_AMOUNT));
			if (qty < 1 || qty * profitEach < cfg.minProfitPerFlip) continue;

			candidates.add(new Flip(pid, prettyName(pid), buyAt, sellAt, topOrder, lowOffer, qty, round1(profitEach),
					qty * profitEach, qty * buyAt, Math.round(margin * 100) / 100.0, Math.round(hourly), ""));
		}
		candidates.sort(Comparator.comparingDouble(Flip::profitTotal).reversed());

		List<Flip> flips = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		double budgetLeft = cfg.budget;
		for (Flip f : candidates.subList(0, Math.min(candidates.size(), cfg.topN * 4))) {
			if (flips.size() >= cfg.topN) break;
			double[] med = medians(f.product(), cfg);
			if (med != null) {
				if (f.lowestSellOffer() > med[1] * cfg.outlierFactor) {
					skipped.add(f.product());
					continue;
				}
				double usual = med[1] - med[0];
				double spread = f.lowestSellOffer() - f.topBuyOrder();
				if (usual > 0 && spread > usual * 2) {
					f = f.withNote(String.format(Locale.US, "spread is %.1fx its usual - may not last", spread / usual));
				}
			} else {
				f = f.withNote("no price history yet");
			}
			if (f.cost() > budgetLeft) {
				f = f.withQuantity((long) Math.floor(budgetLeft / f.buyPrice()));
				if (f.profitTotal() < cfg.minProfitPerFlip) continue;
			}
			budgetLeft -= f.cost();
			flips.add(f);
		}
		return new Result(lastUpdated, products.size(), flips, skipped);
	}

	/** {median top buy order, median lowest sell offer} from Coflnet's 24h history, or local history. */
	private double[] medians(String pid, FlipConfig cfg) {
		long now = System.currentTimeMillis();
		if (cfg.useCoflnet && now >= coflFailedUntil) {
			Object[] cached = coflCache.get(pid);
			if (cached == null || now - (long) cached[0] > COFL_CACHE_MS) {
				cached = new Object[]{now, fetchCofl(pid)};
				coflCache.put(pid, cached);
			}
			if (cached[1] != null) return (double[]) cached[1];
		}
		ArrayDeque<double[]> hist = localHistory.get(pid);
		if (hist == null || hist.size() < 10) return null;
		return new double[]{median(hist.stream().mapToDouble(h -> h[0]).toArray()),
				median(hist.stream().mapToDouble(h -> h[1]).toArray())};
	}

	private double[] fetchCofl(String pid) {
		try {
			JsonArray rows = get(String.format(COFL_URL, URLEncoder.encode(pid, StandardCharsets.UTF_8))).getAsJsonArray();
			// Coflnet: "sell" = top buy order, "buy" = lowest sell offer
			double[] orders = rows.asList().stream().map(JsonElement::getAsJsonObject)
					.filter(r -> r.has("sell") && !r.get("sell").isJsonNull()).mapToDouble(r -> r.get("sell").getAsDouble()).toArray();
			double[] offers = rows.asList().stream().map(JsonElement::getAsJsonObject)
					.filter(r -> r.has("buy") && !r.get("buy").isJsonNull()).mapToDouble(r -> r.get("buy").getAsDouble()).toArray();
			if (orders.length < 10 || offers.length < 10) return null;
			return new double[]{median(orders), median(offers)};
		} catch (Exception e) {
			BazaarFlipper.LOGGER.warn("Coflnet unavailable, using local history for 5 min: {}", e.toString());
			coflFailedUntil = System.currentTimeMillis() + 5 * 60 * 1000;
			return null;
		}
	}

	private static double median(double[] v) {
		double[] s = v.clone();
		Arrays.sort(s);
		int n = s.length;
		return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2;
	}

	private static double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}
}
