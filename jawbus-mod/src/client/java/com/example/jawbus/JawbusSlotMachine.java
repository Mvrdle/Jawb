package com.example.jawbus;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
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
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lord Jawbus slot machine simulator: a purely client-side, non-blocking HUD overlay.
 *
 * - No Screen is ever opened, so the game is never paused and no input/mouse focus is captured.
 * - The mod itself makes no network connections and reads/writes no files.
 *
 * Wheel-of-fortune style: a wide strip of items scrolls past a center pointer, clicking as each
 * item passes, and slows down until it stops on a pre-rolled weighted result.
 *
 * Written against Minecraft 26.2 (official Mojang names, GuiGraphicsExtractor HUD API).
 */
public final class JawbusSlotMachine implements ClientModInitializer {

    private static final String MOD_ID = "jawbusslots";

    // ---- Timing (client ticks, 20/s) ----
    private static final int SPIN_TICKS = 90;               // 4.5 s of spinning
    private static final int HOLD_TICKS = 100;              // 5 s display after the spin
    private static final int TOTAL_TICKS = SPIN_TICKS + HOLD_TICKS;
    private static final float FADE_IN_TICKS = 6f;
    private static final float FADE_OUT_TICKS = 12f;
    private static final int MAX_QUEUE = 25;

    // ---- Wheel ----
    private static final int TARGET = 28;                   // how many items scroll past before landing
    private static final int STRIP_LEN = TARGET + 16;

    // ---- Data model ----
    /** Your own drop-table tiers (used for the label under the result). */
    private enum Tier {
        COMMON("Common"),
        UNCOMMON("Uncommon"),
        RARE("Rare"),
        VERY_RARE("Very Rare"),
        EXTREMELY_RARE("Extremely Rare");

        final String label;

        Tier(String label) {
            this.label = label;
        }
    }

    // SkyBlock rarity colors
    private static final int C_RARE = 0x5555FF;
    private static final int C_EPIC = 0xAA00AA;
    private static final int C_LEGENDARY = 0xFFAA00;
    private static final int C_MYTHIC = 0xFF55FF;

    // Player-head skins (from the SkyBlock item data), used for the head items.
    private static final String TEX_CARMINE_DYE =
            "ewogICJ0aW1lc3RhbXAiIDogMTc0MTE2MDk2ODI0MiwKICAicHJvZmlsZUlkIiA6ICJiMzMwZWE4OWE3MTY0OWZjYTMwZmI5ZjU5MTQ5ZjNjMCIsCiAgInByb2ZpbGVOYW1lIiA6ICJUQUxLX0FMT1RfIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzgxNTBiNDJlNjQ5NzEzNzIxN2Q1ZDhmNTY1MDNmOThkOGY1NDljYjAzNGZiZWFlNmZkZDU0MzE1NzA1MThlMzgiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ==";
    private static final String TEX_JAWBUS_SHARD =
            "ewogICJ0aW1lc3RhbXAiIDogMTczMDMyMTE2NTcyNywKICAicHJvZmlsZUlkIiA6ICIzZDIxZTYyMTk2NzQ0Y2QwYjM3NjNkNTU3MWNlNGJlZSIsCiAgInByb2ZpbGVOYW1lIiA6ICJTcl83MUJsYWNrYmlyZCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9hMGRiYjUwOTNhM2Y5ZDc3MGU4ZGE0ZDk3Njc1YzdjYTI2YjA2MzhmMmRhM2EzYjY1NmY3Y2NhZDVjMTk4YjcxIgogICAgfQogIH0KfQ==";
    private static final String TEX_RADIOACTIVE_VIAL =
            "ewogICJ0aW1lc3RhbXAiIDogMTYxMDA0OTczMzc3OSwKICAicHJvZmlsZUlkIiA6ICIxOTI1MjFiNGVmZGI0MjVjODkzMWYwMmE4NDk2ZTExYiIsCiAgInByb2ZpbGVOYW1lIiA6ICJTZXJpYWxpemFibGUiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWI1NzRjMDNiMWM4ZjUyNTM1Zjk2YTIwYTQxNzYwMjBiYjI4MGUwYWY1MmZjMTI4NWQzZWNiZDM1MTcyODBjNCIKICAgIH0KICB9Cn0=";

