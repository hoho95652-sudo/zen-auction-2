package com.example.donutauction;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class DonutAuctionClient implements ClientModInitializer {
    public static KeyMapping openKey;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("donutauction", "main"));
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.donutauction.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.consumeClick()) {
                if (client.player != null && client.screen == null) {
                    client.setScreenAndShow(new AuctionScreen());
                }
            }
            AuctionManager.tick(client);
        });

        // Servers deliver chat either as signed chat or as system/game messages; listen to both.
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (!overlay) AuctionManager.onChat(msg.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) ->
                AuctionManager.onChat(msg.getString()));
    }
}
