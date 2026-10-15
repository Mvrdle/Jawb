package com.example.jawbus;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lord Jawbus slot machine simulator: a purely client-side, non-blocking HUD overlay.
 *
 * - No Screen is ever opened, so the game is never paused and no input/mouse focus is captured.
 * - No network, file or server access of any kind.
 *
 * Reel layout: three reels, the CENTER reel (payline) is the official weighted roll.
 * The left/right reels are weighted decorative rolls.
 *
 * Written against Minecraft 26.2 (official Mojang names, GuiGraphicsExtractor HUD API).
 */
public final class JawbusSlotMachine implements ClientModInitializer {

    private static final String MOD_ID = "jawbusslots";

    // ---- Timing (client ticks, 20/s) ----
    private static final int[] STOP_TICKS = {36, 60, 48}; // left, center, right lock-in times
    private static final int SPIN_TICKS = 60;              // spin ends when the center reel locks
    private static final int HOLD_TICKS = 100;             // 5 s display after the spin
    private static final int TOTAL_TICKS = SPIN_TICKS + HOLD_TICKS;
    private static final float FADE_IN_TICKS = 4f;
    private static final float FADE_OUT_TICKS = 10f;
    private static final int MAX_QUEUE = 25;

    // ---- Layout (GUI-scaled pixels) ----
    private static final int REEL_W = 28;
    private static final int ROW_H = 22;
    private static final int GAP = 6;
    private static final int PAD = 8;
    private static final int HEADER_H = 22;
    private static final int FOOTER_H = 28;

    // ---- Data model ----
    private enum Rarity {
        COMMON("Common", 0xFFFFFF),
        UNCOMMON("Uncommon", 0x55FF55),
        RARE("Rare", 0x5555FF),
        VERY_RARE("Very Rare", 0xAA00AA),
        EXTREMELY_RARE("Extremely Rare", 0xFFAA00);

        final String label;
        final int rgb;

        Rarity(String label, int rgb) {
            this.label = label;
            this.rgb = rgb;
        }
    }

    private enum Drop {
        SILVER_MAGMAFISH("Silver Magmafish", Rarity.COMMON, 60.0, Items.COD, false),
        MAGMA_LORD_FRAGMENTS("Magma Lord Fragments", Rarity.UNCOMMON, 25.0, Items.MAGMA_CREAM, false),
        CARMINE_DYE("Carmine Dye", Rarity.RARE, 8.0, Items.REDSTONE, true),
        LORD_JAWBUS_SHARDS("Lord Jawbus Shards", Rarity.RARE, 5.0, Items.NETHERITE_SCRAP, false),
        BOBBIN_SCRIPTURES("Bobbin Scriptures", Rarity.VERY_RARE, 1.8, Items.ENCHANTED_BOOK, true),
        RADIOACTIVE_VIAL("Radioactive Vial", Rarity.EXTREMELY_RARE, 0.2, Items.EXPERIENCE_BOTTLE, true);

        final String displayName;
        final Rarity rarity;
        final double weight;
        final Item item;
        final boolean fanfare;
        private ItemStack iconCache;

        Drop(String displayName, Rarity rarity, double weight, Item item, boolean fanfare) {
            this.displayName = displayName;
            this.rarity = rarity;
            this.weight = weight;
            this.item = item;
            this.fanfare = fanfare;
        }

        /** Created lazily so no ItemStack is built before the game has finished loading. */
        ItemStack icon() {
            if (iconCache == null) iconCache = new ItemStack(item);
            return iconCache;
        }
    }

    private static final Drop[] DROPS = Drop.values();
    private static final double TOTAL_WEIGHT = Arrays.stream(DROPS).mapToDouble(d -> d.weight).sum();

    private enum Trigger {
        SPAWN("Lord Jawbus spawned!"),
        KILL("Lord Jawbus slain!"),
        RARE_DROP("Rare drop!"),
        PET_DROP("Pet drop!"),
        LOOT_SHARE("Loot share!"),
        MANUAL("Manual spin");

        final String label;

        Trigger(String label) {
            this.label = label;
        }
    }

    /** One queued slot spin. Outcome is rolled up front so the animation just plays it back. */
    private record DropRoll(Drop left, Drop result, Drop right, Trigger trigger) {
    }

    // ---- Animation state (client thread only) ----
    private static final Queue<DropRoll> QUEUE = new ArrayDeque<>();
    private static DropRoll current = null;
    private static int age = 0;
    private static final int[] reelIndex = new int[3];
    private static final int[] nextAdvance = new int[3];
    private static final boolean[] landed = new boolean[3];

    // ---- Chat matching ----
    private static final String[] SPAWN_WORDS = {"spawn", "emerge", "appear", "rise", "risen", "awaken"};
    private static final String[] KILL_WORDS = {"slain", "slay", "killed", "defeated", "died", "vanquish"};