    /**
     * model: the SkyBlock pack's item model id (namespace:path). If the loaded resource packs contain it,
     * the item is drawn with the pack's texture; otherwise the vanilla fallback item is shown.
     * headTexture: for the player-head items.
     */
    private enum Drop {
        SILVER_MAGMAFISH("Silver Magmafish", Tier.COMMON, 60.0, C_EPIC, false,
                "hypixel_skyblock:item/fishing/trophy/lava/magmafish_silver", Items.COD, null),
        MAGMA_LORD_FRAGMENTS("Magma Lord Fragments", Tier.UNCOMMON, 25.0, C_LEGENDARY, false,
                "hypixel_skyblock:item/uncategorized/magma_lord_fragment", Items.MAGMA_CREAM, null),
        CARMINE_DYE("Carmine Dye", Tier.RARE, 8.0, C_LEGENDARY, true,
                null, Items.REDSTONE, TEX_CARMINE_DYE),
        LORD_JAWBUS_SHARDS("Lord Jawbus Shards", Tier.RARE, 5.0, C_LEGENDARY, false,
                null, Items.NETHERITE_SCRAP, TEX_JAWBUS_SHARD),
        BOBBIN_SCRIPTURES("Bobbin Scriptures", Tier.VERY_RARE, 1.8, C_RARE, true,
                "hypixel_skyblock:item/island_relevant/crimson_isle/bobbin_time/bobbin_scriptures", Items.ENCHANTED_BOOK, null),
        RADIOACTIVE_VIAL("Radioactive Vial", Tier.EXTREMELY_RARE, 0.2, C_MYTHIC, true,
                null, Items.EXPERIENCE_BOTTLE, TEX_RADIOACTIVE_VIAL);

        final String displayName;
        final Tier tier;
        final double weight;
        final int color;
        final boolean fanfare;
        final String model;
        final Item fallback;
        final String headTexture;
        private ItemStack iconCache;

        Drop(String displayName, Tier tier, double weight, int color, boolean fanfare,
             String model, Item fallback, String headTexture) {
            this.displayName = displayName;
            this.tier = tier;
            this.weight = weight;
            this.color = color;
            this.fanfare = fanfare;
            this.model = model;
            this.fallback = fallback;
            this.headTexture = headTexture;
        }

        /** Built lazily (and rebuilt each spin) so it follows whatever resource packs are loaded right now. */
        ItemStack icon() {
            if (iconCache == null) iconCache = build();
            return iconCache;
        }

        void refresh() {
            iconCache = null;
        }

        String status() {
            if (headTexture != null) return "player head skin";
            if (model != null && modelAvailable(modelId())) return "pack texture found";
            return "pack texture NOT found (showing vanilla icon)";
        }

        private Identifier modelId() {
            int i = model.indexOf(':');
            return Identifier.fromNamespaceAndPath(model.substring(0, i), model.substring(i + 1));
        }

