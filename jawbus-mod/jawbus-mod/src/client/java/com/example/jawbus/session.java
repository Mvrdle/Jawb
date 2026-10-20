package com.example.jawbus;

import java.util.Locale;
import java.util.Map;

/** In-memory view of this play session. Nothing is saved; everything is derived from Stats by difference. */
final class Session {
    private Session() {
    }

    private static long startMs = System.currentTimeMillis();
    private static int kills0, drops0, shares0;

    static void start() {
        startMs = System.currentTimeMillis();
        Stats s = Stats.get();
        kills0 = s.kills;
        drops0 = sum(s.drops);
        shares0 = s.lootShares;
    }

    private static int sum(Map<String, Integer> m) {
        int n = 0;
        for (int v : m.values()) n += v;
        return n;
    }

    static int kills() {
        return Math.max(0, Stats.get().kills - kills0);
    }

    static int drops() {
        return Math.max(0, sum(Stats.get().drops) - drops0);
    }

    static int lootShares() {
        return Math.max(0, Stats.get().lootShares - shares0);
    }

    static long elapsedMs() {
        return System.currentTimeMillis() - startMs;
    }

    static String elapsed() {
        long s = elapsedMs() / 1000;
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    static double perHour(int n) {
        double h = elapsedMs() / 3_600_000.0;
        return h < 0.02 ? 0 : n / h;
    }

    /** Kills since the last drop worth calling a drop - ignores the guaranteed shard and magmafish. */
    static int dry() {
        int best = Integer.MAX_VALUE;
        for (Reward r : Reward.values()) {
            if (r.priority < 3) continue;
            best = Math.min(best, Stats.get().killsSince(r));
        }
        return best == Integer.MAX_VALUE ? 0 : best;
    }
}