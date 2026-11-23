package com.soulfamiliar;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.KeyMapping;

public class SoulFamiliarMod implements ClientModInitializer {
    public static final String MOD_ID = "soulfamiliar";
    public static KeyMapping togglePossessViewKey;

    @Override
    public void onInitializeClient() {
        togglePossessViewKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.soulfamiliar.toggle_eyes",
                InputConstants.KEY_V,
                KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (togglePossessViewKey.consumeClick()) {
                FamiliarManager.getInstance().toggleNextPossessionView(client);
            }
            FamiliarManager.getInstance().tick(client);
        });

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            String trimmed = message.trim();
            if (trimmed.startsWith(".f ") || trimmed.equalsIgnoreCase(".f")) {
                String silentCmd = trimmed.length() > 2 ? trimmed.substring(2).trim() : "";
                ChatCommandParser.handleMessage(silentCmd, true);
                return false;
            }
            ChatCommandParser.handleMessage(trimmed, false);
            return true;
        });
    }
}