        private ItemStack build() {
            if (headTexture != null) return head(headTexture);
            if (model != null) {
                Identifier id = modelId();
                if (modelAvailable(id)) {
                    ItemStack stack = new ItemStack(Items.PAPER);
                    stack.set(DataComponents.ITEM_MODEL, id);
                    return stack;
                }
            }
            return new ItemStack(fallback);
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

    /**
     * One queued spin. Everything is rolled up front: the scrolling strip, the winning drop (strip[TARGET])
     * and a small random offset so the pointer rests somewhere inside the winning slot.
     */
    private record DropRoll(Drop[] strip, Drop result, float offset, Trigger trigger) {
    }

    // ---- Animation state (client thread only) ----
    private static final Queue<DropRoll> QUEUE = new ArrayDeque<>();
    private static DropRoll current = null;
    private static int age = 0;
    private static int lastClickIndex = 0;
    private static float lastClickTime = -100f;

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

        // 2. Fallback command: /jawbus [count]   and   /jawbus textures
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
                dispatcher.register(ClientCommands.literal("jawbus")
                        .executes(ctx -> {
                            enqueue(Trigger.MANUAL);
                            ctx.getSource().sendFeedback(Component.literal("Jawbus slot machine queued."));
                            return 1;
                        })
                        .then(ClientCommands.literal("textures")
                                .executes(ctx -> {
                                    for (Drop d : DROPS) {
                                        d.refresh();
                                        ctx.getSource().sendFeedback(Component.literal(d.displayName + ": " + d.status()));
                                    }
                                    return 1;
                                }))
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
    // Item icons
    // ------------------------------------------------------------------

    private static boolean modelAvailable(Identifier model) {
        Identifier definition = Identifier.fromNamespaceAndPath(model.getNamespace(), "items/" + model.getPath() + ".json");
        return Minecraft.getInstance().getResourceManager().getResource(definition).isPresent();
    }

    private static ItemStack head(String textureValue) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        PropertyMap properties = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", textureValue)));
        UUID id = UUID.nameUUIDFromBytes(textureValue.getBytes(StandardCharsets.UTF_8));
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(new GameProfile(id, "jawbus", properties)));
        return stack;
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
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        Drop result = rollWeighted();
        Drop[] strip = new Drop[STRIP_LEN];
        for (int i = 0; i < STRIP_LEN; i++) {
            Drop d;
            do {
                d = DROPS[rng.nextInt(DROPS.length)];
            } while (i > 0 && d == strip[i - 1]); // no back-to-back repeats
            strip[i] = d;
        }
        strip[TARGET] = result;
        if (strip[TARGET + 1] == result) strip[TARGET + 1] = DROPS[(result.ordinal() + 1) % DROPS.length];
        if (strip[TARGET - 1] == result) strip[TARGET - 1] = DROPS[(result.ordinal() + 2) % DROPS.length];

        float offset = (rng.nextFloat() - 0.5f) * 0.64f; // lands somewhere inside the winning slot
        QUEUE.add(new DropRoll(strip, result, offset, trigger));
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
    // Tick-based timeline + landing audio
    // ------------------------------------------------------------------

    private static void tick(Minecraft mc) {
        if (mc.player == null) return;

        if (current == null) {
            DropRoll next = QUEUE.poll();
            if (next == null) return;
            start(next);
        }

        age++;
        if (age == SPIN_TICKS) land(mc, current);
        if (age >= TOTAL_TICKS) current = null; // dismissed; next queued roll starts on the next tick
    }

    private static void start(DropRoll roll) {
        current = roll;
        age = 0;
        lastClickIndex = 0;
        lastClickTime = -100f;
        for (Drop d : DROPS) d.refresh(); // pick up resource packs loaded since last time
    }

    private static void land(Minecraft mc, DropRoll roll) {
        play(mc, SoundEvents.UI_BUTTON_CLICK, 0.5f, 1.0f); // heavy "thunk"
        if (roll.result().fanfare) {
            play(mc, SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f);
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

    private static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        DropRoll roll = current;
        if (roll == null) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        float t = age + deltaTracker.getGameTimeDeltaPartialTick(false);
        float alpha = Math.max(0f, Math.min(1f, Math.min(t / FADE_IN_TICKS, (TOTAL_TICKS - t) / FADE_OUT_TICKS)));
        if (alpha <= 0.01f) return;

        int w = g.guiWidth();
        int h = g.guiHeight();
        int cx = w / 2;
        int slot = Math.max(48, Math.min(w / 6, (int) (h * 0.34f)));
        int pad = slot / 8;
        int bandH = slot + pad * 2;
        int cy = h / 2 + (int) (h * 0.04f);
        int bandTop = cy - bandH / 2;
        int bandBottom = bandTop + bandH;

        // ---- wheel position: fast start, long slow-down (ease-out cubic) ----
        float u = Math.min(1f, t / SPIN_TICKS);
        float ease = 1f - (1f - u) * (1f - u) * (1f - u);
        float pos = (TARGET + roll.offset()) * ease;
        boolean landed = t >= SPIN_TICKS;
        float landedFor = Math.max(0f, t - SPIN_TICKS);

        // ---- click sound: one per item that passes the pointer ----
        int nearest = (int) Math.floor(pos + 0.5f);
        if (nearest != lastClickIndex) {
            lastClickIndex = nearest;
            lastClickTime = t;
            float speed = (1f - u) * (1f - u); // 1 = fastest, 0 = stopped
            float pitch = 1.0f + 0.9f * speed;
            float volume = 0.40f + 0.35f * (1f - speed);
            play(mc, SoundEvents.NOTE_BLOCK_HAT, pitch, volume);
            if (speed < 0.3f) play(mc, SoundEvents.UI_BUTTON_CLICK, 1.4f + 0.4f * speed, 0.35f);
        }

        int accent = landed ? roll.result().color : C_LEGENDARY;

        // ---- dim the whole screen a little ----
        g.fill(0, 0, w, h, argb(0x000000, alpha * 0.50f));

        // ---- band ----
        g.fill(0, bandTop, w, bandBottom, argb(0x000000, alpha * 0.60f));
        g.fill(cx - slot, bandTop, cx + slot, bandBottom, argb(accent, alpha * 0.14f));
        int line = Math.max(2, slot / 32);
        g.fill(0, bandTop - line, w, bandTop, argb(accent, alpha));
        g.fill(0, bandBottom, w, bandBottom + line, argb(accent, alpha));

        // ---- items ----
        int half = slot / 2 - Math.max(3, slot / 24);
        int visible = w / (2 * slot) + 3;
        int first = (int) Math.floor(pos) - visible;
        int last = (int) Math.floor(pos) + visible;
        int len = roll.strip().length;
        float itemScaleBase = (slot * 0.62f) / 16f;
        float fadeScale = 0.55f + 0.45f * alpha;

        for (int i = first; i <= last; i++) {
            float fx = cx + (i - pos) * slot;
            if (fx + slot / 2f < 0 || fx - slot / 2f > w) continue;
            int x = Math.round(fx);
            Drop d = roll.strip()[Math.floorMod(i, len)];
            boolean isWinner = landed && i == TARGET;

            // slot backdrop in the item's SkyBlock rarity color
            g.fill(x - half, cy - half, x + half, cy + half, argb(d.color, alpha * 0.38f));
            frame(g, x - half, cy - half, x + half, cy + half, Math.max(2, slot / 40), argb(d.color, alpha * 0.9f));

            // item icon (scaled up; the winner pops)
            float scale = itemScaleBase * fadeScale;
            if (isWinner) {
                float pop = (float) Math.sin(Math.min(1f, landedFor / 10f) * Math.PI) * 0.18f;
                float idle = (float) Math.sin(landedFor * 0.25f) * 0.025f;
                scale *= 1f + 0.10f + pop + idle;
            }
            drawIcon(g, d.icon(), x, cy, scale);

            // after landing, dim everything that didn't win
            if (landed && !isWinner) {
                g.fill(x - half, cy - half, x + half, cy + half, argb(0x000000, alpha * 0.58f));
            }
        }

        // ---- winner glow + sparkles ----
        if (landed) {
            Drop win = roll.result();
            int wx = Math.round(cx - (roll.offset()) * slot);
            float pulse = 0.5f + 0.5f * (float) Math.sin(landedFor * 0.3f);
            for (int k = 0; k < 3; k++) {
                int grow = 2 + k * 3;
                frame(g, wx - half - grow, cy - half - grow, wx + half + grow, cy + half + grow, 2,
                        argb(win.color, alpha * (0.55f - k * 0.15f) * (0.6f + 0.4f * pulse)));
            }
            if (win.fanfare) sparkles(g, wx, cy, slot * 0.78f, win.color, landedFor, alpha);
        }

        // ---- edge fade (darken toward the screen edges) ----
        int steps = 10;
        int stepW = Math.max(2, w / 40);
        for (int k = 0; k < steps; k++) {
            float a = alpha * 0.55f * (1f - k / (float) steps);
            g.fill(k * stepW, bandTop, (k + 1) * stepW, bandBottom, argb(0x000000, a));
            g.fill(w - (k + 1) * stepW, bandTop, w - k * stepW, bandBottom, argb(0x000000, a));
        }

        // ---- center line + pointer (kicks on each click) ----
        float kick = Math.max(0f, 1f - (t - lastClickTime) / 3f);
        g.fill(cx - 1, bandTop, cx + 1, bandBottom, argb(0xFFFFFF, alpha * 0.70f));
        int pw = Math.max(3, slot / 22);
        int ph = slot / 4 + Math.round(kick * Math.max(3, slot / 14));
        g.fill(cx - pw, bandBottom + line, cx + pw, bandBottom + line + ph, argb(0xFF3355, alpha));
        g.fill(cx - pw, bandTop - line - ph, cx + pw, bandTop - line, argb(0xFF3355, alpha));

        // ---- title ----
        float titleScale = Math.max(2f, Math.min(5f, slot / 36f));
        int titleH = Math.round(9 * titleScale);
        int titleY = bandTop - line - ph - titleH - 10;
        if (landed) {
            scaledCentered(g, font, roll.result().displayName, cx, titleY, titleScale, argb(roll.result().color, alpha));
        } else {
            scaledCentered(g, font, "LORD JAWBUS", cx, titleY, titleScale, argb(C_LEGENDARY, alpha));
        }
        scaledCentered(g, font, roll.trigger().label, cx, titleY - 16, 1.3f, argb(0xBBBBBB, alpha));

        // ---- tier label under the band after landing ----
        if (landed) {
            float tierScale = Math.max(1.5f, titleScale * 0.5f);
            scaledCentered(g, font, roll.result().tier.label.toUpperCase(Locale.ROOT), cx,
                    bandBottom + line + ph + 10, tierScale, argb(roll.result().color, alpha * 0.9f));
        }

        // ---- queued count ----
        if (!QUEUE.isEmpty()) {
            String q = "+" + QUEUE.size() + " queued";
            g.text(font, q, w - 10 - font.width(q), 10, argb(0x55FFFF, alpha), true);
        }
    }

    private static void sparkles(GuiGraphicsExtractor g, int cx, int cy, float radius, int color, float t, float alpha) {
        int n = 14;
        for (int i = 0; i < n; i++) {
            float dir = (i % 2 == 0) ? 1f : -1f;
            float ang = (float) (i * (Math.PI * 2 / n)) + t * 0.04f * dir;
            float r = radius * (0.75f + 0.30f * (float) Math.sin(t * 0.15f + i * 1.7f));
            int x = Math.round(cx + (float) Math.cos(ang) * r);
            int y = Math.round(cy + (float) Math.sin(ang) * r * 0.8f);
            int size = 2 + (i % 3);
            float phase = 0.5f + 0.5f * (float) Math.sin(t * 0.3f + i);
            int c = argb(color, alpha * phase);
            g.fill(x - size, y, x + size + 1, y + 1, c);
            g.fill(x, y - size, x + 1, y + size + 1, c);
        }
    }

    private static void scaledCentered(GuiGraphicsExtractor g, Font font, String text, float cx, float y, float scale, int color) {
        var m = g.pose();
        m.pushMatrix();
        m.translate(cx, y);
        m.scale(scale, scale);
        g.text(font, text, -font.width(text) / 2, 0, color, true);
        m.popMatrix();
    }

    /** Draws an item centered at (cx, cy), scaled up about its middle (2D Matrix3x2fStack). */
    private static void drawIcon(GuiGraphicsExtractor g, ItemStack stack, int cx, int cy, float scale) {
        var m = g.pose();
        m.pushMatrix();
        m.translate(cx, cy);
        m.scale(scale, scale);
        m.translate(-8f, -8f);
        g.item(stack, 0, 0);
        m.popMatrix();
    }

    private static void frame(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int thick, int color) {
        g.fill(x0, y0, x1, y0 + thick, color);
        g.fill(x0, y1 - thick, x1, y1, color);
        g.fill(x0, y0 + thick, x0 + thick, y1 - thick, color);
        g.fill(x1 - thick, y0 + thick, x1, y1 - thick, color);
    }

    private static int argb(int rgb, float alpha) {
        int a = Math.max(4, Math.min(255, (int) (alpha * 255f))); // text renderer treats very low alpha as opaque
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
