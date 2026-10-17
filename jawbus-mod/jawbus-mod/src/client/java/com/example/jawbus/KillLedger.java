package com.example.jawbus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The bookkeeping for "which kill got which reward". Pure Java (no Minecraft classes) so it can be tested on its own.
 * Time is counted in game ticks (20 per second).
 *
 * What it can and cannot know: Hypixel's drop lines ("RARE DROP! Magma Lord Fragment") do not say WHICH Lord Jawbus
 * they came from, and neither does an item appearing in your inventory. So with ONE kill open, a reward is certain.
 * With several kills open at once, a reward is handed to the oldest open kill that does not have that reward yet
 * (preferring a kill that has nothing yet) and that result is flagged "ambiguous". Nothing is ever dropped or
 * overwritten: every distinct drop becomes one reward for exactly one kill.
 *
 * Duplicates: a kill can hold each reward at most once, so the same line repeated for ONE kill is ignored, while
 * the same item dropping from TWO kills is accepted twice. Chat and inventory describe the same physical drop, so
 * the number of drops of one item is max(chat lines, inventory increases), not their sum.
 */
public final class KillLedger {
    public static final int WINDOW_TICKS = 240;      // 12 s to find a reward after a kill
    public static final int SETTLE_TICKS = 40;       // after a kill's first reward, wait 2 s for more
    public static final int ORPHAN_TICKS = 60;       // a drop line seen up to 3 s BEFORE its kill is still claimed
    public static final int SPOILER_TICKS = 120;     // chat stays hidden this long if nothing is found
    public static final int IDLE_RESET_TICKS = 400;  // counters reset after 20 s of nothing
    public static final int STAND_MERGE_TICKS = 6;   // one boss has up to 3 name plates that vanish together
    public static final double STAND_MERGE_DIST_SQ = 1.5 * 1.5;
    public static final int MAX_STANDS = 3;

    /** One finished kill and everything that was found for it. */
    public record Result(int killNumber, List<Reward> rewards, boolean ambiguous, boolean concurrent, boolean sim) {
    }

    public interface Sink {
        void reveal(Result r);

        void note(String msg);
    }

    private static final class Kill {
        final int number;
        final long open;
        final double x, z;
        final boolean sim;
        int stands = 1;
        long firstFound = -1;
        boolean ambiguous;
        boolean concurrent;
        final LinkedHashSet<Reward> rewards = new LinkedHashSet<>();

        Kill(int number, long open, double x, double z, boolean sim) {
            this.number = number;
            this.open = open;
            this.x = x;
            this.z = z;
            this.sim = sim;
        }
    }

    private record Orphan(Reward reward, long tick, boolean sim) {
    }

    private static final int N = Reward.values().length;

    private final Sink sink;
    private final List<Kill> open = new ArrayList<>();
    private final List<Orphan> orphans = new ArrayList<>();
    private final int[][] chat = new int[2][N];
    private final int[][] inv = new int[2][N];
    private final int[][] assigned = new int[2][N];
    private int killCounter;
    private long lastActivity;

    public KillLedger(Sink sink) {
        this.sink = sink;
    }

