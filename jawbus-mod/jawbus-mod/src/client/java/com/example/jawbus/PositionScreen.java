package com.example.jawbus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * "Move animation" screen: click and drag anywhere to move the animation, then press Done (or Esc).
 * Only this screen reacts to the mouse; the animation itself never captures input while it plays.
 */
public final class PositionScreen extends Screen {
    private final Screen parent;

    public PositionScreen(Screen parent) {
        super(Component.literal("Move animation"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int bw = 110, bh = 20, cx = this.width / 2;
        this.addRenderableWidget(Button.builder(Component.literal("Reset position"), b -> {
            JawbusConfig c = JawbusConfig.get();
            c.offsetX = 0f;
            c.offsetY = 0f;
        }).pos(cx - bw - 4, this.height - 30).size(bw, bh).build());
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .pos(cx + 4, this.height - 30).size(bw, bh).build());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (super.mouseDragged(event, dx, dy)) return true;
        JawbusConfig c = JawbusConfig.get();
        c.offsetX = Math.max(-0.45f, Math.min(0.45f, c.offsetX + (float) (dx / this.width)));
        c.offsetY = Math.max(-0.35f, Math.min(0.35f, c.offsetY + (float) (dy / this.height)));
        return true;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        graphics.fill(0, 0, this.width, this.height, 0x55000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        Cinematic.renderEditor(graphics);
        String msg = "Click and drag anywhere to move the animation";
        graphics.text(this.font, msg, (this.width - this.font.width(msg)) / 2, 10, 0xFFFFAA00, true);
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
