package com.bazaarflipper;

import com.bazaarflipper.FlipFinder.Flip;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bazaar flip alerts in chat.
 *
 * Display only: it reads Hypixel's public Bazaar prices and prints suggestions
 * for you. It never clicks, never opens menus and never places orders. The only
 * thing it sends to Hypixel is a /bz command, and only when you click an item name.
 */
public class BazaarFlipper implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("bazaarflipper");
	private static final Component PREFIX = Component.literal("[Flip] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
	/** Settings you can change with /flips set. */
	private static final List<String> SETTABLE = List.of("budget", "maxCoinsPerFlip", "minProfitPerFlip",
			"minMarginPercent", "minWeeklyVolume", "maxShareOfHourlyVolume", "taxPercent", "topN",
			"pollSeconds", "realertMinutes", "outlierFactor");

	/** Hypixel refreshes the Bazaar about every 20s; if we checked just before a refresh, look again soon. */
	private static final long UNCHANGED_RETRY_SECONDS = 5;

	private static volatile FlipConfig config;
	private static volatile boolean inWorld;
	private static final FlipFinder finder = new FlipFinder();
	private static final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "bazaarflipper-poll");
		t.setDaemon(true);
		return t;
	});

	// Worker-thread state
	private static long lastUpdated;
	private static boolean apiDown;

	// Game-thread state
	private static final Map<String, double[]> lastAlerted = new HashMap<>(); // pid -> {time, buy, sell}
	private static List<Flip> current = List.of();
	private static int alertsShown;

	/** For the in-game test. */
	public static int alertsShown() {
		return alertsShown;
	}

	@Override
	public void onInitializeClient() {
		config = FlipConfig.load();
		ClientTickEvents.END_CLIENT_TICK.register(mc -> inWorld = mc.player != null);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
				ClientCommands.literal("flips")
						.executes(ctx -> { showCurrent(); return 1; })
						.then(ClientCommands.literal("on").executes(ctx -> { setEnabled(true); return 1; }))
						.then(ClientCommands.literal("off").executes(ctx -> { setEnabled(false); return 1; }))
						.then(ClientCommands.literal("sound").executes(ctx -> { toggleSound(); return 1; }))
						.then(ClientCommands.literal("settings").executes(ctx -> { showSettings(); return 1; }))
						.then(ClientCommands.literal("reload").executes(ctx -> {
							config = FlipConfig.load();
							say(Component.literal("Settings reloaded.").withStyle(ChatFormatting.GREEN));
							return 1;
						}))
						.then(ClientCommands.literal("set")
								.then(ClientCommands.argument("setting", StringArgumentType.word())
										.suggests((ctx, b) -> {
											SETTABLE.forEach(b::suggest);
											return b.buildFuture();
										})
										.then(ClientCommands.argument("value", StringArgumentType.word())
												.executes(ctx -> {
													set(StringArgumentType.getString(ctx, "setting"), StringArgumentType.getString(ctx, "value"));
													return 1;
												}))))));
		worker.schedule(BazaarFlipper::pollLoop, 5, TimeUnit.SECONDS);
		LOGGER.info("Bazaar Flipper loaded");
	}

	// ------------------------------------------------------------ polling (worker thread)

	private static void pollLoop() {
		FlipConfig cfg = config;
		long delay = cfg.pollSeconds;
		try {
			if (inWorld && cfg.enabled) {
				FlipFinder.Result result = finder.poll(cfg, lastUpdated);
				if (apiDown) {
					apiDown = false;
					Minecraft.getInstance().execute(() -> say(Component.literal("Bazaar API is back.").withStyle(ChatFormatting.GREEN)));
				}
				if (result != null) {
					lastUpdated = result.lastUpdated();
					Minecraft.getInstance().execute(() -> onResult(result));
				} else {
					delay = UNCHANGED_RETRY_SECONDS;
				}
			}
		} catch (Exception e) {
			LOGGER.warn("Bazaar poll failed: {}", e.toString());
			if (!apiDown) {
				apiDown = true;
				Minecraft.getInstance().execute(() -> say(Component.literal("Can't reach the Bazaar API right now - I'll keep trying.")
						.withStyle(ChatFormatting.RED)));
			}
		} finally {
			worker.schedule(BazaarFlipper::pollLoop, delay, TimeUnit.SECONDS);
		}
	}

	// ------------------------------------------------------------ alerts (game thread)

	private static void onResult(FlipFinder.Result result) {
		current = result.flips();
		FlipConfig cfg = config;
		long now = System.currentTimeMillis();
		List<Flip> fresh = new ArrayList<>();
		for (Flip f : result.flips()) {
			double[] prev = lastAlerted.get(f.product());
			boolean repeat = prev != null
					&& now - prev[0] < cfg.realertMinutes * 60_000L
					&& Math.abs(f.buyPrice() - prev[1]) / prev[1] <= 0.03
					&& Math.abs(f.sellPrice() - prev[2]) / prev[2] <= 0.03;
			if (!repeat) {
				fresh.add(f);
				lastAlerted.put(f.product(), new double[]{now, f.buyPrice(), f.sellPrice()});
			}
		}
		if (fresh.isEmpty()) return;
		for (Flip f : fresh) showFlip(f);
		alertsShown += fresh.size();
		Minecraft mc = Minecraft.getInstance();
		if (cfg.sound && mc.player != null) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
	}

	private static void showFlip(Flip f) {
		Component details = Component.literal(f.name() + "\n").withStyle(ChatFormatting.AQUA)
				.append(Component.literal(String.format(Locale.US,
						"Profit: %s/each, %.2f%% margin (after tax)\nCost: %s\nTop buy order now: %s\nLowest sell offer now: %s\n"
								+ "Size: %d%% of the ~%,d that trade per hour\n\nClick to open /bz %s",
						coins(f.profitEach()), f.marginPercent(), coins(f.cost()), price(f.topBuyOrder()),
						price(f.lowestSellOffer()), Math.round(f.quantity() * 100.0 / Math.max(1, f.hourlyVolume())),
						f.hourlyVolume(), f.name())).withStyle(ChatFormatting.GRAY));

		say(Component.literal(f.name()).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true)
						.withClickEvent(new ClickEvent.RunCommand("/bz " + f.name()))
						.withHoverEvent(new HoverEvent.ShowText(details)))
				.append(Component.literal("  +" + coins(f.profitTotal()) + " profit").withStyle(ChatFormatting.GREEN)));
		chat(Component.literal("  Buy order: ").withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(String.format(Locale.US, "%,d @ %s ", f.quantity(), price(f.buyPrice()))).withStyle(ChatFormatting.WHITE))
				.append(copyButton(f.buyPrice())));
		chat(Component.literal("  Sell offer: ").withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(String.format(Locale.US, "%,d @ %s ", f.quantity(), price(f.sellPrice()))).withStyle(ChatFormatting.WHITE))
				.append(copyButton(f.sellPrice())));
		if (!f.note().isEmpty()) chat(Component.literal("  " + f.note()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
	}

	/** [copy] puts the exact price on your clipboard so you can paste it into the custom price sign. */
	private static MutableComponent copyButton(double value) {
		String text = String.format(Locale.US, "%.1f", value);
		return Component.literal("[copy]").withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)
				.withClickEvent(new ClickEvent.CopyToClipboard(text))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy " + text))));
	}

	// ------------------------------------------------------------ commands

	private static void showCurrent() {
		if (!config.enabled) {
			say(Component.literal("Alerts are off. Type /flips on to start.").withStyle(ChatFormatting.GRAY));
		} else if (current.isEmpty()) {
			say(Component.literal(lastUpdated == 0 ? "Still loading the Bazaar - give it a few seconds."
					: "No flips pass your filters right now.").withStyle(ChatFormatting.GRAY));
		} else {
			say(Component.literal("Current best flips:").withStyle(ChatFormatting.YELLOW));
			current.forEach(BazaarFlipper::showFlip);
		}
	}

	private static void setEnabled(boolean on) {
		config.enabled = on;
		config.save();
		say(Component.literal(on ? "Alerts ON" : "Alerts OFF (type /flips on to resume)")
				.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
	}

	private static void toggleSound() {
		config.sound = !config.sound;
		config.save();
		say(Component.literal("Alert sound " + (config.sound ? "ON" : "OFF")).withStyle(ChatFormatting.GRAY));
	}

	private static void showSettings() {
		say(Component.literal("Settings (change with /flips set <setting> <value>, e.g. /flips set budget 50m):")
				.withStyle(ChatFormatting.YELLOW));
		for (String key : SETTABLE) {
			try {
				Object v = FlipConfig.class.getField(key).get(config);
				String shown = v instanceof Double d && d >= 1000 ? coins(d) : String.valueOf(v);
				chat(Component.literal("  " + key + ": ").withStyle(ChatFormatting.GRAY)
						.append(Component.literal(shown).withStyle(ChatFormatting.WHITE)));
			} catch (ReflectiveOperationException ignored) {
			}
		}
		chat(Component.literal("  Blacklist and everything else: " + FlipConfig.path()).withStyle(ChatFormatting.DARK_GRAY));
	}

	private static void set(String key, String raw) {
		String match = SETTABLE.stream().filter(k -> k.equalsIgnoreCase(key)).findFirst().orElse(null);
		if (match == null) {
			say(Component.literal("Unknown setting '" + key + "'. Try /flips settings.").withStyle(ChatFormatting.RED));
			return;
		}
		double value;
		try {
			value = parseAmount(raw);
		} catch (NumberFormatException e) {
			say(Component.literal("'" + raw + "' isn't a number. Examples: 50m, 300k, 2.5").withStyle(ChatFormatting.RED));
			return;
		}
		try {
			Field field = FlipConfig.class.getField(match);
			if (field.getType() == int.class) field.setInt(config, (int) value);
			else field.setDouble(config, value);
		} catch (ReflectiveOperationException e) {
			LOGGER.warn("Couldn't set {}", match, e);
			return;
		}
		config.clamp();
		config.save();
		say(Component.literal(match + " set to " + raw + ". Applies from the next check.").withStyle(ChatFormatting.GREEN));
	}

	/** "50m" -> 50,000,000; "300k" -> 300,000; "1.5b"; "2.5" -> 2.5. */
	static double parseAmount(String raw) {
		String s = raw.trim().toLowerCase(Locale.ROOT).replace(",", "");
		double mult = 1;
		if (s.endsWith("k")) mult = 1e3;
		else if (s.endsWith("m")) mult = 1e6;
		else if (s.endsWith("b")) mult = 1e9;
		if (mult != 1) s = s.substring(0, s.length() - 1);
		double v = Double.parseDouble(s) * mult;
		if (v < 0 || Double.isNaN(v) || Double.isInfinite(v)) throw new NumberFormatException(raw);
		return v;
	}

	// ------------------------------------------------------------ helpers

	private static void say(Component message) {
		chat(PREFIX.copy().append(message));
	}

	/** Client-side only: the message is shown to you, nothing is sent to the server. */
	private static void chat(Component message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) mc.player.sendSystemMessage(message);
	}

	private static String price(double v) {
		return String.format(Locale.US, "%,.1f", v);
	}

	static String coins(double n) {
		double a = Math.abs(n);
		if (a >= 1e9) return trim(n / 1e9) + "B";
		if (a >= 1e6) return trim(n / 1e6) + "M";
		if (a >= 1e3) return trim(n / 1e3) + "k";
		return price(n);
	}

	private static String trim(double v) {
		return String.format(Locale.US, "%.2f", v).replaceAll("\\.?0+$", "");
	}
}