    @Override
    public void onInitializeClient() {
        // 1. Chat listener (overlay == true means action bar text; ignore it)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            Trigger trigger = classify(message.getString());
            if (trigger != null) enqueue(trigger);
        });

        // 2. Fallback command: /jawbus [count]
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("jawbus")
                        .executes(ctx -> {
                            enqueue(Trigger.MANUAL);
                            ctx.getSource().sendFeedback(Component.literal("Jawbus slot machine queued."));
                            return 1;
                        })
                        .then(ClientCommands.argument("count", IntegerArgumentType.integer(1, MAX_QUEUE))
                                .executes(ctx -> {
                                    int n = IntegerArgumentType.getInteger(ctx, "count");
                                    for (int i = 0; i < n; i++) enqueue(Trigger.MANUAL);
                                    ctx.getSource().sendFeedback(Component.literal("Queued " + n + " Jawbus spin(s)."));
                                    return n;
                                }))));

        // Animation clock + cleanup
        ClientTickEvents.END_CLIENT_TICK.register(JawbusSlotMachine::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> reset());

        // 3. HUD element: drawn straight onto the HUD, no Screen involved
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "slot_overlay"),
                JawbusSlotMachine::render);
    }

    // ------------------------------------------------------------------
    // Chat classification
    // ------------------------------------------------------------------

    /**
     * Returns the trigger for a chat line, or null. Lines that look like player chat
     * (a ": " before the keyword) are ignored so players can't spoof drops.
     * Wording lists above are best-effort; tweak them to match live Hypixel messages.
     */
    private static Trigger classify(String raw) {
        String text = raw.strip();
        if (hasTag(text, "RARE DROP!")) return Trigger.RARE_DROP;
        if (hasTag(text, "PET DROP!")) return Trigger.PET_DROP;
        if (hasTag(text, "LOOT SHARE")) return Trigger.LOOT_SHARE;

        String lower = text.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("lord jawbus");
        if (idx < 0 || lower.substring(0, idx).contains(": ")) return null;
        if (containsAny(lower, KILL_WORDS)) return Trigger.KILL;
        if (containsAny(lower, SPAWN_WORDS)) return Trigger.SPAWN;
        return null;
    }

    private static boolean hasTag(String text, String tag) {
        int idx = text.indexOf(tag);
        return idx >= 0 && !text.substring(0, idx).contains(": ");
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

    // ------------------------------------------------------------------
    // Queue + weighted roll
    // ------------------------------------------------------------------

    private static void enqueue(Trigger trigger) {
        if (QUEUE.size() >= MAX_QUEUE) return;
        QUEUE.add(new DropRoll(rollWeighted(), rollWeighted(), rollWeighted(), trigger));
    }

    private static Drop rollWeighted() {
        double r = ThreadLocalRandom.current().nextDouble() * TOTAL_WEIGHT;
        for (Drop d : DROPS) {
            r -= d.weight;
            if (r < 0) return d;
        }
        return DROPS[0];
    }

    private static void reset() {
        QUEUE.clear();
        current = null;
        age = 0;
    }

    // ------------------------------------------------------------------
    // Tick-based animation + audio
    // ------------------------------------------------------------------

    private static void tick(Minecraft mc) {
        if (mc.player == null) return;

        if (current == null) {
            DropRoll next = QUEUE.poll();
            if (next == null) return;
            start(next);
        }

        age++;
        if (age <= SPIN_TICKS) tickSpin(mc);
        if (age >= TOTAL_TICKS) current = null; // dismissed; next queued roll starts on the next tick
    }

    private static void start(DropRoll roll) {
        current = roll;
        age = 0;
        for (int i = 0; i < 3; i++) {
            landed[i] = false;
            reelIndex[i] = ThreadLocalRandom.current().nextInt(DROPS.length);
            nextAdvance[i] = 0;
        }
    }

    private static void tickSpin(Minecraft mc) {
        DropRoll roll = current;
        Drop[] finals = {roll.left(), roll.result(), roll.right()};
        boolean advanced = false;

        for (int i = 0; i < 3; i++) {
            if (landed[i]) continue;

            if (age >= STOP_TICKS[i]) {
                landed[i] = true;
                reelIndex[i] = finals[i].ordinal();
                play(mc, SoundEvents.UI_BUTTON_CLICK, i == 1 ? 1.9f : 1.5f, 0.8f);
                if (i == 1 && finals[1].fanfare) {
                    play(mc, SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f);
                }
                continue;
            }

            if (age >= nextAdvance[i]) {
                reelIndex[i]++;
                // decelerate: advance interval grows from 1 tick toward 5 ticks near the stop
                nextAdvance[i] = age + 1 + (age * 4) / STOP_TICKS[i];
                advanced = true;
            }
        }

        if (advanced && age % 2 == 0) {
            play(mc, SoundEvents.NOTE_BLOCK_HAT, 0.8f + (age / (float) SPIN_TICKS) * 0.9f, 0.6f);
        }
    }

    // Two overloads so this compiles whether a SoundEvents field is a SoundEvent or a Holder<SoundEvent>.
    private static void play(Minecraft mc, SoundEvent sound, float pitch, float volume) {
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    private static void play(Minecraft mc, Holder<SoundEvent> sound, float pitch, float volume) {
        play(mc, sound.value(), pitch, volume);
    }

    // ------------------------------------------------------------------
    // HUD rendering
    // ------------------------------------------------------------------

    private static Drop symbolAt(int index) {
        return DROPS[Math.floorMod(index, DROPS.length)];
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        DropRoll roll = current;
        if (roll == null) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        float t = age + deltaTracker.getGameTimeDeltaPartialTick(false);
        float alpha = Math.min(1f, Math.min(t / FADE_IN_TICKS, (TOTAL_TICKS - t) / FADE_OUT_TICKS));
        alpha = Math.max(0f, alpha);
        if (alpha <= 0.01f) return;

        int sw = g.guiWidth();
        int sh = g.guiHeight();
        int panelW = PAD * 2 + REEL_W * 3 + GAP * 2;
        int panelH = PAD * 2 + HEADER_H + ROW_H * 3 + FOOTER_H;
        int x0 = (sw - panelW) / 2;
        int y0 = (sh - panelH) / 2 - 40; // centered, nudged up so the crosshair stays clear
        int cx = sw / 2;

        boolean centerLanded = landed[1];
        int accent = centerLanded ? roll.result().rarity.rgb : 0xFFAA00;

        // Panel + border
        g.fill(x0, y0, x0 + panelW, y0 + panelH, argb(0x101018, alpha * 0.88f));
        border(g, x0, y0, panelW, panelH, argb(accent, alpha));

        // Header
        centered(g, font, "LORD JAWBUS", cx, y0 + 5, argb(0xFFAA00, alpha));
        centered(g, font, roll.trigger().label, cx, y0 + 14, argb(0xAAAAAA, alpha));
        if (!QUEUE.isEmpty()) {
            String q = "+" + QUEUE.size();
            g.text(font, q, x0 + panelW - PAD - font.width(q), y0 + 5, argb(0x55FFFF, alpha), true);
        }

        // Reels
        int reelsTop = y0 + PAD + HEADER_H;
        float iconScale = alpha; // icons can't take an alpha tint, so shrink them while fading
        for (int r = 0; r < 3; r++) {
            int rx = x0 + PAD + r * (REEL_W + GAP);
            g.fill(rx, reelsTop, rx + REEL_W, reelsTop + ROW_H * 3, argb(0x000000, alpha * 0.55f));
            for (int k = -1; k <= 1; k++) {
                Drop d = symbolAt(reelIndex[r] + k);
                int ix = rx + (REEL_W - 16) / 2;
                int iy = reelsTop + (k + 1) * ROW_H + (ROW_H - 16) / 2;
                drawIcon(g, d.icon(), ix, iy, iconScale);
            }
            border(g, rx, reelsTop, REEL_W, ROW_H * 3, argb(0x555555, alpha));
        }

        // Payline highlight across the middle row
        int payY = reelsTop + ROW_H;
        g.fill(x0 + PAD - 3, payY, x0 + panelW - PAD + 3, payY + ROW_H, argb(0xFFFFFF, alpha * 0.12f));
        g.fill(x0 + PAD - 3, payY, x0 + panelW - PAD + 3, payY + 1, argb(accent, alpha));
        g.fill(x0 + PAD - 3, payY + ROW_H - 1, x0 + panelW - PAD + 3, payY + ROW_H, argb(accent, alpha));

        // Footer: result
        int fy = reelsTop + ROW_H * 3 + 6;
        if (centerLanded) {
            Drop res = roll.result();
            centered(g, font, res.displayName, cx, fy, argb(res.rarity.rgb, alpha));
            centered(g, font, res.rarity.label.toUpperCase(Locale.ROOT), cx, fy + 10, argb(res.rarity.rgb, alpha * 0.8f));
        } else {
            centered(g, font, "Spinning...", cx, fy + 5, argb(0xAAAAAA, alpha));
        }
    }

    private static void centered(GuiGraphicsExtractor g, Font font, String text, int cx, int y, int color) {
        g.text(font, text, cx - font.width(text) / 2, y, color, true);
    }

    /** Draws an item icon, optionally scaled about its center (2D Matrix3x2fStack). */
    private static void drawIcon(GuiGraphicsExtractor g, ItemStack stack, int x, int y, float scale) {
        if (scale >= 0.99f) {
            g.item(stack, x, y);
            return;
        }
        float cx = x + 8f;
        float cy = y + 8f;
        var m = g.pose();
        m.pushMatrix();
        m.translate(cx, cy);
        m.scale(scale, scale);
        m.translate(-cx, -cy);
        g.item(stack, x, y);
        m.popMatrix();
    }

    private static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    private static int argb(int rgb, float alpha) {
        int a = Math.max(4, Math.min(255, (int) (alpha * 255f))); // text renderer treats very low alpha as opaque
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
