package com.example.jawbus;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minecraft side of kill detection. It NEVER picks a reward itself.
 *
 * 1. A kill is a "Lord Jawbus" name plate (armor stand) that was close to you and disappears. Every kill is its own
 *    event (see KillLedger), so double hooks and overlapping kills are tracked separately.
 * 2. Rewards come from drop lines in chat and from known items newly appearing in your inventory; KillLedger
 *    decides which kill each belongs to (and says honestly when that is a guess).
 * 3. Each finished kill becomes one or more reveals, queued in order. Loot-share lines are separate events.
 */
final class KillTracker {
    private KillTracker() {
    }

    private static final double MAX_DIST_SQ = 40.0 * 40.0;

    private static final class Tracked {
        double x, y, z;
        long seen;
    }

    private static final Map<Integer, Tracked> TRACKED = new HashMap<>();
    private static final int[] INV_BASE = new int[Reward.values().length];
    private static boolean invReady;
    private static long tick;
    private static double lastPx, lastPz;
    private static boolean hadPos;
    private static String lastLootLine = "";
    private static long lastLootTick = -100;

    private static final KillLedger LEDGER = new KillLedger(new KillLedger.Sink() {
        @Override
        public void reveal(KillLedger.Result r) {
            revealKill(r);
        }

        @Override
        public void note(String msg) {
            debug(msg);
        }
    });

    // ---- simulation (debug): events are fed through the same pipeline, but marked as simulated ----
    private record SimAction(long at, Runnable run) {
    }

    private static final List<SimAction> SIM = new ArrayList<>();

    static boolean spoilerWindowOpen() {
        return LEDGER.spoilerOpen(tick);
    }

    static void reset() {
        TRACKED.clear();
        SIM.clear();
        LEDGER.reset();
        invReady = false;
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

        if (!SIM.isEmpty()) runSim();
        if (tick % 4 == 0) scan(mc);
        if (tick % 5 == 0) pollInventory(mc);
        try {
            LEDGER.tick(tick);
        } catch (RuntimeException ex) {
            debug("Kill tracking error (" + ex + "), state cleared.");
            LEDGER.reset();
        }
    }

    /** Remember every Lord Jawbus nameplate near us and where it was last seen. */
    private static void scan(Minecraft mc) {
        for (Entity e : mc.level.entitiesForRendering()) {
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
        int n = LEDGER.onKill(tick, t.x, t.z, false);                      // -1 = another plate of a kill already open
        if (n > 0) {
            Stats.recordKill();
            debug("Lord Jawbus #" + n + " died nearby. Looking for its reward...");
        }
    }

    /** Chat handler: loot shares, and drop lines. Always processed, whether or not the chat is visible. */
    static void onChat(String raw) {
        handleChat(raw, false);
    }

    private static void handleChat(String raw, boolean sim) {
        JawbusConfig cfg = JawbusConfig.get();
        if (!cfg.enabled) return;
        String line = ChatRules.clean(raw);

        if (ChatRules.isLootShare(line)) {
            if (!cfg.lootShare) return;
            // the very same line twice in the same tick is a double delivery; the same item at another moment
            // (a second Jawbus) is a genuine second reward
            if (!sim && line.equals(lastLootLine) && tick - lastLootTick == 0) return;
            if (!sim) {
                lastLootLine = line;
                lastLootTick = tick;
            }
            List<Reward> rs = Reward.findAll(line);
            Reward best = Reward.best(rs);
            if (best == null) {
                debug("Loot share seen but no known reward in it.");
                return;
            }
            List<Reward> others = new ArrayList<>(rs);
            others.remove(best);
            if (!sim) for (Reward x : rs) Stats.recordDrop(x, true);
            Cinematic.enqueue(best, others, true, sim, sim ? "SIMULATED LOOT SHARE" : null);
            debug("Loot share: " + best.displayName + " -> tier " + best.tier);
            return;
        }
        if (ChatRules.isDropLine(line)) {
            List<Reward> rs = Reward.findAll(line);
            if (!rs.isEmpty()) LEDGER.onChat(tick, rs, sim);
        }
    }

    /** Known rewards newly appearing in the inventory count as evidence while a kill is open. */
    private static void pollInventory(Minecraft mc) {
        int[] now = new int[Reward.values().length];
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            Reward r = Reward.match(s.getHoverName().getString());
            if (r != null) now[r.ordinal()] += s.getCount();
        }
        if (invReady) {
            for (Reward r : Reward.values()) {
                int d = now[r.ordinal()] - INV_BASE[r.ordinal()];
                if (d > 0) LEDGER.onInventory(tick, r, d, false);
            }
        }
        System.arraycopy(now, 0, INV_BASE, 0, now.length);
        invReady = true;
    }

