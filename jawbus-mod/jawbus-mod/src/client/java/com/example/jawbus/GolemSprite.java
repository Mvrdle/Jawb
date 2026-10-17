package com.example.jawbus;

import java.util.Random;

/**
 * Pixel-grid Iron Golem (front view) plus "crack growth" maps. Pure Java, no Minecraft classes.
 * Cells are drawn as squares by Cinematic; each cell can also become a flying debris chunk.
 */
public final class GolemSprite {
    public static final int W = 26;
    public static final int H = 38;
    public static final int HEAD = 0, TORSO = 1, ARM_L = 2, ARM_R = 3, LEG_L = 4, LEG_R = 5;

    public final boolean[] filled = new boolean[W * H];
    public final int[] region = new int[W * H];
    public final int[] color = new int[W * H];
    public final boolean[] eye = new boolean[W * H];
    public int count;

    private static GolemSprite instance;
    private static final float[][] CRACKS = new float[4][];

    public static synchronized GolemSprite get() {
        if (instance == null) instance = new GolemSprite();
        return instance;
    }

    private GolemSprite() {
        build();
    }

    boolean at(int x, int y) {
        return x >= 0 && y >= 0 && x < W && y < H && filled[y * W + x];
    }

    private void rect(int x0, int y0, int x1, int y1, int reg) {
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                filled[y * W + x] = true;
                region[y * W + x] = reg;
            }
    }

    private static int hash(int x, int y) {
        int h = x * 73856093 ^ y * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        return h ^ (h >>> 15);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int rgb(int r, int g, int b) {
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private void build() {
        rect(9, 0, 16, 8, HEAD);   // head
        rect(12, 5, 13, 9, HEAD);  // long nose
        rect(4, 9, 21, 17, TORSO); // shoulders + chest
        rect(6, 18, 19, 21, TORSO); // waist
        rect(0, 9, 3, 33, ARM_L);
        rect(22, 9, 25, 33, ARM_R);
        rect(6, 22, 11, 37, LEG_L);
        rect(14, 22, 19, 37, LEG_R);

        int[][] vines = {{5, 10}, {5, 11}, {6, 11}, {20, 14}, {20, 15}, {19, 15}, {1, 20}, {1, 21}, {2, 21},
                {23, 26}, {24, 26}, {8, 25}, {8, 26}, {16, 30}, {10, 16}, {15, 12}};

        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                if (!filled[i]) continue;
                count++;
                int base = switch (region[i]) {
                    case HEAD -> 0xA9A296;
                    case TORSO -> 0x9C968D;
                    case ARM_L, ARM_R -> 0x948E85;
                    default -> 0x86807A;
                };
                if (region[i] == HEAD && x >= 12 && x <= 13 && y >= 5) base = 0xB9B1A2;
                int r = (base >> 16) & 255, g = (base >> 8) & 255, b = base & 255;
                int n = ((hash(x, y) >>> 8) & 31) - 16;
                r += n * 6 / 10;
                g += n * 6 / 10;
                b += n * 6 / 10;
                if (!at(x, y - 1)) {
                    r += 14;
                    g += 14;
                    b += 14;
                }
                if (!at(x - 1, y) || !at(x + 1, y) || !at(x, y + 1)) {
                    r = r * 74 / 100;
                    g = g * 74 / 100;
                    b = b * 74 / 100;
                }
                color[i] = rgb(r, g, b);
            }
        }
        for (int x = 10; x <= 15; x++) color[2 * W + x] = 0x4E4944; // brow
        for (int x : new int[]{10, 11, 14, 15}) {                    // glowing eyes
            color[3 * W + x] = 0xE0701A;
            eye[3 * W + x] = true;
        }
        for (int[] v : vines) {
            int i = v[1] * W + v[0];
            if (filled[i]) color[i] = 0x4F7A32;
        }
    }

    // ---- cracks ----

    private static void mark(GolemSprite s, float[] o, int x, int y, float v) {
        if (!s.at(x, y)) return;
        int i = y * W + x;
        if (o[i] < 0 || v < o[i]) o[i] = v;
    }

    private static int cl(int v) {
        return Math.max(-1, Math.min(1, v));
    }

    private static void walk(GolemSprite s, float[] o, Random r, int x, int y, int dx, int dy, int len, float delay,
                             boolean branch) {
        for (int i = 0; i < len; i++) {
            float v = delay + (1f - delay) * (i / (float) Math.max(1, len - 1));
            mark(s, o, x, y, v);
            if (r.nextFloat() < 0.35f) {
                dx = cl(dx + r.nextInt(3) - 1);
                dy = cl(dy + r.nextInt(3) - 1);
                if (dx == 0 && dy == 0) dx = r.nextBoolean() ? 1 : -1;
            }
            if (dx != 0 && dy != 0) mark(s, o, x + dx, y, v + 0.01f); // keep the crack connected
            x += dx;
            y += dy;
            if (branch && i == len / 2) walk(s, o, r, x, y, -dy, dx, Math.max(2, len / 2), v, false);
        }
    }

    /**
     * Crack growth map for a tier: for each cell, 0..1 = when (along the growth timeline) it cracks,
     * or -1 for never. Tier 1 is a single crack across the chest, tier 2 several over the upper body,
     * tier 3 spreads over the whole figure.
     */
    public static synchronized float[] cracks(int tier) {
        tier = Math.max(1, Math.min(3, tier));
        if (CRACKS[tier] != null) return CRACKS[tier];
        GolemSprite s = get();
        float[] o = new float[W * H];
        java.util.Arrays.fill(o, -1f);
        Random r = new Random(1000L + tier * 77L);

        if (tier == 1) {
            int[] zig = {0, 0, 1, 1, 0, -1};
            int prevY = 13;
            for (int x = 5; x <= 20; x++) {
                int y = 13 + zig[x % 6];
                float v = Math.abs(x - 12.5f) / 8f;
                mark(s, o, x, y, v);
                for (int yy = Math.min(y, prevY); yy <= Math.max(y, prevY); yy++) mark(s, o, x, yy, v);
                prevY = y;
            }
        } else {
            int[][] seeds2 = {{12, 13, -1, 0, 12}, {12, 13, 1, 0, 12}, {12, 13, 0, -1, 7}, {6, 10, -1, 1, 9},
                    {19, 10, 1, 1, 9}, {9, 12, 0, 1, 6}, {16, 12, 0, 1, 6}, {2, 11, 0, 1, 9}, {23, 11, 0, 1, 9}};
            for (int k = 0; k < seeds2.length; k++) {
                int[] d = seeds2[k];
                float delay = k < 3 ? 0f : 0.15f + r.nextFloat() * 0.30f;
                int len = tier == 3 ? d[4] * 13 / 10 : d[4];
                walk(s, o, r, d[0], d[1], d[2], d[3], len, delay, true);
            }
            if (tier == 3) {
                int[][] seeds3 = {{12, 4, -1, 1, 5}, {13, 4, 1, 1, 5}, {12, 15, 0, 1, 8}, {8, 24, 0, 1, 12},
                        {17, 24, 0, 1, 12}, {9, 30, 1, 1, 6}, {16, 30, -1, 1, 6}, {1, 22, 0, 1, 10},
                        {24, 22, 0, 1, 10}, {2, 28, 1, 1, 5}, {23, 28, -1, 1, 5}, {7, 15, -1, 1, 6},
                        {18, 15, 1, 1, 6}, {12, 19, 0, 1, 5}};
                for (int[] d : seeds3) walk(s, o, r, d[0], d[1], d[2], d[3], d[4], r.nextFloat() * 0.5f, true);
            }
        }
        float max = 0f;
        for (float v : o) max = Math.max(max, v);
        if (max > 0f) for (int i = 0; i < o.length; i++) if (o[i] >= 0) o[i] /= max;
        CRACKS[tier] = o;
        return o;
    }
}
