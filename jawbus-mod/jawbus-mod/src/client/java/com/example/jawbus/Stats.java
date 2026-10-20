package com.example.jawbus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Lifetime Lord Jawbus numbers: kills, every reward (own kills and loot shares), dry streaks and the expected count. */
public final class StatsScreen extends Screen {
    private final Screen parent;
    private int px, py, pw, ph;
    private final boolean confirmReset;

    public StatsScreen(Screen parent) {
        this(parent, false);
    }

    private StatsScreen(Screen parent, boolean confirmReset) {
        super(Component.literal("Jawbus Roll Stats"));
        this.parent = parent;
        this.confirmReset = confirmReset;
    }

    @Override
    protected void init() {
        Reward[] rs = Reward.values();
        pw = 420;
        ph = 24 + 44 + 12 + rs.length * 11 + 30 + 24 + 8;
        px = Math.max(4, (this.width - pw) / 2);
        py = Math.max(4, (this.height - ph) / 2);
        int by = py + ph - 22;
        this.addRenderableWidget(Button.builder(Component.literal(confirmReset ? "Click again to erase" : "Reset stats"), b -> {
            if (confirmReset) Stats.reset();
            Minecraft.getInstance().gui.setScreen(new StatsScreen(parent, !confirmReset));
        }).pos(px + 8, by).size(150, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .pos(px + pw - 158, by).size(150, 18).build());
    }

    private static String fmt(double v) {
        return v >= 10 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.1f", v);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // intentionally empty
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        Stats s = Stats.get();
        g.fill(px, py, px + pw, py + ph, 0xD8000000);
        g.fill(px, py, px + pw, py + 1, 0xFFFFAA00);
        g.fill(px, py + ph - 1, px + pw, py + ph, 0xFFFFAA00);
        g.text(this.font, this.title, px + 8, py + 8, 0xFFFFAA00, true);

        int y = py + 24;
        g.text(this.font, "Total kills: " + s.totalKills() + (s.isSynced() ? "  (Bestiary)" : "  (not synced)"),
                px + 8, y, 0xFFFFFFFF, true);
        g.text(this.font, "Loot shares received: " + s.lootShares, px + 190, y, 0xFF55FFFF, true);
        y += 11;
        g.text(this.font, "Seen by the mod: " + s.kills + ".  To sync, open Bestiary > Crimson Isle > Lord Jawbus,",
                px + 8, y, 0xFF888888, false);
        y += 10;
        g.text(this.font, "or type /jawbusRoll kills <number>. New kills are added on top.", px + 8, y, 0xFF888888, false);
        y += 13;
        int cName = px + 8, cKill = px + 150, cShare = px + 205, cSince = px + 265, cExp = px + 320, cRatio = px + 370;
        g.text(this.font, "Reward", cName, y, 0xFFAAAAAA, false);
        g.text(this.font, "Kills", cKill, y, 0xFFAAAAAA, false);
        g.text(this.font, "Share", cShare, y, 0xFFAAAAAA, false);
        g.text(this.font, "Dry", cSince, y, 0xFFAAAAAA, false);
        g.text(this.font, "Expect", cExp, y, 0xFFAAAAAA, false);
        g.text(this.font, "How dry", cRatio, y, 0xFFAAAAAA, false);
        y += 12;
        for (Reward r : Reward.values()) {
            g.text(this.font, r.displayName, cName, y, 0xFF000000 | r.rarity.rgb, true);
            g.text(this.font, String.valueOf(s.dropCount(r)), cKill, y, 0xFFFFFFFF, true);
            g.text(this.font, String.valueOf(s.shareCount(r)), cShare, y, 0xFFFFFFFF, true);
            boolean guaranteed = r.killChance() >= 100;
            g.text(this.font, guaranteed ? "-" : String.valueOf(s.killsSince(r)), cSince, y, 0xFFFFFFFF, true);
            double p = r.killChance();
            double expect = Double.isNaN(p) ? Double.NaN : s.kills * p / 100.0;
            g.text(this.font, Double.isNaN(expect) ? "-" : fmt(expect), cExp, y, 0xFFFFE9B0, true);

            String ratio = "-";
            int ratioColor = 0xFF888888;
            if (!guaranteed && expect >= 1.0) {
                double x = s.killsSince(r) / expect;
                ratio = fmt(x) + "x";
                ratioColor = x >= 3.0 ? 0xFFFF5555 : x >= 1.5 ? 0xFFFFAA00 : 0xFF55FF55;
            }
            g.text(this.font, ratio, cRatio, y, ratioColor, true);
            y += 11;
        }
        g.text(this.font, "Dry = kills since it last dropped from your own kill.  Expect = kills x drop chance.",
                px + 8, y + 4, 0xFF888888, false);
        g.text(this.font, "How dry = dry / expected. Green under 1.5x, amber from 1.5x, red from 3x.",
                px + 8, y + 14, 0xFF888888, false);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
