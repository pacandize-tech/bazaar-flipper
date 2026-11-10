package com.bazaarflipper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Settings, saved to .minecraft/config/bazaarflipper.json. Same meaning as flipper.py's config.json. */
public class FlipConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final int MIN_POLL_SECONDS = 10;

	public boolean enabled = true;
	public boolean sound = true;
	public int pollSeconds = 20;
	public double budget = 100_000_000;
	public double maxCoinsPerFlip = 25_000_000;
	public double minProfitPerFlip = 300_000;
	public double minMarginPercent = 2.0;
	public double minWeeklyVolume = 200_000;
	public double maxShareOfHourlyVolume = 0.25;
	public double taxPercent = 1.25;
	public int topN = 5;
	public int realertMinutes = 30;
	public double outlierFactor = 1.5;
	public boolean useCoflnet = true;
	public List<String> blacklist = new ArrayList<>();

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("bazaarflipper.json");
	}

	public static FlipConfig load() {
		try {
			if (Files.exists(path())) {
				FlipConfig c = GSON.fromJson(Files.readString(path()), FlipConfig.class);
				if (c != null) {
					c.clamp();
					return c;
				}
			}
		} catch (IOException | RuntimeException e) {
			BazaarFlipper.LOGGER.warn("Couldn't read {}, using defaults", path(), e);
		}
		FlipConfig c = new FlipConfig();
		c.save();
		return c;
	}

	public void save() {
		try {
			Files.writeString(path(), GSON.toJson(this));
		} catch (IOException e) {
			BazaarFlipper.LOGGER.warn("Couldn't save config", e);
		}
	}

	void clamp() {
		pollSeconds = Math.max(pollSeconds, MIN_POLL_SECONDS);
		topN = Math.max(1, topN);
		if (blacklist == null) blacklist = new ArrayList<>();
	}
}