    /**
     * Reveal policy for one kill that found several rewards: the best one gets the full animation, and every other
     * reward is listed under "Also:" on that reveal. (Magma Lord Fragment and Silver Magmafish are guaranteed drops,
     * so animating each reward separately would double every reveal.) Nothing is discarded.
     */
    private static void revealKill(KillLedger.Result r) {
        Reward best = Reward.best(r.rewards());
        List<Reward> others = new ArrayList<>(r.rewards());
        others.remove(best);
        others.sort(Comparator.comparingInt((Reward x) -> x.priority).reversed());
        String tag = r.concurrent() ? "KILL " + r.killNumber() + (r.ambiguous() ? "  (reward match is a best guess)" : "") : null;
        if (r.sim()) tag = "SIMULATED" + (tag == null ? "" : "  " + tag);
        if (!r.sim()) for (Reward x : r.rewards()) Stats.recordDrop(x, false);
        Cinematic.enqueue(best, others, false, r.sim(), tag);
        StringBuilder sb = new StringBuilder("Kill " + r.killNumber() + ":");
        for (Reward x : r.rewards()) sb.append(' ').append(x.displayName).append(" (tier ").append(x.tier).append(')');
        if (r.ambiguous()) sb.append(" [several kills were open, so which kill got it is a best guess]");
        debug(sb.toString());
    }

    // ------------------------------------------------------------------
    // Debug simulation: /jawbusRoll simulate <1-6>
    // ------------------------------------------------------------------

    static final String[] SIM_NAMES = {
            "two kills, two different rewards",
            "two kills, same reward twice (+ one repeated line)",
            "one kill, three drops",
            "one kill + loot shares",
            "three kills at once (two rewards, one empty)",
            "drop line arrives before its kill",
    };

    private static void at(int ticksFromNow, Runnable r) {
        SIM.add(new SimAction(tick + ticksFromNow, r));
    }

    private static void simKill(double x) {
        int n = LEDGER.onKill(tick, x, 0, true);
        if (n > 0) debug("[sim] Lord Jawbus #" + n + " died.");
    }

    private static void simChat(String s) {
        handleChat(s, true);
    }

    static void simulate(int scenario) {
        SIM.clear();
        switch (scenario) {
            case 1 -> {
                at(0, () -> simKill(0));
                at(4, () -> simKill(6));
                at(10, () -> simChat("RARE DROP! Magma Lord Fragment"));
                at(12, () -> simChat("CRAZY RARE DROP! Carmine Dye"));
            }
            case 2 -> {
                at(0, () -> simKill(0));
                at(2, () -> simKill(6));
                at(10, () -> simChat("RARE DROP! Magma Lord Fragment"));
                at(10, () -> simChat("RARE DROP! Magma Lord Fragment"));
                at(11, () -> simChat("RARE DROP! Magma Lord Fragment"));
            }
            case 3 -> {
                at(0, () -> simKill(0));
                at(8, () -> simChat("RARE DROP! Magma Lord Fragment"));
                at(9, () -> simChat("RARE DROP! Silver Magmafish"));
                at(14, () -> simChat("VERY RARE DROP! Bobbin' Scriptures"));
            }
            case 4 -> {
                at(0, () -> simKill(0));
                at(8, () -> simChat("RARE DROP! Silver Magmafish"));
                at(10, () -> simChat("LOOT SHARE You received Magma Lord Fragment for assisting Lord Jawbus!"));
                at(30, () -> simChat("LOOT SHARE You received Magma Lord Fragment for assisting Lord Jawbus!"));
            }
            case 5 -> {
                at(0, () -> simKill(0));
                at(3, () -> simKill(5));
                at(6, () -> simKill(10));
                at(12, () -> simChat("CRAZY RARE DROP! Carmine Dye"));
                at(13, () -> simChat("RARE DROP! Radioactive Vial"));
            }
            default -> {
                at(0, () -> simChat("RARE DROP! Radioactive Vial"));
                at(6, () -> simKill(0));
            }
        }
        debug("Simulating: " + SIM_NAMES[Math.max(0, Math.min(5, scenario - 1))] + ". Everything is labelled SIMULATED.");
    }

    private static void runSim() {
        for (int i = 0; i < SIM.size(); i++) {
            SimAction a = SIM.get(i);
            if (tick >= a.at()) {
                SIM.remove(i--);
                try {
                    a.run().run();
                } catch (RuntimeException ex) {
                    debug("Simulation step failed: " + ex);
                }
            }
        }
    }
}
