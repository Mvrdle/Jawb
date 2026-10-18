package com.example.jawbus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Timing menu: how fast the stages build, how long the still pauses are, how long the golem holds before it pops
 * and how long the reward stays on screen. The preview buttons close the menu and play that tier with these settings.
 */
public final class TimingScreen extends Screen {
    private final Screen parent;
    private int px, py, pw, ph;

    public TimingScreen(Screen parent) {
        super(Component.literal("Jawbus Roll Timings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        JawbusConfig c = JawbusConfig.get();
        int colW = 150, gap = 8;
        int step = Math.max(16, Math.min(22, (this.height - 50) / 9));
        int bh = step - 2;
        pw = colW * 2 + gap * 3;
        ph = 24 + step * 6 + 8;
        px = Math.max(4, (this.width - pw) / 2);
        py = Math.max(4, (this.height - ph) / 2);
        int lx = px + gap, rx = px + gap * 2 + colW;
        int y = py + 22;

        slide(lx, y, colW, bh, "Build-up speed", 0.5, 2.0, c.buildSpeed, v -> c.buildSpeed = (float) v);
        slide(lx, y + step, colW, bh, "Pause length", 0.2, 2.5, c.pauseScale, v -> c.pauseScale = (float) v);
        slide(lx, y + step * 2, colW, bh, "Hold before pop", 0.3, 2.5, c.finalHold, v -> c.finalHold = (float) v);
        slide(lx, y + step * 3, colW, bh, "Reward stays", 0.5, 3.0, c.rewardTime, v -> c.rewardTime = (float) v);
        slide(lx, y + step * 4, colW, bh, "Overall length", 0.6, 2.0, c.duration, v -> c.duration = (float) v);

        int half = (colW - 4) / 2;
        preview(rx, y, half, bh, "Tier 1", 1);
        preview(rx + half + 4, y, half, bh, "Tier 2", 2);
        preview(rx, y + step, half, bh, "Tier 3", 3);
        preview(rx + half + 4, y + step, half, bh, "Dye (max)", 4);
        this.addRenderableWidget(Button.builder(Component.literal("Reset timings"), b -> {
            c.buildSpeed = 1f;
            c.pauseScale = 1f;
            c.finalHold = 1f;
            c.rewardTime = 1f;
            c.duration = 1f;
            Minecraft.getInstance().gui.setScreen(new TimingScreen(parent));
        }).pos(rx, y + step * 3).size(colW, bh).build());
        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .pos(rx, y + step * 5).size(colW, bh).build());
    }

    private void slide(int x, int y, int w, int h, String label, double min, double max, double cur,
                       java.util.function.DoubleConsumer set) {
        this.addRenderableWidget(new JawbusConfigScreen.PercentSlider(x, y, w, h, label, min, max, cur, set));
    }

    private void preview(int x, int y, int w, int h, String label, int mode) {
        this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
            JawbusConfig.save();
            Minecraft.getInstance().gui.setScreen(null);
            Cinematic.preview(mode);
        }).pos(x, y).size(w, h).build());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // intentionally empty, like the main menu
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(px, py, px + pw, py + ph, 0xC8000000);
        graphics.fill(px, py, px + pw, py + 1, 0xFFFFAA00);
        graphics.fill(px, py + ph - 1, px + pw, py + ph, 0xFFFFAA00);
        graphics.text(this.font, this.title, px + 8, py + 8, 0xFFFFAA00, true);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        JawbusConfig.save();
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
