package com.bazaarflipper.test;

import com.bazaarflipper.BazaarFlipper;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.ChatScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Joins a world, waits for live Bazaar alerts to land in chat, and screenshots them. Needs internet. */
public class BazaarFlipperClientGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("bazaarflipper-test");

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			int waited = 0;
			while (context.computeOnClient(mc -> BazaarFlipper.alertsShown()) == 0 && waited < 20 * 90) {
				context.waitTicks(20);
				waited += 20;
			}
			int shown = context.computeOnClient(mc -> BazaarFlipper.alertsShown());
			LOGGER.info("Flip alerts shown in chat: {} (after {}s)", shown, waited / 20);
			if (shown == 0) throw new AssertionError("no flip alerts reached chat within 90s");
			context.runOnClient(mc -> mc.setScreen(new ChatScreen("", false)));
			context.waitTicks(10);
			context.takeScreenshot("bazaarflipper-chat");
		}
	}
}
