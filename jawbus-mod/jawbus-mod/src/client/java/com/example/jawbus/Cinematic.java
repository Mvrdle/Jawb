package com.example.jawbus;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
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

    // cs/ce = crack growth start/end of each stage (tier 1 has one stage, tier 2 two, tier 3 three), burst = flop /
    // rupture / detonation, itemDone = reward settled, total = end (seconds @ duration 1.0)
    private record Tl(float[] cs, float[] ce, float burst, float itemDone, float total) {
    }

    private static double[] dbl(float[] f) {
        double[] d = new double[f.length];
        for (int i = 0; i < f.length; i++) d[i] = f[i];
        return d;
    }

    private record Cue(float t, Runnable action) {
    }

    private record Particle(int kind, float x0, float y0, float vx, float vy, float grav, float life, int rgb,
                            float size, float delay) {
    }

    private record Req(Reward reward, List<Reward> others, boolean lootShare, boolean preview, String tag) {
    }

    private static final class Playback {
        final Reward reward;
        final List<Reward> others;
        final boolean lootShare;
        final boolean preview;
        final String tag;
        final boolean fast;
        final int tier;
        final Tl tl;
        final float dur;
        final long startNs = System.nanoTime();
        final long seed = System.nanoTime();
        final List<Cue> cues = new ArrayList<>();
        int nextCue;
        List<Particle> parts;
        ItemStack icon;
        float[] chest = {Golem3D.FW / 2f, Golem3D.FH * 0.5f};
        boolean g3ok = true;

        Playback(Req r, Tl tl, float dur, boolean fast) {
            this.reward = r.reward();
            this.others = r.others();
            this.lootShare = r.lootShare();
            this.preview = r.preview();
            this.tag = r.tag();
            this.fast = fast;
            this.tier = r.reward().tier;
            this.tl = tl;
            this.dur = dur;
        }

        /** Elapsed time on the animation timeline (already divided by the duration setting). */
        float t() {
            return (System.nanoTime() - startNs) / 1e9f / dur;
        }
    }

    private static final GolemRenderer GR = new GolemRenderer();   // draws the 3D golem on a background thread
    private static final ArrayDeque<Req> QUEUE = new ArrayDeque<>();
    private static Playback current;
    private static final int MAX_QUEUE = 12;       // reveals waiting (rewards beyond this are merged, never dropped)
    private static final int FAST_BACKLOG = 2;     // this many waiting: play shortened, lighter reveals
    private static final long GAP_NS = 400_000_000L;
    private static long gapUntilNs;
    private static long hiddenSinceNs;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Queue the animation for a REAL reward (tier comes from the reward itself). */
    public static void enqueue(Reward reward, List<Reward> others, boolean lootShare, boolean preview, String tag) {
        QUEUE.add(new Req(reward, new ArrayList<>(others), lootShare, preview, tag));
        while (QUEUE.size() > MAX_QUEUE) mergeWeakest();
    }

    /** Over the cap: fold the weakest waiting reveal into the strongest one's "Also:" list. Nothing is lost. */
    private static void mergeWeakest() {
        List<Req> all = new ArrayList<>(QUEUE);
        Req weak = all.get(0);
        Req strong = all.get(0);
        for (Req r : all) {
            if (r.reward().priority < weak.reward().priority) weak = r;
            if (r.reward().priority >= strong.reward().priority) strong = r;
        }
        if (weak == strong) return;
        List<Reward> o = new ArrayList<>(strong.others());
        o.add(weak.reward());
        o.addAll(weak.others());
        all.remove(weak);
        all.set(all.indexOf(strong), new Req(strong.reward(), o, strong.lootShare(), strong.preview(), strong.tag()));
        QUEUE.clear();
        QUEUE.addAll(all);
    }

    /** Menu preview: 1 = tier 1, 2 = tier 2, 3 = tier 3, 4 = tier 3 maximum (Radioactive Vial). Labelled PREVIEW. */
    public static void preview(int mode) {
        Reward r = switch (mode) {
            case 1 -> Reward.SILVER_MAGMAFISH;
            case 2 -> Reward.MAGMA_LORD_FRAGMENT;
            case 3 -> Reward.RADIOACTIVE_VIAL;
            default -> Reward.CARMINE_DYE;
        };
        enqueue(r, List.of(), false, true, null);   // joins the end of the queue: never interrupts a running reveal
    }

    public static void reset() {
        QUEUE.clear();
        current = null;
        GR.clear();
        gapUntilNs = 0;
        hiddenSinceNs = 0;
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
            if (System.nanoTime() < gapUntilNs) return;
            Req r = QUEUE.poll();
            if (r == null) return;
            try {
                current = start(r, QUEUE.size() >= FAST_BACKLOG);
            } catch (Throwable ex) {
                current = null;                        // a broken reveal must never block the ones behind it
                KillTracker.debug("A reveal failed to start (" + ex + "), skipped.");
                return;
            }
        }
        Playback p = current;
        float t = p.t();
        while (p.nextCue < p.cues.size() && p.cues.get(p.nextCue).t() <= t) {
            Cue c = p.cues.get(p.nextCue++);
            try {
                c.action().run();
            } catch (Throwable ignored) {
                // a sound failing must not stop the animation
            }
        }
        if (t >= p.tl.total()) {
            current = null;
            gapUntilNs = System.nanoTime() + GAP_NS;
        }
    }

    private static Tl timeline(int tier, boolean vial) {
        // Higher tiers climb through the earlier stages one at a time, with a still pause after each, so the
        // viewer cannot tell which stage is the last one until the golem finally detonates. Every length below is
        // the default (seconds at duration 1.0); the timing sliders in the menu scale them.
        JawbusConfig c = JawbusConfig.get();
        int n = tier == 1 ? 1 : tier == 2 ? 2 : vial ? 4 : 3;
        int idx = tier == 1 ? 0 : tier == 2 ? 1 : vial ? 3 : 2;
        float[] len = {tier == 1 ? 0.8f : 0.75f, 1.8f, 2.1f, 2.2f};
        float[] gap = {1.4f, 0.6f, 0.7f};
        float[] hold = {1.1f, 0.5f, 1.0f, 1.0f};
        float[] toItem = {0.85f, 1.0f, 1.6f, 1.7f};
        float[] after = {3.1f, 4.0f, 4.0f, 4.2f};
        float[] cs = new float[n], ce = new float[n];
        float t = 0.35f;
        for (int i = 0; i < n; i++) {
            cs[i] = t;
            ce[i] = t + len[i] / c.buildSpeed;
            t = ce[i] + (i < n - 1 ? gap[i] * c.pauseScale : 0f);
        }
        float burst = ce[n - 1] + hold[idx] * c.finalHold;
        float itemDone = burst + toItem[idx];
        return new Tl(cs, ce, burst, itemDone, itemDone + after[idx] * c.rewardTime);
    }

    private static boolean skinTried;

    /** Reads the game's own Iron Golem texture so Jawbus wears the real skin (respects resource packs). */
    static void ensureSkin() {
        if (skinTried) return;
        skinTried = true;
        try {
            var res = Minecraft.getInstance().getResourceManager()
                    .getResource(Identifier.fromNamespaceAndPath("minecraft", "textures/entity/iron_golem/iron_golem.png"));
            if (res.isEmpty()) {
                KillTracker.debug("Iron Golem texture not found, using the built-in look.");
                return;
            }
            try (java.io.InputStream in = res.get().open()) {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(in);
                int w = img.getWidth(), h = img.getHeight();
                Golem3D.setSkin(img.getRGB(0, 0, w, h, null, 0, w), w, h);
            }
        } catch (Throwable ex) {
            KillTracker.debug("Could not read the Iron Golem texture (" + ex + "), using the built-in look.");
        }
    }

    private static Playback start(Req r, boolean fast) {
        ensureSkin();
        Icons.refresh();
        boolean vial = r.reward().maxIntensity();
        // with a backlog the reveal is shortened (never skipped) and uses fewer particles and sounds
        Playback p = new Playback(r, timeline(r.reward().tier, vial), JawbusConfig.get().duration * (fast ? 0.6f : 1f), fast);
        p.icon = Icons.get(r.reward());
        applyDetail();
        lastFrameNs = 0;
        GR.begin(p.tier, vial, p.seed, dbl(p.tl.cs()), dbl(p.tl.ce()), p.tl.burst());   // builds in the background
        buildCues(p.cues, p.tier, vial, p.tl);
        prewarm(p.cues);
        p.cues.sort(Comparator.comparingDouble(Cue::t));
        if (fast) {                                    // thin out the audio, keep the big moment
            List<Cue> keep = new ArrayList<>();
            for (int i = 0; i < p.cues.size(); i++) {
                Cue c = p.cues.get(i);
                if (i % 2 == 0 || Math.abs(c.t() - p.tl.burst()) < 0.06f) keep.add(c);
            }
            p.cues.clear();
            p.cues.addAll(keep);
        }
        return p;
    }

    // ------------------------------------------------------------------
    // Audio
    // ------------------------------------------------------------------

    /** base sound -> slot name, so a sound pack can override a cue without touching the cue list */
    private static final java.util.IdentityHashMap<Object, String> SLOT = new java.util.IdentityHashMap<>();

    private static void play(Object snd, float pitch, float vol) {
        SoundEvent ev = snd instanceof SoundEvent se ? se : (SoundEvent) ((Holder<?>) snd).value();
        String slot = SLOT.get(snd);
        if (slot != null) {
            SoundEvent ov = SoundPacks.override(slot);
            if (ov != null) ev = ov;
        }
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ev, pitch, Math.max(0f, vol)));
    }

    /** Regular-volume cue (rumbles, cracks, impacts for tiers 1 and 2). */
    private static final java.util.IdentityHashMap<Object, Boolean> WARM = new java.util.IdentityHashMap<>();

    /** Loads every sound of this reveal ahead of time (near-silent) so the first big blast does not stall the game. */
    private static void prewarm(List<Cue> cues) {
        try {
            for (Object o : PRE) {
                if (WARM.put(o, true) == null) play(o, 1f, 0.001f);
            }
        } catch (Throwable ignored) {
        }
    }

    private static final List<Object> PRE = new ArrayList<>();

    private static Cue reg(float t, Object snd, float pitch, float vol) {
        return new Cue(t, () -> play(snd, pitch, vol * JawbusConfig.get().regularVolume));
    }

    /** Jackpot-volume cue (tier 3 explosion, bass, jackpot). */
    private static Cue jack(float t, Object snd, float pitch, float vol) {
        return new Cue(t, () -> play(snd, pitch, vol * JawbusConfig.get().jackpotVolume));
    }

    /** Looks a vanilla sound up by field name (names differ between versions); falls back so it can never fail to compile or play. */
    private static Object snd(String name, Object fallback) {
        try {
            Object v = SoundEvents.class.getField(name).get(null);
            if (v instanceof SoundEvent || v instanceof Holder<?>) return v;
        } catch (Throwable ignored) {
        }
        return fallback;
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
        Object thunder = snd("LIGHTNING_BOLT_THUNDER", boom);
        Object golem = snd("IRON_GOLEM_DAMAGE", land);
        Object hurt = snd("IRON_GOLEM_HURT", bass);
        Object reson = snd("AMETHYST_BLOCK_RESONATE", bell);
        Object crystal = snd("AMETHYST_CLUSTER_BREAK", chime);
        Object glass = snd("GLASS_BREAK", blast);
        Object conduit = snd("CONDUIT_ACTIVATE", level);
        Object totem = snd("TOTEM_USE", level);
        Object sonic = snd("WARDEN_SONIC_BOOM", boom);
        Object growl = snd("ENDER_DRAGON_GROWL", thunder);
        Object portal = snd("END_PORTAL_SPAWN", thunder);
        Object toast = snd("UI_TOAST_CHALLENGE_COMPLETE", level);
        PRE.clear();
        PRE.addAll(java.util.Arrays.asList(bass, land, boom, chime, blast, level, bell, orb, twinkle, thunder, golem, hurt, reson, crystal, glass, conduit, totem, sonic, growl, portal, toast));

        SLOT.clear();
        SLOT.put(bass, "rumble");     SLOT.put(land, "impact");
        SLOT.put(boom, "burst");      SLOT.put(chime, "chime");
        SLOT.put(blast, "blast");     SLOT.put(level, "levelup");
        SLOT.put(bell, "bell");       SLOT.put(orb, "orb");
        SLOT.put(twinkle, "twinkle"); SLOT.put(thunder, "thunder");
        SLOT.put(golem, "golem");     SLOT.put(hurt, "golem_hurt");
        SLOT.put(reson, "resonate");  SLOT.put(crystal, "crystal");
        SLOT.put(glass, "glass");     SLOT.put(conduit, "conduit");
        SLOT.put(totem, "totem");     SLOT.put(sonic, "sonic");
        SLOT.put(growl, "growl");     SLOT.put(portal, "portal");
        SLOT.put(toast, "toast");

        float cs1 = tl.cs()[0], ce1 = tl.ce()[0], burst = tl.burst();
        // stage 1 (every tier): first crack, twitch, stagger - subdued
        c.add(reg(cs1, bass, 1.4f, 0.35f));
        c.add(reg(cs1 + 0.3f, bass, 1.2f, 0.30f));
        c.add(reg(ce1 + 0.05f, bass, 0.8f, 0.35f));
        c.add(reg(ce1 + 0.35f, bass, 0.7f, 0.35f));
        if (tier == 1) {                                   // the flop: the anvil drop stays for the lowest tier
            c.add(reg(ce1 + 0.75f, bass, 0.6f, 0.30f));
            c.add(reg(burst, land, 0.6f, 0.5f));
            c.add(reg(burst, boom, 1.6f, 0.15f));
            c.add(reg(burst + 0.25f, chime, 1.2f, 0.35f));
            return;
        }
        c.add(reg(ce1 + 0.65f, bass, 0.6f, 0.30f));        // rocks forward, then holds (silence in the pause)

        // stage 2: rising rumble with stone grinding, ends with a thump
        float cs2 = tl.cs()[1], ce2 = tl.ce()[1];
        for (float x = cs2; x < ce2; x += 0.22f) {
            float u = (x - cs2) / (ce2 - cs2);
            c.add(reg(x, bass, 0.5f + 0.6f * u, 0.25f + 0.6f * u));
        }
        for (float x = cs2 + 0.4f; x < ce2 - 0.2f; x += 0.7f) c.add(reg(x, hurt, 0.6f + 0.3f * (x - cs2) / (ce2 - cs2), 0.35f));
        c.add(reg(ce2, golem, 0.55f, 0.6f));
        c.add(reg(ce2 + 0.05f, bass, 0.4f, 0.6f));
        if (tier == 2) {                                   // the rupture: layered boom + glassy shatter + chime rise
            c.add(reg(burst - 0.35f, reson, 0.8f, 0.45f));
            c.add(reg(burst, boom, 0.8f, 0.9f));
            c.add(reg(burst, golem, 0.5f, 0.7f));
            c.add(reg(burst + 0.05f, crystal, 1.0f, 0.6f));
            c.add(reg(burst + 0.20f, chime, 1.0f, 0.5f));
            c.add(reg(burst + 0.30f, blast, 1.2f, 0.35f));
            arp(c, burst + 0.35f, chime, new float[]{1.0f, 1.26f, 1.5f}, 0.11f, 0.45f, false);
            return;
        }

        // stage 3: heartbeat, rising tone, then a held pause
        float cs3 = tl.cs()[2], ce3 = tl.ce()[2];
        for (float x = cs3; x < ce3; x += 0.2f) {
            float u = (x - cs3) / (ce3 - cs3);
            c.add(reg(x, bass, 0.4f + 0.8f * u, 0.3f + 0.7f * u));
        }
        for (float x = cs3; x < ce3 - 0.3f; x += 0.45f) {
            float u = (x - cs3) / (ce3 - cs3);
            c.add(reg(x, reson, 0.6f + 1.0f * u, 0.30f + 0.4f * u));
        }
        c.add(reg(ce3, golem, 0.5f, 0.8f));
        c.add(reg(ce3, thunder, 0.7f, 0.5f));
        c.add(reg(ce3 + 0.05f, bass, 0.35f, 0.8f));

        if (!vial) {                                       // tier 3: final held breath, heartbeat, jackpot
            for (float d : new float[]{0.7f, 0.45f, 0.25f, 0.10f}) {
                c.add(reg(burst - d, bass, 0.5f, 0.8f));
                c.add(reg(burst - d + 0.08f, bass, 0.45f, 0.6f));
            }
            jackpot(c, burst, boom, golem, bass, thunder, glass, level, blast, bell, orb, conduit);
            return;
        }

        // stage 4 (maximum only): everything louder, higher, and longer
        float cs4 = tl.cs()[3], ce4 = tl.ce()[3];
        for (float x = cs4; x < ce4; x += 0.17f) {
            float u = (x - cs4) / (ce4 - cs4);
            c.add(reg(x, bass, 0.45f + 1.0f * u, 0.4f + 0.7f * u));
        }
        for (float x = cs4; x < ce4 - 0.2f; x += 0.3f) {
            float u = (x - cs4) / (ce4 - cs4);
            c.add(reg(x, reson, 0.7f + 1.3f * u, 0.4f + 0.5f * u));
        }
        c.add(reg(cs4 + 0.9f, sonic, 1.0f, 0.5f));
        c.add(reg(ce4 - 0.6f, growl, 0.8f, 0.45f));
        for (float d : new float[]{0.7f, 0.45f, 0.25f, 0.10f}) {
            c.add(reg(burst - d, bass, 0.5f, 0.9f));
            c.add(reg(burst - d + 0.08f, bass, 0.45f, 0.7f));
        }
        jackpot(c, burst, boom, golem, bass, thunder, glass, level, blast, bell, orb, conduit);
        c.add(jack(burst + 0.05f, sonic, 0.8f, 1.0f));
        c.add(jack(burst + 0.10f, portal, 1.0f, 0.8f));
        c.add(jack(burst + 0.25f, boom, 0.4f, 1.0f));
        c.add(jack(burst + 0.9f, totem, 1.0f, 1.0f));
        c.add(jack(burst + 1.0f, toast, 1.0f, 0.8f));
        arp(c, burst + 0.9f, bell, new float[]{1.0f, 1.14f, 1.26f, 1.4f, 1.5f, 1.68f, 1.88f, 2.0f}, 0.07f, 0.7f, true);
        arp(c, burst + 1.1f, twinkle, new float[]{1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f}, 0.08f, 0.6f, true);
    }

    private static void jackpot(List<Cue> c, float b, Object boom, Object golem, Object bass, Object thunder,
                                Object glass, Object level, Object blast, Object bell, Object orb, Object conduit) {
        c.add(jack(b, boom, 0.5f, 1.0f));
        c.add(jack(b, golem, 0.45f, 1.0f));
        c.add(jack(b, bass, 0.35f, 1.0f));
        c.add(jack(b, thunder, 0.8f, 0.7f));
        c.add(jack(b + 0.05f, glass, 0.7f, 0.6f));
        c.add(jack(b + 0.15f, level, 1.0f, 1.0f));
        c.add(jack(b + 0.20f, blast, 1.0f, 0.8f));
        c.add(jack(b + 0.30f, conduit, 1.0f, 0.7f));
        arp(c, b + 0.3f, bell, new float[]{0.84f, 1.0f, 1.26f, 1.5f, 1.68f, 2.0f}, 0.09f, 0.7f, true);
        for (int i = 0; i < 8; i++) c.add(jack(b + 0.5f + i * 0.06f, orb, 0.8f + 0.1f * i, 0.5f));
    }

    private static void arp(List<Cue> c, float t0, Object snd, float[] pitches, float step, float vol, boolean jackpot) {
        for (int i = 0; i < pitches.length; i++) {
            c.add(jackpot ? jack(t0 + i * step, snd, pitches[i], vol) : reg(t0 + i * step, snd, pitches[i], vol));
        }
    }

    // ------------------------------------------------------------------
    // Screen shake (read by CameraMixin; visual only)
    // ------------------------------------------------------------------

    private static float amp(Playback p) {
        float t = p.t();
        Tl tl = p.tl;
        float tau = t - tl.burst();
        float ce1 = tl.ce()[0];
        float a = 0f;
        if (p.tier >= 2 && t >= ce1 && t < ce1 + 0.3f) a = 0.12f * (1f - (t - ce1) / 0.3f);   // the first twitch
        if (p.tier == 1) {
            if (tau >= 0f && tau < 0.6f) a = 0.30f * (1f - tau / 0.6f) * (1f - tau / 0.6f);
        } else if (p.tier == 2) {
            float cs2 = tl.cs()[1];
            if (t >= cs2 && t < tl.burst()) {
                float u = (t - cs2) / (tl.burst() - cs2);
                a = 0.10f + 0.35f * u * u;
            } else if (tau >= 0f && tau < 1.6f) {
                a = 1.3f * (float) Math.exp(-tau * 2.6f);
            }
        } else {
            float cs2 = tl.cs()[1], ce2 = tl.ce()[1], cs3 = tl.cs()[2], ce3 = tl.ce()[2];
            boolean four = tl.cs().length > 3;
            if (t >= cs2 && t < ce2) {
                float u = (t - cs2) / (ce2 - cs2);
                a = 0.10f + 0.30f * u * u;
            } else if (t >= ce2 && t < ce2 + 0.6f) {
                a = 0.45f * (float) Math.exp(-(t - ce2) * 8f);
            }
            if (t >= cs3 && t < ce3) {
                float u = (t - cs3) / (ce3 - cs3);
                a = 0.15f + 0.7f * u * u;
            } else if (t >= ce3 && t < ce3 + 0.6f && four) {
                a = 0.6f * (float) Math.exp(-(t - ce3) * 7f);
            }
            if (four && t >= tl.cs()[3] && t < tl.ce()[3]) {
                float u = (t - tl.cs()[3]) / (tl.ce()[3] - tl.cs()[3]);
                a = 0.25f + 0.9f * u * u;
            } else if (t >= (four ? tl.ce()[3] : ce3) && t < burst0(tl)) {
                a = 0.12f;
            } else if (tau >= 0f && tau < 2.2f) {
                a = 3.2f * (float) Math.exp(-tau * 1.9f);
            }
            if (p.reward.maxIntensity()) a *= 1.45f;
        }
        return a * JawbusConfig.get().shakeIntensity;
    }

    private static float burst0(Tl tl) {
        return tl.burst();
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
        try {
            renderInner(g);
        } catch (Throwable ex) {
            current = null;                            // drop the broken reveal so the queue and the chat recover
            KillTracker.debug("Drawing a reveal failed (" + ex + "), skipped.");
        }
    }

    // ---- picture detail: every merged rectangle costs the game's GUI a fixed amount (and more as their number grows),
    // so a chunky golem is far cheaper. Auto starts at 2x2 blocks and moves by itself according to the frame time.
    private static int autoBlock = 2;
    private static long lastFrameNs, lastAdaptNs;
    private static float smoothMs = 8f;

    private static void applyDetail() {
        int mode = JawbusConfig.get().detailMode;
        Golem3D.rectBudget = 1500;
        Golem3D.blockSize = mode == 3 ? autoBlock : new int[]{3, 2, 1}[mode];
    }

    private static void adaptDetail() {
        long now = System.nanoTime();
        if (lastFrameNs != 0) {
            float ms = (now - lastFrameNs) / 1e6f;
            if (ms < 120f) smoothMs = smoothMs * 0.92f + ms * 0.08f;
        }
        lastFrameNs = now;
        if (JawbusConfig.get().detailMode != 3) return;
        long since = now - lastAdaptNs;
        if (smoothMs > 10f && autoBlock < 4 && since > 350_000_000L) {          // below ~100 fps: go chunkier
            autoBlock++;
            lastAdaptNs = now;
        } else if (smoothMs < 4f && autoBlock > 1 && since > 3_000_000_000L) {  // above ~250 fps for a while: sharpen again
            autoBlock--;
            lastAdaptNs = now;
        }
        Golem3D.blockSize = autoBlock;
    }

    private static void renderInner(GuiGraphicsExtractor g) {
        Playback p = current;
        if (p == null) return;
        JawbusConfig cfg = JawbusConfig.get();
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        Tl tl = p.tl;
        int tier = p.tier;
        float t = p.t();
        adaptDetail();
        float a0 = clamp01(Math.min(t / 0.3f, (tl.total() - t) / 0.6f));
        if (a0 <= 0.01f) return;
        float a = a0 * cfg.opacity;

        int w = g.guiWidth();
        int h = g.guiHeight();
        int cx = w / 2 + Math.round(cfg.offsetX * w);                 // the animation can be dragged elsewhere
        int oyShift = Math.round(cfg.offsetY * h);
        int cell = Math.max(1, Math.round(h * 0.0105f * cfg.scale)); // scales with resolution and GUI scale
        float sf = h * 0.80f * cfg.scale / Golem3D.FH;               // 3D framebuffer pixel -> GUI pixel
        float fox = cx - Golem3D.FW * sf / 2f;
        float foy = h * 0.50f + oyShift - Golem3D.FH * 0.52f * sf;
        float[] chestNow = GR.chest();
        if (chestNow != null) p.chest = chestNow;
        float chestX = fox + p.chest[0] * sf;
        int chestY = Math.round(foy + p.chest[1] * sf);
        boolean vial = p.reward.maxIntensity();

        // soft dim behind everything (never fully opaque, and it fades out with the animation)
        float[] dim = {0.45f, 0.55f, 0.65f};
        g.fill(0, 0, w, h, argb(0x000000, a * dim[tier - 1]));

        // ---- the 3D golem (software-rendered, painted as merged rectangles) ----
        if (p.g3ok) {
            if (GR.failed()) {
                p.g3ok = false;                    // fall back to effects + reward only
            } else {
                GR.request(t, (tier == 1 && t > tl.itemDone()) ? a * 0.6f : a);
                GolemRenderer.Frame fr = GR.frame();
                if (fr != null) paintFrame(g, fr, fox, foy, sf);
            }
        }

        // ---- burst / impact ----
        float tau = t - tl.burst();
        if (tau >= 0f) {
            if (p.parts == null) p.parts = spawn(p, cell, Math.round(chestX), chestY, h);
            effects(g, p, tau, Math.round(chestX), chestY, w, h, a);
            drawParticles(g, p, t, a);
        }

        // ---- reward ----
        float is = tl.burst() + (tier == 1 ? 0.05f : tier == 2 ? 0f : 0.1f);
        if (t >= is) {
            float u = clamp01((t - is) / (tl.itemDone() - is));
            float k = Math.max(0.7f, cfg.scale);
            float px = h * (tier == 1 ? 0.17f : tier == 2 ? 0.21f : 0.25f) * (vial ? 1.2f : 1f) * k;
            float arc = h * (tier == 1 ? 0.05f : tier == 2 ? 0.09f : 0.13f);
            float y1 = h * 0.38f + oyShift;
            float iy = chestY + (y1 - chestY) * easeOut(u) - (float) Math.sin(u * Math.PI) * arc;
            int rx = Math.round(cx + (chestX - cx) * (1f - easeOut(u)));
            if (u >= 1f) iy += (float) Math.sin((t - tl.itemDone()) * 3f) * cell * 0.8f;
            float sIcon = px / 16f * Math.max(0.01f, easeOutBack(u));
            int rc = p.reward.rarity.rgb;

            // rarity glow
            float pulse = 0.5f + 0.5f * (float) Math.sin(t * 5f);
            for (int n = 0; n < 3; n++) {
                int half = Math.round(px * (0.62f + 0.10f * n) + pulse * cell);
                int x0 = rx - half, y0 = Math.round(iy) - half;
                g.fill(x0, y0, rx + half, Math.round(iy) + half, argb(rc, a * u * (0.10f - n * 0.025f)));
                frame(g, x0, y0, rx + half, Math.round(iy) + half, Math.max(1, cell / 2), argb(rc, a * u * (0.55f - n * 0.17f)));
            }
            if (tier == 3 && u > 0.3f) sparkles(g, rx, Math.round(iy), px, cell, rc, t, a * u, vial ? 18 : 12);
            float xs = 1f;                       // never squashed: the icon stays full width the whole time
            drawIcon(g, p.icon, rx, Math.round(iy), sIcon, xs);

            // name + rarity (once the reward has mostly arrived)
            float ta = clamp01((u - 0.55f) / 0.3f) * a;
            if (ta > 0.02f) {
                float ts = Math.max(1.5f, Math.min(4f, h / 110f)) * (tier == 3 ? 1.2f : 1f);
                int ny = Math.round(y1 + px * 0.5f + cell * 3f);
                if (p.lootShare) centered(g, font, "LOOT SHARE", cx, ny - Math.round(11 * ts * 0.7f), ts * 0.6f, argb(0x55FFFF, ta));
                centered(g, font, p.reward.displayName, cx, ny, ts, argb(rc, ta));
                centered(g, font, p.reward.rarity.label, cx, ny + Math.round(10 * ts), ts * 0.7f, argb(rc, ta * 0.9f));
                int line = 19;
                String chance = p.reward.chanceLabel(p.lootShare);
                if (chance != null) {
                    centered(g, font, (p.lootShare ? "Loot share chance: " : "Drop chance: ") + chance, cx,
                            ny + Math.round(line * ts), 1.3f, argb(0xFFE9B0, ta));
                    line += 10;
                }
                if (!p.others.isEmpty()) {
                    StringBuilder sb = new StringBuilder("Also: ");
                    for (int n = 0; n < p.others.size(); n++) sb.append(n > 0 ? ", " : "").append(p.others.get(n).displayName);
                    centered(g, font, sb.toString(), cx, ny + Math.round(line * ts), 1.2f, argb(0xAAAAAA, ta));
                }
            }
        }

        int topY = 8;
        if (p.preview) {
            centered(g, font, "PREVIEW  (tier " + tier + (vial ? ", max" : "") + ")", cx, topY, 1.2f, argb(0xFFFFFF, a * 0.8f));
            topY += 12;
        }
        if (p.tag != null) centered(g, font, p.tag, cx, topY, 1.2f, argb(0xFFCC66, a * 0.8f));
        if (QUEUE.size() > 0) centered(g, font, "+" + QUEUE.size() + " more", cx, topY + 12, 1.1f, argb(0xAAAAAA, a * 0.7f));
    }

    private static void paintFrame(GuiGraphicsExtractor g, GolemRenderer.Frame f, float fox, float foy, float sf) {
        for (int i = 0; i < f.n; i++) {
            g.fill(Math.round(fox + f.x0[i] * sf), Math.round(foy + f.y0[i] * sf),
                    Math.round(fox + f.x1[i] * sf), Math.round(foy + f.y1[i] * sf), f.c[i]);
        }
    }

    private static void paintRects(GuiGraphicsExtractor g, Golem3D gg, float fox, float foy, float sf) {
        for (int i = 0; i < gg.rectCount; i++) {
            g.fill(Math.round(fox + gg.rx0[i] * sf), Math.round(foy + gg.ry0[i] * sf),
                    Math.round(fox + gg.rx1[i] * sf), Math.round(foy + gg.ry1[i] * sf), gg.rc[i]);
        }
    }

    private static Golem3D editorG3;
    private static int editorVer = -1;

    /** A standing golem at the current animation position, drawn by the "Move animation" screen. */
    public static void renderEditor(GuiGraphicsExtractor g) {
        JawbusConfig cfg = JawbusConfig.get();
        int w = g.guiWidth();
        int h = g.guiHeight();
        int cx = w / 2 + Math.round(cfg.offsetX * w);
        float sf = h * 0.80f * cfg.scale / Golem3D.FH;
        float fox = cx - Golem3D.FW * sf / 2f;
        float foy = h * 0.50f + Math.round(cfg.offsetY * h) - Golem3D.FH * 0.52f * sf;
        try {
            ensureSkin();
            if (editorG3 == null || editorVer != Golem3D.skinVersion) {
                editorVer = Golem3D.skinVersion;
                editorG3 = new Golem3D();
                editorG3.begin(2, false, 7L, new double[]{0.35, 2.3}, new double[]{1.1, 4.0}, 4.6);
            }
            editorG3.draw(1.8);
            editorG3.buildRects(0.9f);
            paintRects(g, editorG3, fox, foy, sf);
        } catch (Throwable ignored) {
            // no preview golem: the frame below still shows where the animation will be
        }
        frame(g, Math.round(fox + Golem3D.FW * sf * 0.10f), Math.round(foy + 8 * sf),
                Math.round(fox + Golem3D.FW * sf * 0.90f), Math.round(foy + (Golem3D.FH - 4) * sf), 1, 0xCCFFAA00);
    }

    /** True while the chat should stay hidden so the reward is not given away early. */
    public static boolean chatHidden() {
        return hidden(JawbusConfig.get().hideChat);
    }

    /** True while the rest of the HUD stays hidden, so a reveal owns the whole screen. */
    public static boolean hudHidden() {
        return hidden(true);
    }

    private static boolean hidden(boolean gate) {
        JawbusConfig c = JawbusConfig.get();
        boolean want = gate && c.enabled
                && (current != null || !QUEUE.isEmpty() || System.nanoTime() < gapUntilNs || KillTracker.spoilerWindowOpen());
        if (!want) {
            hiddenSinceNs = 0;
            return false;
        }
        long now = System.nanoTime();
        if (hiddenSinceNs == 0) hiddenSinceNs = now;
        return now - hiddenSinceNs < 120_000_000_000L;   // safety net: never hide the HUD for more than 2 minutes
    }

    // ---- explosion particles (sparks and smoke; the debris itself is 3D, drawn by Golem3D) ----

    private static List<Particle> spawn(Playback p, int cell, int ox, int oy, int h) {
        Random r = new Random(p.seed);
        List<Particle> out = new ArrayList<>();
        int tier = p.tier;
        boolean vial = p.reward.maxIntensity();
        float k = vial ? 1.4f : 1f;

        if (p.fast) k *= 0.5f;
        int sparks = Math.round((tier == 1 ? 20 : tier == 2 ? 60 : 110) * k);
        float sx0 = ox;
        float sy0 = oy;
        for (int n = 0; n < sparks; n++) {
            double ang = r.nextDouble() * Math.PI * 2;
            float sp = h * (tier == 1 ? 0.10f + 0.25f * r.nextFloat() : 0.25f + 0.90f * r.nextFloat());
            float vx = (float) Math.cos(ang) * sp;
            float vy = (float) Math.sin(ang) * sp - (tier == 1 ? h * 0.18f : 0f);
            int rgb = r.nextBoolean() ? 0xFF8A1A : 0xFFD040;
            out.add(new Particle(1, sx0, sy0, vx, vy, h * 0.9f, 0.5f + r.nextFloat() * 0.9f, rgb,
                    Math.max(1f, cell * (0.4f + 0.5f * r.nextFloat())), 0f));
        }

        int smoke = Math.round((tier == 1 ? 4 : tier == 2 ? 16 : 26) * k);
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

    private static void drawIcon(GuiGraphicsExtractor g, ItemStack stack, int cx, int cy, float scale, float xs) {
        var m = g.pose();
        m.pushMatrix();
        m.translate(cx, cy);
        m.scale(scale * xs, scale);
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
