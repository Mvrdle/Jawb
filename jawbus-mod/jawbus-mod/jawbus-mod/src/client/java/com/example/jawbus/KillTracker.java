package com.example.jawbus;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides when an animation may play, and for which reward. It NEVER picks a reward itself.
 *
 * 1. A kill is confirmed when a "Lord Jawbus" nameplate (armor stand) that was close to you disappears.
 * 2. For the next ~12 s it looks for a known reward: a drop line in chat ("RARE DROP! ..."), or a known item
 *    that newly appears in your inventory. Items are recognised by their name.
 * 3. If one is found, the best one is sent to Cinematic. If none is found, nothing plays.
 * Loot shares are handled separately (chat line about Lord Jawbus + a known item name) and respect the toggle.
 */
final class KillTracker {
    private KillTracker() {
    }

    private static final int WINDOW_TICKS = 240;   // 12 s to find a reward after a kill
    private static final int SETTLE_TICKS = 40;    // after the first find, wait 2 s for a better one
    private static final int DEDUPE_TICKS = 200;   // same reward within 10 s counts as the same event
    private static final double MAX_DIST_SQ = 40.0 * 40.0;

    private static final class Tracked {
        double x, y, z;
        long seen;
    }

    private static final class Window {
        final long open;
        long firstFound = -1;
        final EnumSet<Reward> found = EnumSet.noneOf(Reward.class);
        final int[] base = new int[Reward.values().length];

        Window(long open) {
            this.open = open;
        }
    }

    private static final Map<Integer, Tracked> TRACKED = new HashMap<>();
    private static final Map<Reward, Long> RECENT = new EnumMap<>(Reward.class);
    private static Window window;
    private static long tick;
    private static long lastKillTick = -100000;
    private static double lastKx, lastKy, lastKz, lastPx, lastPz;
    private static boolean hadPos;

    static void reset() {
        TRACKED.clear();
        RECENT.clear();
        window = null;
        hadPos = false;
    }

    private static double sq(double v) {
        return v * v;
    }

    static void debug(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (JawbusConfig.get().debug && mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("§6[Jawbus] §7" + msg));
        }
    }

    static void tick(Minecraft mc) {
        tick++;
        if (mc.level == null || mc.player == null || !JawbusConfig.get().enabled) {
            reset();
            return;
        }
        // a teleport (island change etc.) makes everything vanish at once: forget what we were tracking
        if (hadPos && sq(mc.player.getX() - lastPx) + sq(mc.player.getZ() - lastPz) > 25 * 25) TRACKED.clear();
        lastPx = mc.player.getX();
        lastPz = mc.player.getZ();
        hadPos = true;

        if (tick % 4 == 0) scan(mc);
        if (window != null) pollWindow(mc);
    }

    /** Remember every Lord Jawbus nameplate near us and where it was last seen. */
    private static void scan(Minecraft mc) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getType() != EntityType.ARMOR_STAND) continue;
            Component name = e.getCustomName();
            if (name == null || !name.getString().toLowerCase(Locale.ROOT).contains("lord jawbus")) continue;
            Tracked t = TRACKED.computeIfAbsent(e.getId(), k -> new Tracked());
            t.x = e.getX();
            t.y = e.getY();
            t.z = e.getZ();
            t.seen = tick;
        }
    }

    /** Called when any entity is about to unload. */
    static void onUnload(Entity e) {
        Tracked t = TRACKED.remove(e.getId());
        Minecraft mc = Minecraft.getInstance();
        if (t == null || mc.player == null || !JawbusConfig.get().enabled) return;
        if (tick - t.seen > 12) return;                                   // stale: not a fresh disappearance
        if (mc.player.distanceToSqr(t.x, t.y, t.z) > MAX_DIST_SQ) return; // too far: probably walked out of range
        // the boss usually has two stands (name + health): count one kill
        if (tick - lastKillTick < 100 && sq(t.x - lastKx) + sq(t.y - lastKy) + sq(t.z - lastKz) < 64) return;
        lastKillTick = tick;
        lastKx = t.x;
        lastKy = t.y;
        lastKz = t.z;
        if (window != null) finish(mc); // a second kill: settle the first one now
        window = new Window(tick);
        count(mc, window.base);
        debug("Lord Jawbus died nearby. Looking for the reward...");
    }

    /** Chat handler: loot shares, and drop lines while a kill window is open. */
    static void onChat(String raw) {
        JawbusConfig cfg = JawbusConfig.get();
        if (!cfg.enabled) return;
        String line = ChatRules.clean(raw);

        if (ChatRules.isLootShare(line)) {
            if (!cfg.lootShare) return;
            List<Reward> rs = Reward.findAll(line);
            Reward best = Reward.best(rs);
            if (best == null) {
                debug("Loot share seen but no known reward in it.");
                return;
            }
            Long last = RECENT.get(best);
            if (last != null && tick - last < DEDUPE_TICKS) return;
            RECENT.put(best, tick);
            List<Reward> others = new ArrayList<>(rs);
            others.remove(best);
            Cinematic.enqueue(best, others, true, false);
            debug("Loot share: " + best.displayName + " -> tier " + best.tier);
            return;
        }
        if (window != null && ChatRules.isDropLine(line)) window.found.addAll(Reward.findAll(line));
    }

    private static void pollWindow(Minecraft mc) {
        Window w = window;
        if (tick % 5 == 0) {
            int[] now = new int[Reward.values().length];
            count(mc, now);
            for (Reward r : Reward.values()) {
                if (now[r.ordinal()] > w.base[r.ordinal()]) {
                    w.found.add(r);
                    w.base[r.ordinal()] = now[r.ordinal()];
                }
            }
        }
        if (w.firstFound < 0 && !w.found.isEmpty()) w.firstFound = tick;
        boolean settled = w.firstFound >= 0 && tick - w.firstFound >= SETTLE_TICKS;
        if (settled || tick - w.open >= WINDOW_TICKS) finish(mc);
    }

    private static void finish(Minecraft mc) {
        Window w = window;
        window = null;
        Reward best = Reward.best(w.found);
        if (best == null) {
            debug("No known reward was identified for that kill, so nothing was played.");
            return;
        }
        Long last = RECENT.get(best);
        if (last != null && tick - last < DEDUPE_TICKS) return; // already shown (e.g. via loot share)
        for (Reward r : w.found) RECENT.put(r, tick);
        List<Reward> others = new ArrayList<>(w.found);
        others.remove(best);
        Cinematic.enqueue(best, others, false, false);
        debug("Reward: " + best.displayName + " -> tier " + best.tier);
    }

    /** How many of each known reward are in the player's inventory right now (by item name). */
    private static void count(Minecraft mc, int[] out) {
        Arrays.fill(out, 0);
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            Reward r = Reward.match(s.getHoverName().getString());
            if (r != null) out[r.ordinal()] += s.getCount();
        }
    }
}
