package com.bazaarflipper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HelpersTest {
	@Test
	void parsesAmounts() {
		assertEquals(50_000_000, BazaarFlipper.parseAmount("50m"));
		assertEquals(300_000, BazaarFlipper.parseAmount("300K"));
		assertEquals(1_500_000_000, BazaarFlipper.parseAmount("1.5b"));
		assertEquals(2.5, BazaarFlipper.parseAmount("2.5"));
		assertEquals(1_000_000, BazaarFlipper.parseAmount("1,000,000"));
		assertThrows(NumberFormatException.class, () -> BazaarFlipper.parseAmount("lots"));
		assertThrows(NumberFormatException.class, () -> BazaarFlipper.parseAmount("-5"));
	}

	@Test
	void formatsCoins() {
		assertEquals("6.53M", BazaarFlipper.coins(6_527_860));
		assertEquals("4.5k", BazaarFlipper.coins(4_500));
		assertEquals("812.3", BazaarFlipper.coins(812.3));
	}

	@Test
	void prettyNamesForUnlistedItems() {
		FlipFinder f = new FlipFinder();
		assertEquals("Crop Fever V", f.prettyName("ENCHANTMENT_ULTIMATE_CROP_FEVER_5"));
		assertEquals("Sharpness VI", f.prettyName("ENCHANTMENT_SHARPNESS_6"));
		assertEquals("Dragon Essence", f.prettyName("ESSENCE_DRAGON"));
		assertEquals("Salmon Shard", f.prettyName("SHARD_SALMON"));
		assertEquals("Enchanted Diamond", f.prettyName("ENCHANTED_DIAMOND"));
	}
}