    public void reset() {
        open.clear();
        orphans.clear();
        for (int s = 0; s < 2; s++) {
            java.util.Arrays.fill(chat[s], 0);
            java.util.Arrays.fill(inv[s], 0);
            java.util.Arrays.fill(assigned[s], 0);
        }
        killCounter = 0;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static int s(boolean sim) {
        return sim ? 1 : 0;
    }

    /** A boss name plate vanished at (x, z). Returns the kill number, or -1 if it is another plate of a kill already open. */
    public int onKill(long tick, double x, double z, boolean sim) {
        lastActivity = tick;
        for (Kill k : open) {
            if (k.sim == sim && tick - k.open <= STAND_MERGE_TICKS && k.stands < MAX_STANDS
                    && sq(x - k.x) + sq(z - k.z) <= STAND_MERGE_DIST_SQ) {
                k.stands++;
                return -1;
            }
        }
        Kill k = new Kill(++killCounter, tick, x, z, sim);
        for (Kill o : open) {
            if (o.sim == sim) {
                o.concurrent = true;
                k.concurrent = true;
            }
        }
        open.add(k);
        // drop lines that arrived just before this kill was noticed belong to it
        for (int i = 0; i < orphans.size(); i++) {
            Orphan o = orphans.get(i);
            if (o.sim == sim && tick - o.tick <= ORPHAN_TICKS) {
                give(k, o.reward, tick);
                orphans.remove(i--);
            }
        }
        return k.number;
    }

    /** Drop lines seen in chat (each reward named in one line counts once). */
    public void onChat(long tick, Collection<Reward> rewards, boolean sim) {
        lastActivity = tick;
        for (Reward r : rewards) {
            chat[s(sim)][r.ordinal()]++;
            reconcile(tick, r, sim);
        }
    }

    /** A known reward newly appeared in the inventory (only counts while a kill is open). */
    public void onInventory(long tick, Reward r, int delta, boolean sim) {
        if (delta <= 0 || !hasOpen(sim)) return;
        lastActivity = tick;
        inv[s(sim)][r.ordinal()] += delta;
        reconcile(tick, r, sim);
    }

    private boolean hasOpen(boolean sim) {
        for (Kill k : open) if (k.sim == sim) return true;
        return false;
    }

    private void reconcile(long tick, Reward r, boolean sim) {
        int o = r.ordinal();
        int target = Math.max(chat[s(sim)][o], inv[s(sim)][o]);
        while (assigned[s(sim)][o] < target) {
            assigned[s(sim)][o]++;
            place(tick, r, sim);
        }
    }

    private void place(long tick, Reward r, boolean sim) {
        List<Kill> same = new ArrayList<>();
        for (Kill k : open) if (k.sim == sim) same.add(k);
        if (same.isEmpty()) {
            orphans.add(new Orphan(r, tick, sim));   // wait up to 3 s for its kill to be noticed
            return;
        }
        Kill pick = null;
        for (Kill k : same) {                          // oldest kill with nothing yet and without this reward
            if (k.rewards.isEmpty() && !k.rewards.contains(r)) {
                pick = k;
                break;
            }
        }
        if (pick == null) {
            for (Kill k : same) {                      // else oldest kill that does not have this reward yet
                if (!k.rewards.contains(r)) {
                    pick = k;
                    break;
                }
            }
        }
        if (pick == null) {
            sink.note("Repeated " + r.displayName + " line ignored (every open kill already has one).");
            return;
        }
        if (same.size() > 1) pick.ambiguous = true;
        give(pick, r, tick);
    }

    private void give(Kill k, Reward r, long tick) {
        k.rewards.add(r);
        if (k.firstFound < 0) k.firstFound = tick;
    }

    /** Call once per game tick: finishes kills whose search is over and expires stale drop lines. */
    public void tick(long tick) {
        for (int i = 0; i < orphans.size(); i++) {
            Orphan o = orphans.get(i);
            if (tick - o.tick > ORPHAN_TICKS) {
                orphans.remove(i--);
                sink.note("A " + o.reward.displayName + " drop line had no kill near it, so nothing was played.");
            }
        }
        for (int i = 0; i < open.size(); i++) {
            Kill k = open.get(i);
            boolean settled = k.firstFound >= 0 && tick - k.firstFound >= SETTLE_TICKS;
            if (!settled && tick - k.open < WINDOW_TICKS) continue;
            open.remove(i--);
            if (k.rewards.isEmpty()) {
                sink.note("Kill " + k.number + ": no known reward was identified, so nothing was played.");
                continue;
            }
            sink.reveal(new Result(k.number, new ArrayList<>(k.rewards), k.ambiguous, k.concurrent, k.sim));
        }
        if (open.isEmpty() && orphans.isEmpty() && tick - lastActivity >= IDLE_RESET_TICKS) reset();
    }

    /** True while the chat should stay hidden: a fresh kill, or a kill that already has a reward. */
    public boolean spoilerOpen(long tick) {
        if (!orphans.isEmpty()) return true;
        for (Kill k : open) if (k.firstFound >= 0 || tick - k.open < SPOILER_TICKS) return true;
        return false;
    }

    public int openKills() {
        return open.size();
    }
}
