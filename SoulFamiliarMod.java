package com.soulfamiliar;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.KeyMapping;

public class SoulFamiliarMod implements ClientModInitializer {
    public static final String MOD_ID = "soulfamiliar";
    public static KeyMapping togglePossessViewKey;
    public static KeyMapping openGrimoireKey;

    @Override
    public void onInitializeClient() {
        FamiliarConfig.getInstance().load();
        GrimoireState.getInstance().load();

        togglePossessViewKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.soulfamiliar.toggle_eyes",
                InputConstants.KEY_V,
                KeyMapping.Category.MISC
        ));

        openGrimoireKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.soulfamiliar.open_grimoire",
                InputConstants.KEY_G,
                KeyMapping.Category.MISC
        ));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("grimoire").executes(ctx -> {
                ctx.getSource().getClient().execute(() -> ctx.getSource().getClient().setScreen(new GrimoireScreen()));
                return 1;
            }));
            dispatcher.register(ClientCommandManager.literal("гримуар").executes(ctx -> {
                ctx.getSource().getClient().execute(() -> ctx.getSource().getClient().setScreen(new GrimoireScreen()));
                return 1;
            }));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (togglePossessViewKey.consumeClick()) {
                FamiliarManager.getInstance().toggleNextPossessionView(client);
            }
            while (openGrimoireKey.consumeClick()) {
                if (client.screen == null && client.player != null) {
                    client.setScreen(new GrimoireScreen());
                }
            }
            FamiliarManager.getInstance().tick(client);
            FamiliarDialogueEngine.getInstance().tick(client);
            GrimoireState.getInstance().tickClient(
                    client,
                    FamiliarManager.getInstance().getAll().size(),
                    FamiliarManager.getInstance().getTickCounter()
            );
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

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            FamiliarDialogueEngine.getInstance().onIncomingChat(message.getString(), true);
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                FamiliarDialogueEngine.getInstance().onIncomingChat(message.getString(), false);
            }
        });
    }
}
