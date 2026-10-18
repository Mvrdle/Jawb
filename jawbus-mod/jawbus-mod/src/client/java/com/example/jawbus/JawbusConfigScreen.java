package com.example.jawbus;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * Settings menu, opened with /jawbusRoll. The background is left clear and the game keeps running,
 * so the preview buttons play the animation live behind the panel while you adjust the sliders.
 */
public final class JawbusConfigScreen extends Screen {
    private int px, py, pw, ph;

    public JawbusConfigScreen() {
        super(Component.literal("Jawbus Roll Settings"));
    }

    @Override
    protected void init() {
        JawbusConfig c = JawbusConfig.get();
        int colW = 150, gap = 8;
        int step = Math.max(16, Math.min(22, (this.height - 50) / 10));
        int bh = step - 2;
        pw = colW * 2 + gap * 3;
        ph = 24 + step * 9 + 8;
        px = Math.max(4, (this.width - pw) / 2);
        py = Math.max(4, (this.height - ph) / 2);
        int lx = px + gap, rx = px + gap * 2 + colW;
        int y = py + 22;

        // left column: sliders
        slider(lx, y, colW, bh, "Animation scale", 0.5, 1.5, c.scale, v -> c.scale = (float) v);
        slider(lx, y + step, colW, bh, "Duration", 0.6, 2.0, c.duration, v -> c.duration = (float) v);
        slider(lx, y + step * 2, colW, bh, "Opacity", 0.2, 1.0, c.opacity, v -> c.opacity = (float) v);
        slider(lx, y + step * 3, colW, bh, "Regular volume", 0.0, 1.5, c.regularVolume, v -> c.regularVolume = (float) v);
        slider(lx, y + step * 4, colW, bh, "Jackpot volume", 0.0, 1.5, c.jackpotVolume, v -> c.jackpotVolume = (float) v);
        slider(lx, y + step * 5, colW, bh, "Shake intensity", 0.0, 1.5, c.shakeIntensity, v -> c.shakeIntensity = (float) v);

        // right column: toggles, previews, done
        toggle(rx, y, colW, bh, "Mod enabled", () -> c.enabled, () -> c.enabled = !c.enabled);
        toggle(rx, y + step, colW, bh, "Loot share spins", () -> c.lootShare, () -> c.lootShare = !c.lootShare);
        toggle(rx, y + step * 2, colW, bh, "Screen shake", () -> c.shakeEnabled, () -> c.shakeEnabled = !c.shakeEnabled);
        toggle(rx, y + step * 3, colW, bh, "Hide chat during animation", () -> c.hideChat, () -> c.hideChat = !c.hideChat);
        toggle(rx, y + step * 4, colW, bh, "Debug chat messages", () -> c.debug, () -> c.debug = !c.debug);

        int half = (colW - 4) / 2;
        preview(rx, y + step * 5, half, bh, "Tier 1", 1);
        preview(rx + half + 4, y + step * 5, half, bh, "Tier 2", 2);
        preview(rx, y + step * 6, half, bh, "Tier 3", 3);
        preview(rx + half + 4, y + step * 6, half, bh, "Dye (max)", 4);

        // left column, last row: move the animation by dragging it
        this.addRenderableWidget(Button.builder(Component.literal("Move animation..."), b ->
                        net.minecraft.client.Minecraft.getInstance().gui.setScreen(new PositionScreen(this)))
                .pos(lx, y + step * 6).size(colW, bh).build());
        this.addRenderableWidget(Button.builder(Component.literal("Timings..."), b ->
                        net.minecraft.client.Minecraft.getInstance().gui.setScreen(new TimingScreen(this)))
                .pos(lx, y + step * 7).size(colW, bh).build());
        this.addRenderableWidget(Button.builder(Component.literal("Stats..."), b ->
                        net.minecraft.client.Minecraft.getInstance().gui.setScreen(new StatsScreen(this)))
                .pos(lx, y + step * 8).size(colW, bh).build());
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .pos(rx, y + step * 8).size(colW, bh).build());
    }

    void slider(int x, int y, int w, int h, String label, double min, double max, double cur, DoubleConsumer set) {
        this.addRenderableWidget(new PercentSlider(x, y, w, h, label, min, max, cur, set));
    }

    private void toggle(int x, int y, int w, int h, String label, BooleanSupplier get, Runnable flip) {
        this.addRenderableWidget(Button.builder(label(label, get.getAsBoolean()), b -> {
            flip.run();
            b.setMessage(label(label, get.getAsBoolean()));
        }).pos(x, y).size(w, h).build());
    }

    private void preview(int x, int y, int w, int h, String label, int mode) {
        this.addRenderableWidget(Button.builder(Component.literal(label), b -> {
            this.onClose();
            Cinematic.preview(mode);
        })
                .pos(x, y).size(w, h).build());
    }

    private static Component label(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "ON" : "OFF"));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // intentionally empty: no blur or dimming, so previews stay visible behind the panel
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
        super.onClose();
    }

    /** Slider that shows a percentage and hands the real value to a setter. */
    static final class PercentSlider extends AbstractSliderButton {
        private final String label;
        private final double min, max;
        private final DoubleConsumer setter;

        PercentSlider(int x, int y, int w, int h, String label, double min, double max, double cur, DoubleConsumer setter) {
            super(x, y, w, h, Component.literal(""), (cur - min) / (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.setter = setter;
            this.updateMessage();
        }

        private double real() {
            return min + (max - min) * this.value;
        }

        @Override
        protected void updateMessage() {
            if (label == null) return;
            this.setMessage(Component.literal(label + ": " + Math.round(real() * 100.0) + "%"));
        }

        @Override
        protected void applyValue() {
            setter.accept(real());
        }
    }
}
