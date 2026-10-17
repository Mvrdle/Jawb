package com.example.jawbus;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The three-tier Lord Jawbus golem animation, drawn straight onto the HUD (never a Screen, so nothing is paused
 * and no input is captured).
 *
 *   Tier 1  The flop        - one chest crack, stagger, golem flops forward, small magma burst
 *   Tier 2  The rupture     - cracks spread over the upper body, rumble, buckle, medium explosion
 *   Tier 3  The detonation  - cracks cover everything, white-hot, short pause, huge explosion
 *
 * Everything is a pure function of elapsed time, so particles and debris need no per-frame simulation.
 * The reward shown is always the one passed in; nothing here ever chooses or invents a reward.
 */
public final class Cinematic {
    private Cinematic() {
    }

    // cs/ce = crack growth start/end, burst = explosion/impact, itemDone = reward settled, total = end (seconds @ duration 1.0)
    private record Tl(float cs, float ce, float burst, float itemDone, float total) {
    }

    private record Cue(float t, Runnable action) {
    }

    private record Particle(int kind, float x0, float y0, float vx, float vy, float grav, float life, int rgb,
                            float size, float delay) {
    }

    private record Req(Reward reward, List<Reward> others, boolean lootShare, boolean preview) {
    }

    private static final class Playback {
        final Reward reward;
        final List<Reward> others;
        final boolean lootShare;
        final boolean preview;
        final int tier;
        final Tl tl;
        final float dur;
        final long startNs = System.nanoTime();
        final long seed = System.nanoTime();
        final List<Cue> cues = new ArrayList<>();
        int nextCue;
        List<Particle> parts;
        ItemStack icon;

        Playback(Req r, Tl tl, float dur) {
            this.reward = r.reward();
            this.others = r.others();
            this.lootShare = r.lootShare();
            this.preview = r.preview();
            this.tier = r.reward().tier;
            this.tl = tl;
            this.dur = dur;
        }

        /** Elapsed time on the animation timeline (already divided by the duration setting). */
        float t() {
            return (System.nanoTime() - startNs) / 1e9f / dur;
        }
    }

    private static final ArrayDeque<Req> QUEUE = new ArrayDeque<>();
    private static Playback current;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Queue the animation for a REAL reward (tier comes from the reward itself). */
    public static void enqueue(Reward reward, List<Reward> others, boolean lootShare, boolean preview) {
        if (QUEUE.size() < 4) QUEUE.add(new Req(reward, others, lootShare, preview));
    }

    /** Menu preview: 1 = tier 1, 2 = tier 2, 3 = tier 3, 4 = tier 3 maximum (Radioactive Vial). Labelled PREVIEW. */
    public static void preview(int mode) {
        Reward r = switch (mode) {
            case 1 -> Reward.SILVER_MAGMAFISH;
            case 2 -> Reward.MAGMA_LORD_FRAGMENT;
            case 3 -> Reward.CARMINE_DYE;
            default -> Reward.RADIOACTIVE_VIAL;
        };
        QUEUE.clear();
        current = null;
        enqueue(r, List.of(), false, true);
    }

    public static void reset() {
        QUEUE.clear();
        current = null;
    }

    // ------------------------------------------------------------------
    // Tick: start playbacks and fire sound cues
    // ------------------------------------------------------------------

    public static void tick(Minecraft mc) {
        if (mc.player == null) {
            reset();
            return;
        }
        if (current == null) {
            Req r = QUEUE.poll();
            if (r == null) return;
            current = start(r);
        }
        Playback p = current;
        float t = p.t();
        while (p.nextCue < p.cues.size() && p.cues.get(p.nextCue).t() <= t) p.cues.get(p.nextCue++).action().run();
        if (t >= p.tl.total()) current = null;
    }

    private static Tl timeline(int tier, boolean vial) {
        return switch (tier) {
            case 1 -> new Tl(0.35f, 1.15f, 2.25f, 3.1f, 6.2f);
            case 2 -> new Tl(0.4f, 2.3f, 2.9f, 3.9f, 7.8f);
            default -> vial ? new Tl(0.4f, 3.4f, 4.1f, 5.6f, 11.7f) : new Tl(0.4f, 3.4f, 4.1f, 5.4f, 10.7f);
        };
    }

