package com.example.jawbus;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Entry point (class name kept from the old slot-machine so existing project files keep working).
 * Purely client-side. The mod makes no network connections; its only file is config/jawbusslots.json.
 */
public final class JawbusSlotMachine implements ClientModInitializer {
    static final String MOD_ID = "jawbusslots";
    private static boolean openSettings;

    @Override
    public void onInitializeClient() {
        JawbusConfig.load();

        // chat: loot shares + drop lines (action-bar text is ignored)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) KillTracker.onChat(message.getString());
        });

        // a Lord Jawbus nameplate disappearing near you = a kill
        ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> KillTracker.onUnload(entity));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (openSettings) { // opened a tick later: a screen opened straight from a chat command gets closed again
                openSettings = false;
                mc.gui.setScreen(new JawbusConfigScreen());
            }
            KillTracker.tick(mc);
            Cinematic.tick(mc);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            KillTracker.reset();
            Cinematic.reset();
        });

        // /jawbusRoll opens the settings; extras: preview <1-4>, textures
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("jawbusRoll")
                        .executes(ctx -> {
                            openSettings = true;
                            return 1;
                        })
                        .then(ClientCommands.literal("preview")
                                .then(ClientCommands.argument("tier", IntegerArgumentType.integer(1, 4))
                                        .executes(ctx -> {
                                            Cinematic.preview(IntegerArgumentType.getInteger(ctx, "tier"));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("textures")
                                .executes(ctx -> {
                                    Icons.refresh();
                                    for (Reward r : Reward.values()) {
                                        ctx.getSource().sendFeedback(Component.literal(r.displayName + ": " + Icons.status(r)));
                                    }
                                    return 1;
                                }))));

        // HUD element: drawn straight onto the HUD, so nothing is paused and no input is captured
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "cinematic"),
                Cinematic::render);
    }
}
