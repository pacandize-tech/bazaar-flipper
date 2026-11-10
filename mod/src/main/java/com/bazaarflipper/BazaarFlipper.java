package com.bazaarflipper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Shows Bazaar flip alerts from flipper.py in chat.
 *
 * Display only. It reads alerts from flipper.py on this PC (127.0.0.1) and
 * prints them for you. It never clicks, never opens menus and never places
 * orders. The only thing that reaches Hypixel is a /bz command, and only
 * when you click an item name yourself.
 */
public class BazaarFlipper implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("bazaarflipper");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int POLL_TICKS = 100; // 5 seconds
	private static final Component PREFIX = Component.literal("[Flip] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	private static Config config = new Config();
	private static int ticks;
	private static volatile boolean requestInFlight;
	private static long lastAlertId;
	private static boolean connected = true; // assume yes so the first failure is reported once

	public static class Config {
		int port = 8765;
		boolean enabled = true;
		boolean sound = true;
	}

	@Override
	public void onInitializeClient() {
		loadConfig();
		ClientTickEvents.END_CLIENT_TICK.register(BazaarFlipper::onTick);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
				ClientCommands.literal("flips")
						.executes(ctx -> { showCurrent(); return 1; })
						.then(ClientCommands.literal("on").executes(ctx -> { setEnabled(true); return 1; }))
						.then(ClientCommands.literal("off").executes(ctx -> { setEnabled(false); return 1; }))
						.then(ClientCommands.literal("sound").executes(ctx -> { toggleSound(); return 1; }))));
		LOGGER.info("Bazaar Flipper alerts loaded (port {})", config.port);
	}

	// ------------------------------------------------------------ polling

	private static void onTick(Minecraft mc) {
		if (mc.player == null || !config.enabled || requestInFlight) return;
		if (++ticks < POLL_TICKS) return;
		ticks = 0;
		fetch("/alerts?after=" + lastAlertId, json -> {
			JsonArray alerts = json.getAsJsonArray("alerts");
			if (alerts == null || alerts.isEmpty()) return;
			for (JsonElement e : alerts) {
				JsonObject flip = e.getAsJsonObject();
				lastAlertId = Math.max(lastAlertId, flip.get("id").getAsLong());
				showFlip(flip);
			}
			if (config.sound && mc.player != null) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
		});
	}

	/** Fetches from flipper.py off the game thread, then runs onResult on the game thread. */
	private static void fetch(String path, Consumer<JsonObject> onResult) {
		requestInFlight = true;
		HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + config.port + path))
				.timeout(Duration.ofSeconds(3)).GET().build();
		HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((resp, err) -> {
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> {
				requestInFlight = false;
				if (err != null || resp.statusCode() != 200) {
					if (connected) {
						connected = false;
						say(Component.literal("Can't reach flipper.py on this PC. Start it with run.bat - I'll keep checking.")
								.withStyle(ChatFormatting.RED));
					}
					return;
				}
				if (!connected) {
					connected = true;
					say(Component.literal("Connected to flipper.py.").withStyle(ChatFormatting.GREEN));
				}
				try {
					onResult.accept(JsonParser.parseString(resp.body()).getAsJsonObject());
				} catch (RuntimeException e) {
					LOGGER.warn("Bad response from flipper.py", e);
				}
			});
		});
	}

	// ------------------------------------------------------------ chat

	private static void showFlip(JsonObject f) {
		String name = f.get("name").getAsString();
		long qty = f.get("quantity").getAsLong();
		double buy = f.get("buy_price").getAsDouble();
		double sell = f.get("sell_price").getAsDouble();
		String note = f.has("note") ? f.get("note").getAsString() : "";

		Component details = Component.literal(name + "\n").withStyle(ChatFormatting.AQUA)
				.append(Component.literal(String.format(Locale.US,
						"Profit: %s/each, %.2f%% margin (after tax)\nCost: %s\nTop buy order now: %s\nLowest sell offer now: %s\n\nClick to open /bz %s",
						coins(f.get("profit_each").getAsDouble()), f.get("margin_percent").getAsDouble(),
						coins(f.get("cost").getAsDouble()), price(f.get("top_buy_order").getAsDouble()),
						price(f.get("lowest_sell_offer").getAsDouble()), name)).withStyle(ChatFormatting.GRAY));

		say(Component.literal(name).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true)
						.withClickEvent(new ClickEvent.RunCommand("/bz " + name))
						.withHoverEvent(new HoverEvent.ShowText(details)))
				.append(Component.literal("  +" + coins(f.get("profit_total").getAsDouble()) + " profit")
						.withStyle(ChatFormatting.GREEN)));
		chat(Component.literal("  Buy order: ").withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(String.format(Locale.US, "%,d @ %s ", qty, price(buy))).withStyle(ChatFormatting.WHITE))
				.append(copyButton(buy))
				.append(Component.literal("  Sell offer: ").withStyle(ChatFormatting.YELLOW))
				.append(Component.literal(String.format(Locale.US, "%,d @ %s ", qty, price(sell))).withStyle(ChatFormatting.WHITE))
				.append(copyButton(sell)));
		if (!note.isEmpty()) chat(Component.literal("  " + note).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
	}

	/** [copy] puts the exact price on your clipboard so you can paste it into the custom price sign. */
	private static MutableComponent copyButton(double value) {
		String text = String.format(Locale.US, "%.1f", value);
		return Component.literal("[copy]").withStyle(Style.EMPTY.withColor(ChatFormatting.DARK_AQUA)
				.withClickEvent(new ClickEvent.CopyToClipboard(text))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy " + text))));
	}

	private static void showCurrent() {
		fetch("/flips", json -> {
			JsonArray flips = json.getAsJsonArray("flips");
			if (flips == null || flips.isEmpty()) {
				say(Component.literal("No flips pass your filters right now.").withStyle(ChatFormatting.GRAY));
				return;
			}
			say(Component.literal("Current best flips:").withStyle(ChatFormatting.YELLOW));
			for (JsonElement e : flips) showFlip(e.getAsJsonObject());
		});
	}

	private static void setEnabled(boolean on) {
		config.enabled = on;
		saveConfig();
		say(Component.literal(on ? "Alerts ON" : "Alerts OFF (type /flips on to resume)")
				.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
	}

	private static void toggleSound() {
		config.sound = !config.sound;
		saveConfig();
		say(Component.literal("Alert sound " + (config.sound ? "ON" : "OFF")).withStyle(ChatFormatting.GRAY));
	}

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

	private static String coins(double n) {
		double a = Math.abs(n);
		if (a >= 1e9) return trim(n / 1e9) + "B";
		if (a >= 1e6) return trim(n / 1e6) + "M";
		if (a >= 1e3) return trim(n / 1e3) + "k";
		return price(n);
	}

	private static String trim(double v) {
		return String.format(Locale.US, "%.2f", v).replaceAll("\\.?0+$", "");
	}

	// ------------------------------------------------------------ config

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("bazaarflipper.json");
	}

	private static void loadConfig() {
		Path path = configPath();
		try {
			if (Files.exists(path)) {
				Config loaded = GSON.fromJson(Files.readString(path), Config.class);
				if (loaded != null) config = loaded;
			} else {
				saveConfig();
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Couldn't read {}, using defaults", path, e);
		}
	}

	private static void saveConfig() {
		try {
			Files.writeString(configPath(), GSON.toJson(config));
		} catch (IOException e) {
			LOGGER.warn("Couldn't save config", e);
		}
	}
}
