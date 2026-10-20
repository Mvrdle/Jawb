package com.example.jawbus;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Small corner readout: session kills, rate, drops and how dry you are. Hidden during a reveal. */
final class SessionHud {
    private SessionHud() {
    }

    static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        try {
            JawbusConfig c = JawbusConfig.get();
            if (!c.enabled || !c.sessionHud) return;
            if (Cinematic.hudHidden()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            Font f = mc.font;
            int dry = Session.dry();
            int color = dry >= 500 ? 0xFFFF5555 : dry >= 150 ? 0xFFFFAA00 : 0xFFAAAAAA;
            line(g, f, 4, 4, "kills " + Session.kills() + "  (" + Math.round(Session.perHour(Session.kills())) + "/h)", 0xFFFFFFFF);
            line(g, f, 4, 14, "drops " + Session.drops() + "   shares " + Session.lootShares(), 0xFF55FFFF);
            line(g, f, 4, 24, "dry " + dry, color);
            line(g, f, 4, 34, Session.elapsed(), 0xFF888888);
        } catch (Throwable ignored) {
            // a HUD failure must never break the game
        }
    }

    private static void line(GuiGraphicsExtractor g, Font f, int x, int y, String s, int color) {
        g.fill(x - 2, y - 1, x + f.width(s) + 2, y + 9, 0x90000000);
        g.text(f, s, x, y, color, true);
    }
}