    private static Playback start(Req r) {
        Icons.refresh();
        boolean vial = r.reward().maxIntensity();
        Playback p = new Playback(r, timeline(r.reward().tier, vial), JawbusConfig.get().duration);
        p.icon = Icons.get(r.reward());
        buildCues(p.cues, p.tier, vial, p.tl);
        p.cues.sort(Comparator.comparingDouble(Cue::t));
        return p;
    }

    // ------------------------------------------------------------------
    // Audio
    // ------------------------------------------------------------------

    private static void play(Object snd, float pitch, float vol) {
        SoundEvent ev = snd instanceof SoundEvent se ? se : (SoundEvent) ((Holder<?>) snd).value();
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ev, pitch, Math.max(0f, vol)));
    }

    /** Regular-volume cue (rumbles, cracks, impacts for tiers 1 and 2). */
    private static Cue reg(float t, Object snd, float pitch, float vol) {
        return new Cue(t, () -> play(snd, pitch, vol * JawbusConfig.get().regularVolume));
    }

    /** Jackpot-volume cue (tier 3 explosion, bass, jackpot). */
    private static Cue jack(float t, Object snd, float pitch, float vol) {
        return new Cue(t, () -> play(snd, pitch, vol * JawbusConfig.get().jackpotVolume));
    }

    private static void buildCues(List<Cue> c, int tier, boolean vial, Tl tl) {
        Object bass = SoundEvents.NOTE_BLOCK_BASEDRUM;
        Object land = SoundEvents.ANVIL_LAND;
        Object boom = SoundEvents.GENERIC_EXPLODE;
        Object chime = SoundEvents.NOTE_BLOCK_CHIME;
        Object blast = SoundEvents.FIREWORK_ROCKET_LARGE_BLAST;
        Object level = SoundEvents.PLAYER_LEVELUP;
        Object bell = SoundEvents.NOTE_BLOCK_BELL;
        Object orb = SoundEvents.EXPERIENCE_ORB_PICKUP;
        Object twinkle = SoundEvents.FIREWORK_ROCKET_TWINKLE;

        if (tier == 1) {                                   // subdued: short, quiet
            c.add(reg(tl.cs(), bass, 1.4f, 0.35f));
            c.add(reg(tl.cs() + 0.3f, bass, 1.2f, 0.30f));
            c.add(reg(1.2f, bass, 0.8f, 0.35f));
            c.add(reg(1.5f, bass, 0.7f, 0.35f));
            c.add(reg(1.9f, bass, 0.6f, 0.30f));
            c.add(reg(tl.burst(), land, 0.6f, 0.5f));
            c.add(reg(tl.burst(), boom, 1.6f, 0.15f));
            c.add(reg(tl.burst() + 0.25f, chime, 1.2f, 0.35f));
        } else if (tier == 2) {                            // rising rumble, then a stronger impact
            for (float x = tl.cs(); x < tl.ce(); x += 0.22f) {
                float u = (x - tl.cs()) / (tl.ce() - tl.cs());
                c.add(reg(x, bass, 0.5f + 0.6f * u, 0.25f + 0.6f * u));
            }
            c.add(reg(tl.ce(), land, 0.5f, 0.4f));
            c.add(reg(tl.burst(), boom, 0.8f, 0.8f));
            c.add(reg(tl.burst(), land, 0.5f, 0.7f));
            c.add(reg(tl.burst() + 0.25f, chime, 1.0f, 0.5f));
            c.add(reg(tl.burst() + 0.30f, blast, 1.2f, 0.3f));
        } else {                                           // tier 3: build, heartbeat, SILENT pause, then jackpot
            for (float x = tl.cs(); x < tl.ce(); x += 0.2f) {
                float u = (x - tl.cs()) / (tl.ce() - tl.cs());
                c.add(reg(x, bass, 0.4f + 0.8f * u, 0.3f + 0.7f * u));
            }
            for (float d : new float[]{0.9f, 0.6f, 0.35f, 0.15f}) {
                c.add(reg(tl.ce() - d, bass, 0.5f, 0.8f));
                c.add(reg(tl.ce() - d + 0.09f, bass, 0.45f, 0.6f));
            }
            c.add(jack(tl.burst(), boom, 0.5f, 1.0f));
            c.add(jack(tl.burst(), land, 0.5f, 1.0f));
            c.add(jack(tl.burst(), bass, 0.35f, 1.0f));
            c.add(jack(tl.burst() + 0.15f, level, 1.0f, 1.0f));
            c.add(jack(tl.burst() + 0.20f, blast, 1.0f, 0.8f));
            float[] arp = {0.84f, 1.0f, 1.26f, 1.5f, 1.68f, 2.0f};
            for (int i = 0; i < arp.length; i++) c.add(jack(tl.burst() + 0.3f + i * 0.09f, bell, arp[i], 0.7f));
            for (int i = 0; i < 8; i++) c.add(jack(tl.burst() + 0.5f + i * 0.06f, orb, 0.8f + 0.1f * i, 0.5f));
            if (vial) {                                    // maximum intensity: extra boom, second fanfare, more chimes
                c.add(jack(tl.burst() + 0.25f, boom, 0.4f, 1.0f));
                c.add(jack(tl.burst() + 0.9f, level, 1.26f, 1.0f));
                for (int i = 0; i < 8; i++) c.add(jack(tl.burst() + 0.9f + i * 0.07f, bell, 1.0f + 0.14f * i, 0.7f));
                for (int i = 0; i < 6; i++) c.add(jack(tl.burst() + 1.1f + i * 0.08f, twinkle, 1.0f + 0.1f * i, 0.6f));
            }
        }
    }

    // ------------------------------------------------------------------
    // Screen shake (read by CameraMixin; visual only)
    // ------------------------------------------------------------------

    private static float amp(Playback p) {
        float t = p.t();
        Tl tl = p.tl;
        float tau = t - tl.burst();
        float a = 0f;
        if (p.tier == 1) {
            if (tau >= 0f && tau < 0.6f) a = 0.30f * (1f - tau / 0.6f) * (1f - tau / 0.6f);
        } else if (p.tier == 2) {
            if (t >= tl.cs() && t < tl.burst()) {
                float u = (t - tl.cs()) / (tl.burst() - tl.cs());
                a = 0.10f + 0.35f * u * u;
            } else if (tau >= 0f && tau < 1.6f) {
                a = 1.3f * (float) Math.exp(-tau * 2.6f);
            }
        } else {
            if (t >= tl.cs() && t < tl.ce()) {
                float u = (t - tl.cs()) / (tl.ce() - tl.cs());
                a = 0.15f + 0.7f * u * u;
            } else if (t >= tl.ce() && t < tl.burst()) {
                a = 0.12f;
            } else if (tau >= 0f && tau < 2.2f) {
                a = 3.2f * (float) Math.exp(-tau * 1.9f);
            }
            if (p.reward.maxIntensity()) a *= 1.45f;
        }
        return a * JawbusConfig.get().shakeIntensity;
    }

    public static boolean shakeActive() {
        Playback p = current;
        JawbusConfig c = JawbusConfig.get();
        return p != null && c.shakeEnabled && c.shakeIntensity > 0.001f && amp(p) > 0.001f;
    }

    public static float shakeYaw() {
        Playback p = current;
        if (p == null) return 0f;
        float w = (System.nanoTime() - p.startNs) / 1e9f;
        float v = amp(p) * ((float) Math.sin(w * 37.1f) + 0.5f * (float) Math.sin(w * 61.7f));
        return Math.max(-6f, Math.min(6f, v));
    }

    public static float shakePitch() {
        Playback p = current;
        if (p == null) return 0f;
        float w = (System.nanoTime() - p.startNs) / 1e9f;
        float v = amp(p) * 0.8f * ((float) Math.cos(w * 43.3f) + 0.5f * (float) Math.cos(w * 71.9f));
        return Math.max(-6f, Math.min(6f, v));
    }

    // ------------------------------------------------------------------
    // Math helpers
    // ------------------------------------------------------------------

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float ease(float x) {
        return x * x * (3f - 2f * x);
    }

    private static float easeOut(float x) {
        float i = 1f - x;
        return 1f - i * i * i;
    }

    private static float easeOutBack(float x) {
        float c1 = 1.70158f, c3 = c1 + 1f, i = x - 1f;
        return 1f + c3 * i * i * i + c1 * i * i;
    }

    private static int mix(int a, int b, float t) {
        t = clamp01(t);
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int scale(int c, float f) {
        int r = Math.min(255, (int) (((c >> 16) & 255) * f));
        int g = Math.min(255, (int) (((c >> 8) & 255) * f));
        int b = Math.min(255, (int) ((c & 255) * f));
        return (r << 16) | (g << 8) | b;
    }

    private static int argb(int rgb, float alpha) {
        int a = Math.max(4, Math.min(255, (int) (alpha * 255f)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    public static void render(GuiGraphicsExtractor g, DeltaTracker deltaTracker) {
        Playback p = current;
        if (p == null) return;
        JawbusConfig cfg = JawbusConfig.get();
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Tl tl = p.tl;
        int tier = p.tier;
        float t = p.t();
        float a0 = clamp01(Math.min(t / 0.3f, (tl.total() - t) / 0.6f));
        if (a0 <= 0.01f) return;
        float a = a0 * cfg.opacity;

        int w = g.guiWidth();
        int h = g.guiHeight();
        int cx = w / 2;
        int cell = Math.max(1, Math.round(h * 0.0105f * cfg.scale)); // scales with resolution and GUI scale
        int ground = Math.round(h * 0.72f);
        int chestY = ground - Math.round(GolemSprite.H * cell * 0.55f);
        boolean vial = p.reward.maxIntensity();

        // soft dim behind everything (never fully opaque, and it fades out with the animation)
        float[] dim = {0.22f, 0.32f, 0.42f};
        g.fill(0, 0, w, h, argb(0x000000, a * dim[tier - 1]));

        // ---- golem pose ----
        float growth = ease(clamp01((t - tl.cs()) / (tl.ce() - tl.cs())));
        float heat = 0f, sx = 1f, sy = 1f, rot = 0f, amp = 0f;
        float oy = (1f - ease(clamp01(t / 0.4f))) * cell * 6f;
        switch (tier) {
            case 1 -> {
                heat = 0.25f * growth;
                float s = t - 1.15f;
                if (s > 0f && s < 0.7f) rot = (float) Math.sin(s * 16f) * 0.09f * (1f - s / 0.7f); // stagger
                float u = clamp01((t - 1.85f) / (tl.burst() - 1.85f));
                if (u > 0f) {                                                                      // flop forward
                    sy = (float) Math.cos(u * u * 1.35f);
                    sx = 1f + 0.12f * u;
                }
                if (t >= tl.burst()) {
                    float tau = t - tl.burst();
                    sy = 0.22f + 0.05f * (float) (Math.exp(-tau * 9f) * Math.abs(Math.sin(tau * 18f)));
                    sx = 1.12f;
                    heat = 0.25f * Math.max(0f, 1f - tau / 1.5f);
                }
            }
            case 2 -> {
                if (t < tl.ce()) {
                    heat = 0.15f + 0.7f * growth * growth;
                    amp = cell * 0.7f * growth;
                } else {                                                                           // buckle
                    float u = ease(clamp01((t - tl.ce()) / (tl.burst() - tl.ce())));
                    heat = 0.85f + 0.15f * u;
                    sy = 1f - 0.20f * u;
                    sx = 1f + 0.10f * u;
                    amp = cell * (0.8f + 1.2f * u);
                }
            }
            default -> {
                if (t < tl.ce()) {
                    heat = 0.1f + 0.9f * (float) Math.pow(growth, 1.7);
                    amp = cell * (0.2f + 1.5f * growth);
                } else {                                                                           // dramatic pause
                    heat = 1f;
                    float pul = 1f + 0.015f * (float) Math.sin(t * 25f);
                    sx = pul;
                    sy = pul;
                }
            }
        }
        float jx = (float) Math.sin(t * 83f) * amp;
        float jy = (float) Math.cos(t * 97f) * amp * 0.6f;

        if (tier == 1 || t < tl.burst()) {
            float ga = (tier == 1 && t > tl.itemDone()) ? a * 0.6f : a;
            drawGolem(g, cell, cx, ground, sx, sy, rot, jx, jy + oy, heat, growth, GolemSprite.cracks(tier), ga, tier, t);
        }

        // ---- burst / impact ----
        float tau = t - tl.burst();
        if (tau >= 0f) {
            if (p.parts == null) p.parts = spawn(p, cell, cx, ground, h, tier == 2 ? 1.10f : 1f, tier == 2 ? 0.80f : 1f);
            effects(g, p, tau, cx, chestY, w, h, a);
            drawParticles(g, p, t, a);
        }

        // ---- reward ----
        float is = tl.burst() + (tier == 1 ? 0.05f : tier == 2 ? 0f : 0.1f);
        if (t >= is) {
            float u = clamp01((t - is) / (tl.itemDone() - is));
            float k = Math.max(0.7f, cfg.scale);
            float px = h * (tier == 1 ? 0.17f : tier == 2 ? 0.21f : 0.25f) * (vial ? 1.2f : 1f) * k;
            float arc = h * (tier == 1 ? 0.05f : tier == 2 ? 0.09f : 0.13f);
            float y1 = h * 0.38f;
            float iy = chestY + (y1 - chestY) * easeOut(u) - (float) Math.sin(u * Math.PI) * arc;
            if (u >= 1f) iy += (float) Math.sin((t - tl.itemDone()) * 3f) * cell * 0.8f;
            float sIcon = px / 16f * Math.max(0.01f, easeOutBack(u));
            int rc = p.reward.rarity.rgb;

            // rarity glow
            float pulse = 0.5f + 0.5f * (float) Math.sin(t * 5f);
            for (int n = 0; n < 3; n++) {
                int half = Math.round(px * (0.62f + 0.10f * n) + pulse * cell);
                int x0 = cx - half, y0 = Math.round(iy) - half;
                g.fill(x0, y0, cx + half, Math.round(iy) + half, argb(rc, a * u * (0.10f - n * 0.025f)));
                frame(g, x0, y0, cx + half, Math.round(iy) + half, Math.max(1, cell / 2), argb(rc, a * u * (0.55f - n * 0.17f)));
            }
            if (tier == 3 && u > 0.3f) sparkles(g, cx, Math.round(iy), px, cell, rc, t, a * u, vial ? 18 : 12);
            drawIcon(g, p.icon, cx, Math.round(iy), sIcon);

            // name + rarity (once the reward has mostly arrived)
            float ta = clamp01((u - 0.55f) / 0.3f) * a;
            if (ta > 0.02f) {
                float ts = Math.max(1.5f, Math.min(4f, h / 110f)) * (tier == 3 ? 1.2f : 1f);
                int ny = Math.round(y1 + px * 0.5f + cell * 3f);
                if (p.lootShare) centered(g, font, "LOOT SHARE", cx, ny - Math.round(11 * ts * 0.7f), ts * 0.6f, argb(0x55FFFF, ta));
                centered(g, font, p.reward.displayName, cx, ny, ts, argb(rc, ta));
                centered(g, font, p.reward.rarity.label, cx, ny + Math.round(10 * ts), ts * 0.7f, argb(rc, ta * 0.9f));
                if (!p.others.isEmpty()) {
                    StringBuilder sb = new StringBuilder("Also: ");
                    for (int n = 0; n < p.others.size(); n++) sb.append(n > 0 ? ", " : "").append(p.others.get(n).displayName);
                    centered(g, font, sb.toString(), cx, ny + Math.round(19 * ts), 1.2f, argb(0xAAAAAA, ta));
                }
            }
        }

        if (p.preview) centered(g, font, "PREVIEW  (tier " + tier + (vial ? ", max" : "") + ")", cx, 8, 1.2f, argb(0xFFFFFF, a * 0.8f));
    }

    // ---- golem ----

    private static int cellColor(GolemSprite s, int i, float[] cr, float growth, float heat, int tier, float t) {
        if (s.eye[i]) return mix(0xE0701A, 0xFFFFFF, heat);
        if (cr[i] >= 0f && cr[i] <= growth) {
            float flick = 0.92f + 0.08f * (float) Math.sin(t * 30f + i);
            int c = tier == 3 ? mix(0xFF6A00, 0xFFFFFF, (float) Math.pow(heat, 1.5)) : mix(0xFF4A00, 0xFFB030, heat);
            return scale(c, flick);
        }
        int c = mix(s.color[i], 0xFF4A10, heat * (tier == 3 ? 0.35f : 0.22f));
        if (tier == 3 && heat > 0.75f) c = mix(c, 0xFFF2D0, (heat - 0.75f) / 0.25f * 0.85f);
        return c;
    }

    private static void drawGolem(GuiGraphicsExtractor g, int cell, int feetX, int ground, float sx, float sy, float rot,
                                  float ox, float oy, float heat, float growth, float[] cr, float a, int tier, float t) {
        GolemSprite s = GolemSprite.get();
        int gw = GolemSprite.W * cell;
        var m = g.pose();
        m.pushMatrix();
        m.translate(feetX + ox, ground + oy);
        m.rotate(rot);
        m.scale(sx, sy);
        m.translate(-gw / 2f, -(float) (GolemSprite.H * cell));
        if (heat > 0.2f) { // glow bleeding past the silhouette
            for (int k = 1; k <= 3; k++) {
                int pad = cell * k * 2;
                g.fill(-pad, cell * 8 - pad, gw + pad, cell * 22 + pad, argb(0xFF5A10, a * heat * 0.09f));
            }
        }
        for (int y = 0; y < GolemSprite.H; y++) {
            for (int x = 0; x < GolemSprite.W; x++) {
                int i = y * GolemSprite.W + x;
                if (!s.filled[i]) continue;
                g.fill(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell, argb(cellColor(s, i, cr, growth, heat, tier, t), a));
            }
        }
        m.popMatrix();
    }

    // ---- explosion particles (debris = the golem's own cells) ----

    private static List<Particle> spawn(Playback p, int cell, int cx, int ground, int h, float sx, float sy) {
        Random r = new Random(p.seed);
        List<Particle> out = new ArrayList<>();
        GolemSprite s = GolemSprite.get();
        int tier = p.tier;
        boolean vial = p.reward.maxIntensity();
        float k = vial ? 1.4f : 1f;
        float ox = cx;
        float oy = ground - GolemSprite.H * cell * 0.55f;

        if (tier >= 2) {
            float frac = tier == 2 ? 0.45f : 0.85f;
            float speed = h * (tier == 2 ? 0.42f : 0.70f) * (vial ? 1.2f : 1f);
            float life = tier == 2 ? 1.8f : 2.4f;
            float[] cr = GolemSprite.cracks(tier);
            for (int y = 0; y < GolemSprite.H; y++) {
                for (int x = 0; x < GolemSprite.W; x++) {
                    int i = y * GolemSprite.W + x;
                    if (!s.filled[i] || r.nextFloat() > frac) continue;
                    float px = cx + (x + 0.5f - GolemSprite.W / 2f) * cell * sx;
                    float py = ground + (y + 0.5f - GolemSprite.H) * cell * sy;
                    float dx = px - ox, dy = py - oy;
                    float len = (float) Math.hypot(dx, dy) + 1f;
                    float sp = speed * (0.5f + r.nextFloat());
                    float vx = dx / len * sp + (r.nextFloat() - 0.5f) * speed * 0.2f;
                    float vy = dy / len * sp - speed * 0.35f * r.nextFloat();
                    int rgb = cr[i] >= 0f ? 0xFF8A20 : mix(s.color[i], 0xFF6A20, 0.35f);
                    out.add(new Particle(0, px, py, vx, vy, h * 1.25f, life * (0.7f + 0.3f * r.nextFloat()), rgb, cell, 0f));
                }
            }
        }

        int sparks = Math.round((tier == 1 ? 28 : tier == 2 ? 110 : 300) * k);
        float sx0 = tier == 1 ? cx : ox;
        float sy0 = tier == 1 ? ground - cell * 3f : oy;
        for (int n = 0; n < sparks; n++) {
            double ang = r.nextDouble() * Math.PI * 2;
            float sp = h * (tier == 1 ? 0.10f + 0.25f * r.nextFloat() : 0.25f + 0.90f * r.nextFloat());
            float vx = (float) Math.cos(ang) * sp;
            float vy = (float) Math.sin(ang) * sp - (tier == 1 ? h * 0.18f : 0f);
            int rgb = r.nextBoolean() ? 0xFF8A1A : 0xFFD040;
            out.add(new Particle(1, sx0, sy0, vx, vy, h * 0.9f, 0.5f + r.nextFloat() * 0.9f, rgb,
                    Math.max(1f, cell * (0.4f + 0.5f * r.nextFloat())), 0f));
        }

        int smoke = Math.round((tier == 1 ? 6 : tier == 2 ? 30 : 70) * k);
        for (int n = 0; n < smoke; n++) {
            float vx = (r.nextFloat() - 0.5f) * h * 0.16f;
            float vy = -h * (0.10f + 0.10f * r.nextFloat());
            int rgb = scale(0x505050, 0.6f + 0.8f * r.nextFloat());
            out.add(new Particle(2, sx0 + (r.nextFloat() - 0.5f) * cell * 8f, sy0 + (r.nextFloat() - 0.5f) * cell * 6f,
                    vx, vy, 0f, 1.2f + r.nextFloat(), rgb, cell * (1f + r.nextFloat()), r.nextFloat() * 0.25f));
        }
        return out;
    }

    private static void drawParticles(GuiGraphicsExtractor g, Playback p, float t, float a) {
        float since = t - p.tl.burst();
        for (Particle q : p.parts) {
            float tau = since - q.delay();
            if (tau < 0f || tau > q.life()) continue;
            float f = tau / q.life();
            float x = q.x0() + q.vx() * tau;
            float y = q.y0() + q.vy() * tau + 0.5f * q.grav() * tau * tau;
            float size;
            float alpha;
            int rgb = q.rgb();
            switch (q.kind()) {
                case 0 -> {
                    size = q.size();
                    alpha = 1f - f * f * f;
                    rgb = mix(rgb, 0x302018, f * 0.7f);
                }
                case 1 -> {
                    size = q.size() * (1f - 0.5f * f);
                    alpha = 1f - f;
                    rgb = mix(rgb, 0xC02000, f);
                }
                default -> {
                    size = q.size() * (1f + 2.5f * f);
                    alpha = 0.55f * (1f - f) * (f < 0.1f ? f / 0.1f : 1f);
                }
            }
            int x0 = Math.round(x - size / 2f), y0 = Math.round(y - size / 2f), sz = Math.max(1, Math.round(size));
            g.fill(x0, y0, x0 + sz, y0 + sz, argb(rgb, a * alpha));
        }
    }

    /** Flash, light and shockwaves. Tier 3 gets a short full-screen flash (under half a second). */
    private static void effects(GuiGraphicsExtractor g, Playback p, float tau, int cx, int cy, int w, int h, float a) {
        boolean vial = p.reward.maxIntensity();
        if (p.tier == 2) {
            if (tau < 0.28f) {
                float f = 1f - tau / 0.28f;
                disc(g, cx, cy, Math.round(h * (0.10f + 0.30f * tau / 0.28f)), argb(0xFF8A30, a * 0.55f * f));
            }
            if (tau < 0.7f) ring(g, cx, cy, h * (0.05f + 0.40f * tau / 0.7f), h * 0.02f, 0xFF6A20, a * (1f - tau / 0.7f) * 0.7f);
        } else if (p.tier == 3) {
            if (tau < 0.45f) {
                float f = 1f - tau / 0.45f;
                g.fill(0, 0, w, h, argb(0xFFFFFF, a * f * f * (vial ? 0.97f : 0.90f)));
            }
            if (tau < 1.0f) ring(g, cx, cy, h * (0.05f + 0.85f * tau), h * 0.03f * (1f - tau * 0.6f), 0xFFB040, a * (1f - tau) * 0.8f);
            float t2 = tau - 0.12f;
            if (t2 >= 0f && t2 < 0.9f) ring(g, cx, cy, h * (0.04f + 0.60f * t2), h * 0.02f, 0xFF5A20, a * (1f - t2 / 0.9f) * 0.7f);
            float t3 = tau - 0.25f;
            if (vial && t3 >= 0f && t3 < 0.9f) ring(g, cx, cy, h * (0.04f + 0.70f * t3), h * 0.025f, 0xFF55FF, a * (1f - t3 / 0.9f) * 0.8f);
        }
    }

    private static void disc(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        if (r < 2) return;
        int step = Math.max(2, r / 48);
        for (int dy = -r; dy <= r; dy += step) {
            int hw = (int) Math.sqrt((double) r * r - (double) dy * dy);
            g.fill(cx - hw, cy + dy, cx + hw, cy + dy + step, color);
        }
    }

    private static void ring(GuiGraphicsExtractor g, int cx, int cy, float outer, float thick, int rgb, float alpha) {
        int ro = Math.round(outer);
        int ri = Math.max(0, Math.round(outer - thick));
        if (ro < 2 || alpha <= 0.01f) return;
        int color = argb(rgb, alpha);
        int step = Math.max(2, ro / 60);
        for (int dy = -ro; dy <= ro; dy += step) {
            int wo = (int) Math.sqrt((double) ro * ro - (double) dy * dy);
            if (Math.abs(dy) < ri) {
                int wi = (int) Math.sqrt((double) ri * ri - (double) dy * dy);
                g.fill(cx - wo, cy + dy, cx - wi, cy + dy + step, color);
                g.fill(cx + wi, cy + dy, cx + wo, cy + dy + step, color);
            } else {
                g.fill(cx - wo, cy + dy, cx + wo, cy + dy + step, color);
            }
        }
    }

    private static void sparkles(GuiGraphicsExtractor g, int cx, int cy, float px, int cell, int rgb, float t, float a, int n) {
        for (int i = 0; i < n; i++) {
            float ang = (float) (i * Math.PI * 2 / n) + t * 0.9f;
            float r = px * (0.75f + 0.25f * (float) Math.sin(t * 3f + i));
            int x = Math.round(cx + (float) Math.cos(ang) * r);
            int y = Math.round(cy + (float) Math.sin(ang) * r);
            int arm = cell * (1 + (i % 3));
            float ph = 0.5f + 0.5f * (float) Math.sin(t * 6f + i);
            int c = argb(rgb, a * ph);
            g.fill(x - arm, y, x + arm + 1, y + 1, c);
            g.fill(x, y - arm, x + 1, y + arm + 1, c);
        }
    }

    // ---- small draw helpers ----

    private static void drawIcon(GuiGraphicsExtractor g, ItemStack stack, int cx, int cy, float scale) {
        var m = g.pose();
        m.pushMatrix();
        m.translate(cx, cy);
        m.scale(scale, scale);
        m.translate(-8f, -8f);
        g.item(stack, 0, 0);
        m.popMatrix();
    }

    private static void centered(GuiGraphicsExtractor g, Font font, String text, int cx, int y, float scale, int color) {
        var m = g.pose();
        m.pushMatrix();
        m.translate(cx, y);
        m.scale(scale, scale);
        g.text(font, text, -font.width(text) / 2, 0, color, true);
        m.popMatrix();
    }

    private static void frame(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int th, int color) {
        g.fill(x0, y0, x1, y0 + th, color);
        g.fill(x0, y1 - th, x1, y1, color);
        g.fill(x0, y0 + th, x0 + th, y1 - th, color);
        g.fill(x1 - th, y0 + th, x1, y1 - th, color);
    }
}
