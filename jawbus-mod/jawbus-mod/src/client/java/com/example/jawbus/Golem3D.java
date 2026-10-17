package com.example.jawbus;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A real 3D Iron-Golem-style Lord Jawbus, drawn by a small software renderer (no Minecraft classes at all).
 *
 *  - Model: 32 chunks (boxes) grouped under 9 joints (root, torso, head, 2 arms, 2 upper legs, 2 lower legs).
 *    Every chunk has 6 textured faces, so the golem has depth: a front, sides, a back, a top.
 *  - Textures: generated from 3D noise (dark stone + iron plates), so they carry over box edges seamlessly.
 *  - Cracks: a 3D crack field baked per tier. Each crack texel has an "arrival time"; cracks appear and spread
 *    outward from the chest as the animation advances. They are part of the surface, not drawn on top.
 *  - Destruction: chunks detach with their own velocity and spin; the cut faces show a glowing core.
 *  - Output: a z-buffered ARGB framebuffer ({@link #pix}) that the HUD paints with filled rectangles.
 *
 * Everything is a pure function of time, so frames can be drawn in any order (also used by the offline test).
 */
final class Golem3D {
    static final int FW = 192, FH = 144;
    private static final double TEXEL = 0.5;

    // joints
    static final int ROOT = 0, TORSO = 1, HEAD = 2, ARM_A = 3, ARM_B = 4, UL_A = 5, LL_A = 6, UL_B = 7, LL_B = 8, NJ = 9;
    private static final int[] PARENT = {-1, ROOT, TORSO, TORSO, TORSO, ROOT, UL_A, ROOT, UL_B};
    private static final double[][] PIVOT = {
            {0, 0, 4}, {0, 20, 0}, {0, 33, 0}, {-11, 32, 0}, {11, 32, 0},
            {-4.5, 16, 0}, {-4.5, 8, 0}, {4.5, 16, 0}, {4.5, 8, 0}};

    // ------------------------------------------------------------------
    // Output buffers
    // ------------------------------------------------------------------

    final int[] pix = new int[FW * FH];      // ARGB, alpha 0 = empty
    private final float[] zbuf = new float[FW * FH];
    private final float[] emis = new float[FW * FH];
    private final float[] blurA = new float[FW * FH];
    private final float[] blurB = new float[FW * FH];

    // ------------------------------------------------------------------
    // Math helpers
    // ------------------------------------------------------------------

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static double ease(double x) {
        x = clamp01(x);
        return x * x * (3 - 2 * x);
    }

    private static double easeOut(double x) {
        double i = 1 - clamp01(x);
        return 1 - i * i * i;
    }

    private static double[] ident() {
        return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static double[] mul(double[] a, double[] b) {
        double[] o = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                o[i * 4 + j] = a[i * 4] * b[j] + a[i * 4 + 1] * b[4 + j] + a[i * 4 + 2] * b[8 + j] + a[i * 4 + 3] * b[12 + j];
            }
        }
        return o;
    }

    private static double[] trans(double x, double y, double z) {
        double[] m = ident();
        m[3] = x;
        m[7] = y;
        m[11] = z;
        return m;
    }

    private static double[] scaleM(double s) {
        double[] m = ident();
        m[0] = s;
        m[5] = s;
        m[10] = s;
        return m;
    }

    private static double[] rotX(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1};
    }

    private static double[] rotY(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1};
    }

    private static double[] rotZ(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static double[] rotXYZ(double x, double y, double z) {
        return mul(rotY(y), mul(rotX(x), rotZ(z)));
    }

    private static void apply(double[] m, double x, double y, double z, double[] out) {
        out[0] = m[0] * x + m[1] * y + m[2] * z + m[3];
        out[1] = m[4] * x + m[5] * y + m[6] * z + m[7];
        out[2] = m[8] * x + m[9] * y + m[10] * z + m[11];
    }

    private static void applyDir(double[] m, double x, double y, double z, double[] out) {
        out[0] = m[0] * x + m[1] * y + m[2] * z;
        out[1] = m[4] * x + m[5] * y + m[6] * z;
        out[2] = m[8] * x + m[9] * y + m[10] * z;
    }

    // ---- noise ----

    private static double h3(int x, int y, int z, int s) {
        int h = x * 374761393 + y * 668265263 + z * 1274126177 + s * 362437;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (double) 0x1000000;
    }

    private static double noise3(double x, double y, double z) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double fx = x - xi, fy = y - yi, fz = z - zi;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        fz = fz * fz * (3 - 2 * fz);
        double c00 = h3(xi, yi, zi, 1) * (1 - fx) + h3(xi + 1, yi, zi, 1) * fx;
        double c10 = h3(xi, yi + 1, zi, 1) * (1 - fx) + h3(xi + 1, yi + 1, zi, 1) * fx;
        double c01 = h3(xi, yi, zi + 1, 1) * (1 - fx) + h3(xi + 1, yi, zi + 1, 1) * fx;
        double c11 = h3(xi, yi + 1, zi + 1, 1) * (1 - fx) + h3(xi + 1, yi + 1, zi + 1, 1) * fx;
        double a = c00 * (1 - fy) + c10 * fy;
        double b = c01 * (1 - fy) + c11 * fy;
        return a * (1 - fz) + b * fz;
    }

    private static double fbm(double x, double y, double z) {
        return 0.55 * noise3(x, y, z) + 0.30 * noise3(x * 2.1 + 5, y * 2.1, z * 2.1) + 0.15 * noise3(x * 4.3, y * 4.3 + 9, z * 4.3);
    }

    private static int mixRgb(int a, int b, double t) {
        t = clamp01(t);
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    private static final class Face {
        final double[] o = new double[3], u = new double[3], v = new double[3], n = new double[3];
        int tw, th;
        int[] base;
        boolean[] eye;
        final float[][] crackT = new float[4][];
        final float[][] haloT = new float[4][];
        final float[][] halo = new float[4][];
        boolean exterior;
    }

    private static final class Chunk {
        int joint;
        double cx, cy, cz;
        int minTier;
        final Face[] faces = new Face[6];
    }

    private static final class Model {
        final List<Chunk> chunks = new ArrayList<>();
        final boolean[] ready = new boolean[4];

        Model() {
            // torso 18x12x11 in a 3x2x2 grid; head 8x10x8 in 2x2x2; arms 4x30x6 in 3; legs in two pieces each
            grid(TORSO, 0, -9, 9, 21, 33, -5.5, 5.5, 3, 2, 2, 2);
            grid(ROOT, 0, -4.5, 4.5, 16, 21, -3, 3, 1, 1, 1, 3);
            grid(HEAD, 1, -4, 4, 33, 43, -3, 5, 2, 2, 2, 2);
            grid(HEAD, 2, -1, 1, 35.5, 39.5, 5, 7, 1, 1, 1, 2);
            grid(ARM_A, 0, -13, -9, 2, 32, -3, 3, 1, 3, 1, 3);
            grid(ARM_B, 0, 9, 13, 2, 32, -3, 3, 1, 3, 1, 3);
            grid(UL_A, 0, -7.5, -1.5, 8, 16, -2.5, 2.5, 1, 1, 1, 3);
            grid(LL_A, 0, -7.5, -1.5, 0, 8, -2.5, 2.5, 1, 1, 1, 3);
            grid(UL_B, 0, 1.5, 7.5, 8, 16, -2.5, 2.5, 1, 1, 1, 3);
            grid(LL_B, 0, 1.5, 7.5, 0, 8, -2.5, 2.5, 1, 1, 1, 3);
            // upper arm chunks fly in tier 2 too
            for (Chunk c : chunks) {
                if ((c.joint == ARM_A || c.joint == ARM_B) && c.cy > 22) c.minTier = 2;
            }
        }

        private void grid(int joint, int region, double x0, double x1, double y0, double y1, double z0, double z1,
                          int nx, int ny, int nz, int minTier) {
            double dx = (x1 - x0) / nx, dy = (y1 - y0) / ny, dz = (z1 - z0) / nz;
            for (int ix = 0; ix < nx; ix++) {
                for (int iy = 0; iy < ny; iy++) {
                    for (int iz = 0; iz < nz; iz++) {
                        double ax = x0 + ix * dx, bx = ax + dx, ay = y0 + iy * dy, by = ay + dy, az = z0 + iz * dz, bz = az + dz;
                        Chunk c = new Chunk();
                        c.joint = joint;
                        c.cx = (ax + bx) / 2;
                        c.cy = (ay + by) / 2;
                        c.cz = (az + bz) / 2;
                        c.minTier = minTier;
                        c.faces[0] = face(2, 1, ax, bx, ay, by, az, bz, iz == nz - 1, region);
                        c.faces[1] = face(2, -1, ax, bx, ay, by, az, bz, iz == 0, region);
                        c.faces[2] = face(0, 1, ax, bx, ay, by, az, bz, ix == nx - 1, region);
                        c.faces[3] = face(0, -1, ax, bx, ay, by, az, bz, ix == 0, region);
                        c.faces[4] = face(1, 1, ax, bx, ay, by, az, bz, iy == ny - 1, region);
                        c.faces[5] = face(1, -1, ax, bx, ay, by, az, bz, iy == 0, region);
                        chunks.add(c);
                    }
                }
            }
        }

        private Face face(int axis, int sign, double x0, double x1, double y0, double y1, double z0, double z1,
                          boolean exterior, int region) {
            Face f = new Face();
            f.exterior = exterior;
            double w = x1 - x0, h = y1 - y0, d = z1 - z0;
            if (axis == 2 && sign > 0) {
                set(f, x0, y1, z1, w, 0, 0, 0, -h, 0, 0, 0, 1);
            } else if (axis == 2) {
                set(f, x1, y1, z0, -w, 0, 0, 0, -h, 0, 0, 0, -1);
            } else if (axis == 0 && sign > 0) {
                set(f, x1, y1, z1, 0, 0, -d, 0, -h, 0, 1, 0, 0);
            } else if (axis == 0) {
                set(f, x0, y1, z0, 0, 0, d, 0, -h, 0, -1, 0, 0);
            } else if (sign > 0) {
                set(f, x0, y1, z0, w, 0, 0, 0, 0, d, 0, 1, 0);
            } else {
                set(f, x0, y0, z1, w, 0, 0, 0, 0, -d, 0, -1, 0);
            }
            double lu = Math.sqrt(f.u[0] * f.u[0] + f.u[1] * f.u[1] + f.u[2] * f.u[2]);
            double lv = Math.sqrt(f.v[0] * f.v[0] + f.v[1] * f.v[1] + f.v[2] * f.v[2]);
            f.tw = Math.max(1, (int) Math.ceil(lu / TEXEL - 1e-6));
            f.th = Math.max(1, (int) Math.ceil(lv / TEXEL - 1e-6));
            f.base = new int[f.tw * f.th];
            f.eye = new boolean[f.tw * f.th];
            for (int j = 0; j < f.th; j++) {
                for (int i = 0; i < f.tw; i++) {
                    double a = (i + 0.5) / f.tw, b = (j + 0.5) / f.th;
                    double px = f.o[0] + f.u[0] * a + f.v[0] * b;
                    double py = f.o[1] + f.u[1] * a + f.v[1] * b;
                    double pz = f.o[2] + f.u[2] * a + f.v[2] * b;
                    int idx = j * f.tw + i;
                    f.base[idx] = stone(px, py, pz, region, i, j);
                    if (region == 1 && axis == 2 && sign > 0 && py > 37.1 && py < 39.0 && Math.abs(px) > 0.9 && Math.abs(px) < 3.3) {
                        f.eye[idx] = true;
                    }
                }
            }
            return f;
        }

        private static void set(Face f, double ox, double oy, double oz, double ux, double uy, double uz,
                                double vx, double vy, double vz, double nx, double ny, double nz) {
            f.o[0] = ox;
            f.o[1] = oy;
            f.o[2] = oz;
            f.u[0] = ux;
            f.u[1] = uy;
            f.u[2] = uz;
            f.v[0] = vx;
            f.v[1] = vy;
            f.v[2] = vz;
            f.n[0] = nx;
            f.n[1] = ny;
            f.n[2] = nz;
        }

        // ---- procedural material: dark basalt + iron plates ----

        private static int stone(double x, double y, double z, int region, int ti, int tj) {
            double n = fbm(x * 0.55, y * 0.55, z * 0.55);
            double fine = h3(ti * 7 + (int) (x * 3), tj * 13 + (int) (y * 3), (int) (z * 3), 5) - 0.5;
            int dark = 0x1E1C1D, light = 0x57524F;
            int c = mixRgb(dark, light, clamp01(n * 1.25 - 0.1 + fine * 0.18));
            double plate = noise3(x * 0.13 + 7, y * 0.13, z * 0.13);
            double brush = noise3(x * 0.04, y * 0.9, z * 0.04);
            double ironAmt = (plate > 0.5 ? 0.55 : 0.0) * (0.6 + 0.4 * brush);
            if (region == 1) ironAmt = 0.65;           // head: mostly iron
            if (region == 2) ironAmt = 0.2;
            c = mixRgb(c, 0x69717A, ironAmt * (0.7 + 0.3 * n));
            if (region == 2) c = mixRgb(c, 0x3A1D12, 0.55);     // nose: scorched
            double rust = noise3(x * 0.35 + 20, y * 0.35, z * 0.35);
            if (rust > 0.68) c = mixRgb(c, 0x6A3A24, 0.45);
            // panel seams every 6 units give the plates a riveted look
            double sx = Math.abs(((x + 100) % 6.0) - 3.0), sy = Math.abs(((y + 100) % 6.0) - 3.0);
            if (region != 1 && (sx > 2.82 || sy > 2.85)) c = mixRgb(c, 0x0E0D0E, 0.45);
            return c;
        }

        // ---- cracks ----

        private static final double[][] MAIN = {
                {0.4, 27.0}, {-1.1, 29.2}, {0.5, 31.0}, {-1.4, 32.9},
                {0.4, 27.0}, {1.7, 25.0}, {0.8, 23.2}, {2.3, 21.4},
                {-1.1, 29.2}, {-3.4, 30.3}, {-5.0, 29.6}};
        private static final int[][] MAIN_SEG = {{0, 3}, {4, 7}, {8, 10}};

        /** Time (0..1) at which the central chest fissure reaches this point, or 2 if it does not. */
        private static double mainFissure(double x, double y, double z) {
            if (z < 5.3 || Math.abs(x) > 9.2 || y < 20.8 || y > 33.2) return 2;
            double best = 2;
            double total = 0;
            for (int[] seg : MAIN_SEG) {
                double run = 0;
                for (int k = seg[0]; k < seg[1]; k++) {
                    double ax = MAIN[k][0], ay = MAIN[k][1], bx = MAIN[k + 1][0], by = MAIN[k + 1][1];
                    double dx = bx - ax, dy = by - ay, len = Math.sqrt(dx * dx + dy * dy);
                    double t = clamp01(((x - ax) * dx + (y - ay) * dy) / (len * len));
                    double px = ax + dx * t, py = ay + dy * t;
                    double dist = Math.sqrt((x - px) * (x - px) + (y - py) * (y - py));
                    double width = 0.62 - 0.22 * (run + t * len) / 9.0;
                    if (dist < Math.max(0.3, width)) {
                        double tt = (run + t * len) / 9.5;
                        best = Math.min(best, tt);
                    }
                    run += len;
                }
                total = Math.max(total, run);
            }
            return best;
        }

        private static double ridge(double x, double y, double z) {
            double wx = (noise3(x * 0.07 + 3, y * 0.07, z * 0.07) - 0.5) * 16;
            double wy = (noise3(x * 0.07, y * 0.07 + 8, z * 0.07) - 0.5) * 16;
            double wz = (noise3(x * 0.07, y * 0.07, z * 0.07 + 15) - 0.5) * 16;
            double a = Math.abs(noise3((x + wx) * 0.082 + 1, (y + wy) * 0.082, (z + wz) * 0.082) - 0.5);
            double b = Math.abs(noise3((x - wy) * 0.118 + 40, (y + wz) * 0.118, (z - wx) * 0.118) - 0.5);
            return Math.min(a, b * 1.15);
        }

        private static double crackTime(int tier, double x, double y, double z) {
            double main = mainFissure(x, y, z);
            if (tier == 1) return main;
            double best = main < 2 ? main * 0.22 : 2;
            double r = ridge(x, y, z);
            if (tier == 2) {
                if (y >= 16.5 && y <= 34.5 && Math.abs(x) <= 14.5) {
                    double d = Math.sqrt(sq(x * 0.85) + sq((y - 27) * 1.15) + sq((z - 4) * 0.7));
                    if (r < 0.021 && d <= 25) best = Math.min(best, 0.12 + d / 25 * 0.80 + 0.08 * noise3(x * .3 + 3, y * .3, z * .3));
                }
            } else {
                double d = Math.sqrt(sq(x) + sq((y - 27) * 0.9) + sq(z * 0.8));
                if (r < 0.034 && d <= 54) best = Math.min(best, 0.08 + d / 54 * 0.84 + 0.08 * noise3(x * .3 + 3, y * .3, z * .3));
            }
            return best;
        }

        private static double sq(double v) {
            return v * v;
        }

        synchronized void ensure(int tier) {
            if (ready[tier]) return;
            for (Chunk c : chunks) {
                for (Face f : c.faces) {
                    int n = f.tw * f.th;
                    float[] ct = new float[n];
                    for (int j = 0; j < f.th; j++) {
                        for (int i = 0; i < f.tw; i++) {
                            double a = (i + 0.5) / f.tw, b = (j + 0.5) / f.th;
                            double px = f.o[0] + f.u[0] * a + f.v[0] * b;
                            double py = f.o[1] + f.u[1] * a + f.v[1] * b;
                            double pz = f.o[2] + f.u[2] * a + f.v[2] * b;
                            ct[j * f.tw + i] = (float) Math.min(2.0, crackTime(tier, px, py, pz));
                        }
                    }
                    float[] ht = new float[n];
                    float[] hv = new float[n];
                    for (int j = 0; j < f.th; j++) {
                        for (int i = 0; i < f.tw; i++) {
                            int idx = j * f.tw + i;
                            ht[idx] = 2f;
                            if (ct[idx] < 2f) continue;
                            double bestD = 9;
                            float bestT = 2f;
                            for (int dj = -2; dj <= 2; dj++) {
                                for (int di = -2; di <= 2; di++) {
                                    int ii = i + di, jj = j + dj;
                                    if (ii < 0 || jj < 0 || ii >= f.tw || jj >= f.th) continue;
                                    float t = ct[jj * f.tw + ii];
                                    if (t >= 2f) continue;
                                    double dist = Math.sqrt(di * di + dj * dj);
                                    if (dist < bestD) bestD = dist;
                                    if (t < bestT) bestT = t;
                                }
                            }
                            if (bestT < 2f) {
                                ht[idx] = bestT;
                                hv[idx] = (float) Math.max(0, 1 - bestD / 3.0);
                            }
                        }
                    }
                    f.crackT[tier] = ct;
                    f.haloT[tier] = ht;
                    f.halo[tier] = hv;
                }
            }
            ready[tier] = true;
        }
    }

    private static Model model;

    private static synchronized Model model() {
        if (model == null) model = new Model();
        return model;
    }

    /** A unit cube (centred, edge 1) used by chips and embers. */
    private static final Face[] CUBE = new Face[6];

    static {
        double[][][] g = {
                {{-.5, .5, .5}, {1, 0, 0}, {0, -1, 0}, {0, 0, 1}},
                {{.5, .5, -.5}, {-1, 0, 0}, {0, -1, 0}, {0, 0, -1}},
                {{.5, .5, .5}, {0, 0, -1}, {0, -1, 0}, {1, 0, 0}},
                {{-.5, .5, -.5}, {0, 0, 1}, {0, -1, 0}, {-1, 0, 0}},
                {{-.5, .5, -.5}, {1, 0, 0}, {0, 0, 1}, {0, 1, 0}},
                {{-.5, -.5, .5}, {1, 0, 0}, {0, 0, -1}, {0, -1, 0}}};
        for (int i = 0; i < 6; i++) {
            Face f = new Face();
            System.arraycopy(g[i][0], 0, f.o, 0, 3);
            System.arraycopy(g[i][1], 0, f.u, 0, 3);
            System.arraycopy(g[i][2], 0, f.v, 0, 3);
            System.arraycopy(g[i][3], 0, f.n, 0, 3);
            f.tw = 1;
            f.th = 1;
            f.base = new int[]{0x3A3634};
            f.eye = new boolean[1];
            CUBE[i] = f;
        }
    }

    // ------------------------------------------------------------------
    // Pose
    // ------------------------------------------------------------------

    private static final class Pose {
        final double[] rx = new double[NJ], ry = new double[NJ], rz = new double[NJ];
        double tx, ty, tz;
        final double[] g = new double[3];     // crack growth of stage 1, 2, 3 (0..1)
        double heat, pitch = 0.16, yaw = 0.30, zoom = 1.0;
        double tgy = 22, tgz = 0;   // camera target (follows the body when it falls toward the viewer)
        double lying;      // 0..1, how far the body has fallen (for the shadow)
    }

    private static double[][] joints(Pose p) {
        double[][] w = new double[NJ][];
        for (int j = 0; j < NJ; j++) {
            double[] pv = PIVOT[j];
            double[] local = mul(trans(pv[0], pv[1], pv[2]), mul(rotXYZ(p.rx[j], p.ry[j], p.rz[j]), trans(-pv[0], -pv[1], -pv[2])));
            if (j == ROOT) local = mul(trans(p.tx, p.ty, p.tz), local);
            w[j] = PARENT[j] < 0 ? local : mul(w[PARENT[j]], local);
        }
        return w;
    }

    private static void shake(Pose p, double t, double amp, double f) {
        double s1 = Math.sin(t * 83 * f), s2 = Math.sin(t * 71 * f + 1.3), s3 = Math.sin(t * 97 * f + 2.1), s4 = Math.sin(t * 61 * f + 0.4), s5 = Math.sin(t * 53 * f + 3.3);
        p.tx += 0.5 * amp * s1;
        p.tz += 0.22 * amp * s2;
        p.rz[TORSO] += 0.038 * amp * s2;
        p.rx[TORSO] += 0.022 * amp * s4;
        p.rx[HEAD] += 0.165 * amp * s3;
        p.rz[HEAD] += 0.135 * amp * s4;
        p.ry[HEAD] += 0.125 * amp * s5;
        p.rz[ARM_A] += 0.21 * amp * s4;
        p.rz[ARM_B] -= 0.21 * amp * s5;
        p.rx[ARM_A] += 0.19 * amp * s3;
        p.rx[ARM_B] += 0.19 * amp * s1;
        p.rx[UL_A] += 0.045 * amp * s5;
        p.rx[UL_B] -= 0.045 * amp * s3;
    }

    /**
     * The staged "catch" sequence. Stage 1 (every tier): the first fissure, twitch, stagger. Tier 2 and 3 then pause,
     * rocking like a shaking ball, and go on to stage 2 (cracks spread over the upper body, violent shaking) and,
     * for tier 3, stage 3 (cracks everywhere, white-hot, silent pause, detonation). Tier 1 ends after stage 1 with
     * the flop; tier 2 ends after stage 2 with the rupture; tier 3 ends after stage 3.
     */
    private void pose(Pose p, double t) {
        final double burst = this.burst;
        final double cs1 = cs[0], ce1 = ce[0];
        p.g[0] = ease((t - cs1) / (ce1 - cs1));
        double u3 = 0;
        if (tier >= 2) p.g[1] = ease((t - cs[1]) / (ce[1] - cs[1]));
        if (tier >= 3) {
            u3 = clamp01((t - cs[2]) / (ce[2] - cs[2]));
            p.g[2] = 1 - Math.pow(1 - u3, 2.3);
        }
        p.ty -= 5 * (1 - ease(t / 0.4));

        // ---- stage 1: idle, first crack, twitch, stagger back (identical for all tiers) ----
        double idle = 1 - clamp01((t - ce1) / 0.15);
        p.rx[TORSO] += 0.02 * Math.sin(t * 2.4) * idle;
        p.rz[ARM_A] += 0.04 * Math.sin(t * 2.0) * idle;
        p.rz[ARM_B] -= 0.04 * Math.sin(t * 2.0 + 1) * idle;
        double tw = t - ce1;
        if (tw > 0 && tw < 0.25) {
            double k = 1 - tw / 0.25;
            p.rz[TORSO] += 0.07 * Math.sin(tw * 70) * k;
            p.rx[ARM_A] += 0.5 * Math.sin(tw * 55) * k;
            p.rx[ARM_B] -= 0.5 * Math.sin(tw * 55) * k;
            p.ry[HEAD] += 0.25 * Math.sin(tw * 65) * k;
        }
        double sb = Math.sin(Math.PI * clamp01((t - (ce1 + 0.08)) / 0.42));
        p.rx[TORSO] += -0.20 * sb;
        p.rx[HEAD] += 0.10 * sb;
        p.tz -= 3.0 * sb;
        p.rx[ARM_A] += 0.45 * sb;
        p.rx[ARM_B] += 0.45 * sb;
        p.rx[UL_A] += 0.18 * sb;
        p.rx[UL_B] -= 0.18 * sb;

        double heat;
        if (tier == 1) {
            // ---- tier 1 ends here: the body tips forward, the knees buckle and it flops ----
            double tTip = ce1 + 0.42;
            double u = clamp01((t - tTip) / (burst - tTip));
            double tau = Math.max(0, t - burst);
            double theta = 1.50 * Math.pow(u, 2.2) - 0.10 * Math.exp(-tau * 7) * Math.abs(Math.sin(tau * 16));
            p.rx[ROOT] += theta;
            p.lying = clamp01(theta / 1.5);
            p.rx[TORSO] += 0.45 * ease(u);
            double bend = ease(u * 1.8);
            p.rx[UL_A] += -0.65 * bend;
            p.rx[UL_B] += -0.65 * bend;
            p.rx[LL_A] += 0.95 * bend;
            p.rx[LL_B] += 0.95 * bend;
            p.ty -= 3.0 * bend;
            p.rx[ARM_A] += 0.30 * ease(u);
            p.rx[ARM_B] += 0.30 * ease(u);
            heat = 0.30 * p.g[0];
            if (t > burst) {
                double k = Math.exp(-tau * 5);
                double fl = (1 - k) + 0.25 * Math.exp(-tau * 6) * Math.sin(tau * 15);   // arms flop outward and bounce
                p.rz[ARM_A] += 0.80 * fl;
                p.rz[ARM_B] -= 0.80 * fl;
                p.rx[ARM_A] += -0.15 * (1 - k);
                p.rx[ARM_B] += -0.15 * (1 - k);
                p.rx[HEAD] += -0.25 * Math.exp(-tau * 6) * Math.sin(tau * 14);
                heat = 0.30 * p.g[0] * Math.max(0, 1 - tau / 1.6);
            }
            p.rx[HEAD] += 0.4 * ease(u);
            double kc = ease((t - 1.55) / 0.9);
            p.pitch = 0.16 + 0.36 * kc;
            p.tgy = 22 - 13 * kc;
            p.tgz = 22 * kc;
            p.zoom = 1 - 0.22 * kc;
            p.yaw = 0.30 - 0.06 * ease(u);
        } else {
            // ---- stage 1 beat: the golem rocks forward as if about to fall, then holds (the first "shake") ----
            double wf = Math.sin(Math.PI * clamp01((t - (ce1 + 0.5)) / 0.4));
            p.rx[TORSO] += 0.14 * wf;
            p.rx[HEAD] -= 0.06 * wf;
            p.tz += 1.5 * wf;
            p.rx[ARM_A] -= 0.20 * wf;
            p.rx[ARM_B] -= 0.20 * wf;
            heat = 0.05 + 0.25 * p.g[0];
            if (t > ce1 && t < cs[1]) heat += 0.04 * Math.sin(t * 9);        // heartbeat while paused
            double amp = 0, f = 1;
            double g2 = p.g[1];
            heat = Math.max(heat, 0.30 + 0.50 * g2 * g2);
            if (t >= cs[1]) {
                // ---- stage 2: cracks spread over the upper body, the golem shakes violently ----
                if (t < ce[1]) {
                    amp = 0.2 + 0.9 * g2;
                } else if (tier == 2) {
                    double b = ease((t - ce[1]) / (burst - ce[1]));              // buckle until the rupture
                    amp = 1.1 + 0.9 * b;
                    heat = Math.max(heat, 0.85 + 0.15 * b);
                    p.rx[UL_A] += -0.32 * b;
                    p.rx[UL_B] += -0.32 * b;
                    p.rx[LL_A] += 0.55 * b;
                    p.rx[LL_B] += 0.55 * b;
                    p.ty -= 2.0 * b;
                    p.rx[TORSO] += -0.14 * b;
                    p.rx[HEAD] += 0.20 * b;
                    if (t > burst) {
                        double w = ease((t - burst) / 1.2);
                        p.ty -= 5 * w;
                        p.rx[UL_A] += -0.4 * w;
                        p.rx[UL_B] += -0.4 * w;
                        p.rx[LL_A] += 0.5 * w;
                        p.rx[LL_B] += 0.5 * w;
                        heat = 1 - 0.5 * w;
                    }
                } else {
                    double q = t - ce[1];                                         // tier 3: a hard shudder, then calm again
                    amp = 0.10 + 1.3 * Math.exp(-q * 7);
                    p.ty += 1.0 * Math.max(0, Math.sin(q * 14)) * Math.exp(-q * 5);
                }
                if (tier == 3) {
                    p.rz[ARM_A] += 0.12 * g2;
                    p.rz[ARM_B] -= 0.12 * g2;
                }
            } else if (t >= ce1) {
                amp = 0.08;
            }
            if (tier >= 3 && t >= cs[2]) {
                // ---- stage 3: cracks everywhere, white-hot, then the (nearly frozen) pause ----
                heat = Math.max(heat, 0.80 + 0.20 * Math.pow(u3, 1.4));
                if (t < ce[2]) {
                    amp = 0.2 + 1.6 * p.g[2];
                    f = 1 + 0.5 * u3;
                } else {
                    amp = 0.35 + 0.12 * Math.sin(t * 40);
                    f = 1.5;
                }
                p.rz[ARM_A] += 0.28 * p.g[2];
                p.rz[ARM_B] -= 0.28 * p.g[2];
                p.rx[HEAD] += -0.25 * p.g[2];
                p.rx[TORSO] += -0.10 * p.g[2];
            }
            if (amp > 0) shake(p, t, amp, f);
            p.pitch = tier == 2 ? 0.15 : 0.14;
            p.zoom = 1 + 0.06 * g2 + (tier >= 3 ? 0.06 * p.g[2] : 0);
        }
        p.heat = heat;
    }

    // ------------------------------------------------------------------
    // Sequence (set by begin)
    // ------------------------------------------------------------------

    private int tier;
    private boolean vial;
    private double[] cs = {0.35, 2.0, 4.0}, ce = {1.1, 3.0, 6.0};   // stage schedule (crack growth start/end)
    private double burst;
    private double[] launch;       // launch time per chunk (or +inf)
    private double[][] vel, spin;  // per chunk
    private double[][] frozen;     // cached joint-frame matrix at launch
    private boolean[] launches;

    private static final class Chip {
        double ts, life, size;
        double[] p0 = new double[3], v = new double[3], spin = new double[3];
        boolean ember;
        double grav = Double.NaN;
        double[] src;      // world-space start (computed lazily)
        int joint;
        double[] local = new double[3];
    }

    private final List<Chip> chips = new ArrayList<>();

    /** A piece that flies out of the chest at the moment of the explosion. */
    private Chip shard(Random r, boolean glowing, double speed) {
        Chip c = chip(r, burst + r.nextDouble() * (tier == 3 ? 0.18 : 0.05), 1.4, glowing, speed, 1.4 + 1.2 * r.nextDouble());
        c.ts = burst + r.nextDouble() * (tier == 3 ? 0.18 : 0.05);
        if (glowing) c.size = 0.9 + 0.7 * r.nextDouble();
        double dx = c.local[0], dy = c.local[1] - 27, dz = c.local[2] - 2;
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-6;
        c.v[0] = dx / l * speed + r.nextGaussian() * speed * 0.15;
        c.v[1] = dy / l * speed + speed * 0.25 + r.nextGaussian() * speed * 0.15;
        c.v[2] = dz / l * speed * 0.6 + r.nextGaussian() * speed * 0.10;
        c.grav = glowing ? -30 : -70;
        c.life = tier == 3 ? 1.7 : 1.3;
        return c;
    }

    void begin(int tier, boolean vial, long seed, double[] cs, double[] ce, double burst) {
        this.tier = Math.max(1, Math.min(3, tier));
        this.vial = vial;
        this.cs = cs.clone();
        this.ce = ce.clone();
        this.burst = burst;
        Model m = model();
        for (int k = 1; k <= this.tier; k++) m.ensure(k);
        Random r = new Random(seed);
        int n = m.chunks.size();
        launch = new double[n];
        vel = new double[n][3];
        spin = new double[n][3];
        frozen = new double[n][];
        launches = new boolean[n];
        double[] chest = {0, 27, 2};
        for (int i = 0; i < n; i++) {
            Chunk c = m.chunks.get(i);
            double dx = c.cx - chest[0], dy = c.cy - chest[1], dz = c.cz - chest[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-6;
            launch[i] = Double.POSITIVE_INFINITY;
            if (this.tier >= 2) {
                boolean full = c.minTier <= this.tier;
                launches[i] = true;
                double speed;
                if (this.tier == 2) {
                    speed = full ? 34 + 30 * r.nextDouble() : 6 + 8 * r.nextDouble();
                    launch[i] = burst + (full ? 0.0 : 0.30 + 0.25 * r.nextDouble());
                } else {
                    speed = (62 + 70 * r.nextDouble()) * (vial ? 1.2 : 1.0);
                    launch[i] = burst + 0.20 * dist / 30.0;
                }
                double jx = r.nextGaussian() * 0.25, jy = r.nextGaussian() * 0.25, jz = r.nextGaussian() * 0.25;
                double vx = dx / dist + jx, vy = dy / dist + jy + 0.35, vz = dz / dist + jz + 0.10;
                double l = Math.sqrt(vx * vx + vy * vy + vz * vz) + 1e-6;
                vel[i][0] = vx / l * speed;
                vel[i][1] = vy / l * speed;
                vel[i][2] = vz / l * speed;
                double sp = this.tier == 3 ? 9 : 6;
                spin[i][0] = (r.nextDouble() - 0.5) * 2 * sp;
                spin[i][1] = (r.nextDouble() - 0.5) * 2 * sp;
                spin[i][2] = (r.nextDouble() - 0.5) * 2 * sp;
            }
        }
        chips.clear();
        if (this.tier == 1) {
            for (int i = 0; i < 9; i++) chips.add(chip(r, burst + r.nextDouble() * 0.06, 1.0, true, 16 + 16 * r.nextDouble(), 0.9));
        } else if (this.tier == 2) {
            double a = this.cs[1], b = burst - 0.05;
            for (int i = 0; i < 16; i++) chips.add(chip(r, a + (b - a) * (0.5 + 0.5 * r.nextDouble()), 1.2, false, 10 + 14 * r.nextDouble(), 1.3 + r.nextDouble()));
            for (int i = 0; i < 18; i++) chips.add(chip(r, a + (b - a) * (0.3 + 0.7 * r.nextDouble()), 1.0, true, 8 + 12 * r.nextDouble(), 0.8));
            for (int i = 0; i < 16; i++) chips.add(shard(r, false, 30 + 35 * r.nextDouble()));
            for (int i = 0; i < 22; i++) chips.add(shard(r, true, 25 + 45 * r.nextDouble()));
        } else {
            double a = this.cs[1], b = this.ce[1];
            for (int i = 0; i < 8; i++) chips.add(chip(r, a + (b - a) * (0.5 + 0.5 * r.nextDouble()), 1.2, false, 10 + 12 * r.nextDouble(), 1.3 + r.nextDouble()));
            for (int i = 0; i < 12; i++) chips.add(chip(r, a + (b - a) * (0.3 + 0.7 * r.nextDouble()), 1.0, true, 8 + 12 * r.nextDouble(), 0.8));
            double c = this.cs[2], d = this.ce[2];
            for (int i = 0; i < 26; i++) chips.add(chip(r, c + (d - c) * (0.30 + 0.70 * r.nextDouble()), 1.3, false, 12 + 16 * r.nextDouble(), 1.3 + r.nextDouble()));
            for (int i = 0; i < 70; i++) chips.add(chip(r, c + (d - c) * (0.20 + 0.85 * r.nextDouble()), 1.0, true, 10 + 16 * r.nextDouble(), 0.8));
            double mult = vial ? 1.25 : 1.0;
            for (int i = 0; i < Math.round(40 * mult); i++) chips.add(shard(r, false, (55 + 70 * r.nextDouble()) * mult));
            for (int i = 0; i < Math.round(60 * mult); i++) chips.add(shard(r, true, (50 + 85 * r.nextDouble()) * mult));
        }
    }

    private Chip chip(Random r, double ts, double life, boolean ember, double speed, double size) {
        Chip c = new Chip();
        c.ts = ts;
        c.life = life;
        c.ember = ember;
        c.size = ember ? 0.7 + 0.5 * r.nextDouble() : size;
        List<Chunk> ch = model().chunks;
        Chunk src = ch.get(r.nextInt(ch.size()));
        if (!ember && tier >= 2) src = ch.get(r.nextInt(12));            // torso chunks (first 12)
        c.joint = src.joint;
        c.local[0] = src.cx + (r.nextDouble() - 0.5) * 4;
        c.local[1] = src.cy + (r.nextDouble() - 0.5) * 4;
        c.local[2] = (ember && tier == 1) ? src.cz : src.cz + (r.nextDouble() - 0.2) * 3;
        double ang = r.nextDouble() * Math.PI * 2;
        if (ember) {
            c.v[0] = Math.cos(ang) * speed * 0.35;
            c.v[1] = speed * (0.4 + 0.6 * r.nextDouble());
            c.v[2] = (r.nextDouble() - 0.3) * speed * 0.4;
        } else {
            c.v[0] = Math.cos(ang) * speed * 0.7;
            c.v[1] = speed * (0.3 + 0.5 * r.nextDouble());
            c.v[2] = (0.3 + 0.7 * r.nextDouble()) * speed * 0.7;
        }
        c.spin[0] = (r.nextDouble() - 0.5) * 12;
        c.spin[1] = (r.nextDouble() - 0.5) * 12;
        c.spin[2] = (r.nextDouble() - 0.5) * 12;
        return c;
    }

    // ------------------------------------------------------------------
    // Camera + projection
    // ------------------------------------------------------------------

    private static final double CAM_D = 180, CAM_F = 402;
    private double[] cam;        // world -> camera
    private double camF;

    private void setupCamera(Pose p) {
        cam = mul(rotX(p.pitch), mul(rotY(p.yaw), trans(0, -p.tgy, -p.tgz)));
        camF = CAM_F * p.zoom;
    }

    private double[] project(double[] camPt) {
        double depth = CAM_D - camPt[2];
        return new double[]{FW / 2.0 + camF * camPt[0] / depth, FH * 0.52 - camF * camPt[1] / depth, 1.0 / depth};
    }

    /** Where the chest is on screen (framebuffer pixels) at time t, without drawing anything. */
    float[] chestAt(double t) {
        Pose p = new Pose();
        poseAt(p, t);
        setupCamera(p);
        double[][] w = joints(p);
        double[] a = new double[3], b = new double[3];
        apply(w[TORSO], 0, 27, 2, a);
        apply(cam, a[0], a[1], a[2], b);
        double[] s = project(b);
        return new float[]{(float) s[0], (float) s[1]};
    }

    private void poseAt(Pose p, double t) {
        double te = t;
        if (tier == 3 && t > ce[2]) te = ce[2] + (t - ce[2]) * 0.04;       // the build-up almost freezes before detonation
        pose(p, te);
        if (tier == 3 && t > burst) p.heat = Math.max(0.12, 1 - (t - burst) * 0.9);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private double[] curG = new double[3];
    private double curHeat, curCore;
    private int curTier;
    private double[] lightDir = norm(-0.45, 0.55, 0.70);

    private static double[] norm(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    void draw(double t) {
        java.util.Arrays.fill(pix, 0);
        java.util.Arrays.fill(zbuf, 0f);
        java.util.Arrays.fill(emis, 0f);
        Pose p = new Pose();
        poseAt(p, t);
        setupCamera(p);
        System.arraycopy(p.g, 0, curG, 0, 3);
        curHeat = p.heat;
        curTier = tier;
        double[][] w = joints(p);
        Model m = model();

        drawShadow(p, t);

        for (int i = 0; i < m.chunks.size(); i++) {
            Chunk c = m.chunks.get(i);
            double tau = t - launch[i];
            if (tau < 0 || !launches[i]) {
                curCore = 0;
                drawChunk(c, mul(cam, w[c.joint]), false, 1.0);
            } else {
                if (frozen[i] == null) {
                    Pose fp = new Pose();
                    poseAt(fp, launch[i]);
                    frozen[i] = joints(fp)[c.joint];
                }
                double[] wf = frozen[i];
                double[] cw = new double[3];
                apply(wf, c.cx, c.cy, c.cz, cw);
                double[] v = vel[i];
                double px = v[0] * tau, py = v[1] * tau - 60 * tau * tau, pz = v[2] * tau;
                double[] rot = rotXYZ(spin[i][0] * tau, spin[i][1] * tau, spin[i][2] * tau);
                double[] mm = mul(trans(cw[0] + px, cw[1] + py, cw[2] + pz), mul(rot, mul(trans(-cw[0], -cw[1], -cw[2]), wf)));
                double life = tier == 3 ? 2.1 : 1.7;
                double alpha = 1 - ease((tau - life * 0.55) / (life * 0.45));
                if (alpha <= 0.04) continue;
                double[] mc = mul(cam, mm);
                double[] q = new double[3];
                apply(mc, c.cx, c.cy, c.cz, q);
                double[] s = project(q);
                double edge = Math.min(Math.min(s[0], FW - s[0]) / 22.0, Math.min(s[1], FH - s[1]) / 18.0);
                alpha *= clamp01(edge);
                if (alpha <= 0.04) continue;
                curCore = Math.exp(-tau * (tier == 3 ? 0.9 : 1.3)) * (0.55 + 0.45 * Math.min(1, p.heat + 0.2));
                drawChunk(c, mc, true, alpha);
            }
        }
        for (Chip c : chips) drawChip(c, t);
        bloom(p);
        float[] ch = chestAt(t);
        chestFx = ch[0];
        chestFy = ch[1];
    }

    float chestFx = FW / 2f, chestFy = FH / 2f;

    private void drawShadow(Pose p, double t) {
        double rx = 12 + 8 * p.lying, rz = 7 + 16 * p.lying;
        double cz = 3 + 20 * p.lying;
        if (tier >= 2 && t > burst) return;
        int n = 28;
        double[][] pts = new double[n][];
        double[] tmp = new double[3];
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            apply(cam, Math.cos(a) * rx, 0, cz + Math.sin(a) * rz, tmp);
            pts[i] = project(tmp);
        }
        double minY = 1e9, maxY = -1e9;
        for (double[] q : pts) {
            minY = Math.min(minY, q[1]);
            maxY = Math.max(maxY, q[1]);
        }
        for (int y = Math.max(0, (int) Math.floor(minY)); y <= Math.min(FH - 1, (int) Math.ceil(maxY)); y++) {
            double yy = y + 0.5, xl = 1e9, xr = -1e9;
            for (int i = 0; i < n; i++) {
                double[] a = pts[i], b = pts[(i + 1) % n];
                if ((a[1] <= yy && b[1] > yy) || (b[1] <= yy && a[1] > yy)) {
                    double x = a[0] + (yy - a[1]) / (b[1] - a[1]) * (b[0] - a[0]);
                    xl = Math.min(xl, x);
                    xr = Math.max(xr, x);
                }
            }
            for (int x = Math.max(0, (int) Math.ceil(xl - 0.5)); x <= Math.min(FW - 1, (int) Math.floor(xr - 0.5)); x++) {
                pix[y * FW + x] = 0x66000000;
            }
        }
    }

    private void drawChunk(Chunk c, double[] mc, boolean interior, double alpha) {
        for (Face f : c.faces) {
            if (!interior && !f.exterior) continue;
            drawFace(f, mc, interior && !f.exterior, alpha, false, 0);
        }
    }

    private void drawChip(Chip c, double t) {
        double tau = t - c.ts;
        if (tau < 0 || tau > c.life) return;
        if (c.src == null) {
            Pose fp = new Pose();
            poseAt(fp, c.ts);
            double[][] wf = joints(fp);
            c.src = new double[3];
            apply(wf[c.joint], c.local[0], c.local[1], c.local[2], c.src);
        }
        double g = !Double.isNaN(c.grav) ? c.grav : (c.ember ? -4 : -70);
        double px = c.src[0] + c.v[0] * tau, py = c.src[1] + c.v[1] * tau + 0.5 * g * tau * tau, pz = c.src[2] + c.v[2] * tau;
        double f = tau / c.life;
        double alpha = 1 - ease((f - 0.6) / 0.4);
        double size = c.size * (c.ember ? 1 - 0.5 * f : 1);
        double[] mm = mul(trans(px, py, pz), mul(rotXYZ(c.spin[0] * tau, c.spin[1] * tau, c.spin[2] * tau), scaleM(size)));
        double[] mc = mul(cam, mm);
        double[] q = new double[3];
        apply(mc, 0, 0, 0, q);
        double[] s = project(q);
        double edge = Math.min(Math.min(s[0], FW - s[0]) / 14.0, Math.min(s[1], FH - s[1]) / 12.0);
        alpha *= clamp01(edge);
        if (alpha <= 0.04) return;
        for (Face face : CUBE) {
            face.base[0] = c.ember ? 0xFF9A28 : 0x4A4542;
            drawFace(face, mc, false, alpha, true, c.ember ? 1 : 0);
        }
    }

    /** Flat-shaded or textured face rasteriser (perspective-correct, z-buffered). */
    private void drawFace(Face f, double[] mc, boolean cutFace, double alpha, boolean flat, int flatMode) {
        double[] q0 = new double[3], q1 = new double[3], q2 = new double[3], q3 = new double[3], qc = new double[3], nq = new double[3];
        apply(mc, f.o[0], f.o[1], f.o[2], q0);
        apply(mc, f.o[0] + f.u[0], f.o[1] + f.u[1], f.o[2] + f.u[2], q1);
        apply(mc, f.o[0] + f.u[0] + f.v[0], f.o[1] + f.u[1] + f.v[1], f.o[2] + f.u[2] + f.v[2], q2);
        apply(mc, f.o[0] + f.v[0], f.o[1] + f.v[1], f.o[2] + f.v[2], q3);
        apply(mc, f.o[0] + 0.5 * (f.u[0] + f.v[0]), f.o[1] + 0.5 * (f.u[1] + f.v[1]), f.o[2] + 0.5 * (f.u[2] + f.v[2]), qc);
        applyDir(mc, f.n[0], f.n[1], f.n[2], nq);
        double nl = Math.sqrt(nq[0] * nq[0] + nq[1] * nq[1] + nq[2] * nq[2]);
        nq[0] /= nl;
        nq[1] /= nl;
        nq[2] /= nl;
        double vx = -qc[0], vy = -qc[1], vz = CAM_D - qc[2];
        if (nq[0] * vx + nq[1] * vy + nq[2] * vz <= 0) return;
        if (CAM_D - q0[2] < 8 || CAM_D - q1[2] < 8 || CAM_D - q2[2] < 8 || CAM_D - q3[2] < 8) return;
        double[] s0 = project(q0), s1 = project(q1), s2 = project(q2), s3 = project(q3);
        double shade = 0.46 + 0.62 * Math.max(0, nq[0] * lightDir[0] + nq[1] * lightDir[1] + nq[2] * lightDir[2]);
        tri(f, s0, s1, s2, 0, 0, 1, 0, 1, 1, shade, cutFace, alpha, flat, flatMode);
        tri(f, s0, s2, s3, 0, 0, 1, 1, 0, 1, shade, cutFace, alpha, flat, flatMode);
    }

    private void tri(Face f, double[] a, double[] b, double[] c, double ua, double va, double ub, double vb, double uc, double vc,
                     double shade, boolean cutFace, double alpha, boolean flat, int flatMode) {
        double area = (b[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (b[1] - a[1]);
        if (Math.abs(area) < 1e-9) return;
        int x0 = Math.max(0, (int) Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int x1 = Math.min(FW - 1, (int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int y0 = Math.max(0, (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int y1 = Math.min(FH - 1, (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        double inv = 1.0 / area;
        int alphaByte = (int) Math.round(clamp01(alpha) * 255);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double px = x + 0.5, py = y + 0.5;
                double w0 = ((b[0] - px) * (c[1] - py) - (c[0] - px) * (b[1] - py)) * inv;
                double w1 = ((c[0] - px) * (a[1] - py) - (a[0] - px) * (c[1] - py)) * inv;
                double w2 = 1 - w0 - w1;
                if (w0 < -1e-4 || w1 < -1e-4 || w2 < -1e-4) continue;
                double iw = w0 * a[2] + w1 * b[2] + w2 * c[2];
                int pi = y * FW + x;
                if (iw <= zbuf[pi]) continue;
                double u = (w0 * a[2] * ua + w1 * b[2] * ub + w2 * c[2] * uc) / iw;
                double v = (w0 * a[2] * va + w1 * b[2] * vb + w2 * c[2] * vc) / iw;
                int tx = Math.min(f.tw - 1, Math.max(0, (int) (u * f.tw)));
                int ty = Math.min(f.th - 1, Math.max(0, (int) (v * f.th)));
                int ti = ty * f.tw + tx;
                int rgb;
                float em = 0f;
                if (flat) {
                    if (flatMode == 1) {
                        rgb = mixRgb(f.base[0], 0xFFE070, 0.25 + 0.4 * shade);
                        em = 0.55f;
                    } else {
                        rgb = scaleRgb(f.base[0], shade);
                    }
                } else if (cutFace) {
                    double n = fbm(u * 3 + ti, v * 3, 7);
                    double hot = clamp01(curCore * (0.45 + 0.8 * n));
                    rgb = mixRgb(0x1C0C08, 0xFF7A18, hot);
                    if (hot > 0.85) rgb = mixRgb(rgb, 0xFFE9B0, (hot - 0.85) / 0.15 * 0.7);
                    em = (float) (hot * 0.8);
                } else {
                    rgb = shadeTexel(f, ti, shade);
                    em = lastEmis;
                }
                pix[pi] = (alphaByte << 24) | rgb;
                zbuf[pi] = (float) iw;
                emis[pi] = em;
            }
        }
    }

    private float lastEmis;

    private static int scaleRgb(int c, double f) {
        int r = Math.min(255, (int) (((c >> 16) & 255) * f));
        int g = Math.min(255, (int) (((c >> 8) & 255) * f));
        int b = Math.min(255, (int) ((c & 255) * f));
        return (r << 16) | (g << 8) | b;
    }

    private int shadeTexel(Face f, int ti, double shade) {
        double heat = curHeat;
        double wash = curTier == 3 ? 0.30 : 0.18;
        int rgb = scaleRgb(f.base[ti], shade);
        lastEmis = 0f;
        rgb = mixRgb(rgb, 0xFF4A10, heat * wash * 0.5);
        if (f.eye[ti]) {
            lastEmis = 0.8f;
            return mixRgb(mixRgb(0xE0701A, 0xFFE08A, heat), 0xFFFFFF, curTier == 3 ? heat * heat * 0.8 : 0);
        }
        double front = -1, halo = 0;
        for (int t = 1; t <= curTier; t++) {          // every stage reached so far contributes its cracks
            double g = curG[t - 1];
            if (g <= 0) continue;
            if (f.crackT[t][ti] <= g) {
                front = Math.max(front, clamp01(1 - (g - f.crackT[t][ti]) / 0.10));
            } else if (f.haloT[t][ti] <= g) {
                halo = Math.max(halo, f.halo[t][ti]);
            }
        }
        if (front >= 0) {
            double white = curTier == 3 ? Math.pow(heat, 2.2) : heat * 0.12;
            int col = mixRgb(0xD02A00, 0xFFB030, clamp01(heat * 1.15));
            col = mixRgb(col, 0xFFF4D8, clamp01(white + front * 0.45));
            lastEmis = (float) (0.6 + 0.4 * front);
            return col;
        }
        if (halo > 0) {
            double h = halo * heat;
            rgb = mixRgb(rgb, 0xFF5A14, h * 0.75);
            lastEmis = (float) (h * 0.25);
        }
        if (curTier == 3 && heat > 0.7) {
            double k = (heat - 0.7) / 0.3;
            rgb = mixRgb(rgb, 0xFFD8A0, k * k * 0.28);
        }
        return rgb;
    }

    // merged rectangles for the HUD (horizontal runs of one colour, merged downward when identical)
    final int[] rx0 = new int[FW * FH / 2], ry0 = new int[FW * FH / 2], rx1 = new int[FW * FH / 2], ry1 = new int[FW * FH / 2], rc = new int[FW * FH / 2];
    int rectCount;
    private final int[] openPrev = new int[FW + 1], openCur = new int[FW + 1];

    /** Slightly coarser colours (steps of 12) so that neighbouring pixels merge into longer rectangles. */
    private static int quant(int a, int c) {
        int r = ((c >> 16) & 255) / 12 * 12, g = ((c >> 8) & 255) / 12 * 12, b = (c & 255) / 12 * 12;
        return ((a / 16 * 16 + 8) << 24) | (r << 16) | (g << 8) | b;
    }

    /** Turns {@link #pix} into rectangles. alphaMul scales every pixel's alpha (global opacity / fade). */
    void buildRects(float alphaMul) {
        rectCount = 0;
        java.util.Arrays.fill(openPrev, -1);
        for (int y = 0; y < FH; y++) {
            java.util.Arrays.fill(openCur, -1);
            int x = 0;
            while (x < FW) {
                int c = pix[y * FW + x];
                int a = (int) ((c >>> 24) * alphaMul);
                if (a < 6) {
                    x++;
                    continue;
                }
                int col = quant(a, c);
                int x2 = x + 1;
                while (x2 < FW) {
                    int c2 = pix[y * FW + x2];
                    int a2 = (int) ((c2 >>> 24) * alphaMul);
                    if (quant(a2, c2) != col) break;
                    x2++;
                }
                int idx = openPrev[x];
                if (idx >= 0 && rx1[idx] == x2 && rc[idx] == col && ry1[idx] == y) {
                    ry1[idx] = y + 1;
                    openCur[x] = idx;
                } else if (rectCount < rx0.length) {
                    idx = rectCount++;
                    rx0[idx] = x;
                    rx1[idx] = x2;
                    ry0[idx] = y;
                    ry1[idx] = y + 1;
                    rc[idx] = col;
                    openCur[x] = idx;
                }
                x = x2;
            }
            System.arraycopy(openCur, 0, openPrev, 0, openCur.length);
        }
    }

    /** Soft glow around the hot pixels, also spilling onto the empty background. */
    private void bloom(Pose p) {
        double heat = p.heat;
        if (heat < 0.05) return;
        int r = curTier == 3 && heat > 0.8 ? 5 : 3;
        boxBlur(emis, blurA, FW, FH, r, true);
        boxBlur(blurA, blurB, FW, FH, r, false);
        double gain = (0.7 + 1.7 * heat) * (curTier == 3 ? 1.2 : 1.0);
        int glow = curTier == 3 ? mixRgb(0xFF6A18, 0xFFF0D0, heat * heat) : 0xFF6A18;
        for (int i = 0; i < pix.length; i++) {
            double g = blurB[i] * gain;
            if (g < 0.06) continue;
            g = Math.floor(Math.min(1, g) * 5) / 5.0;
            if (g <= 0) continue;
            int a = pix[i] >>> 24;
            if (a == 0) {
                pix[i] = ((int) (Math.min(0.55, g * 0.55) * 255) << 24) | glow;
            } else {
                int rgb = mixRgb(pix[i] & 0xFFFFFF, glow, g * 0.16);
                pix[i] = (a << 24) | rgb;
            }
        }
    }

    private static void boxBlur(float[] src, float[] dst, int w, int h, int r, boolean horizontal) {
        float norm = 1f / (2 * r + 1);
        if (horizontal) {
            for (int y = 0; y < h; y++) {
                float sum = 0;
                int row = y * w;
                for (int x = -r; x <= r; x++) sum += src[row + Math.max(0, Math.min(w - 1, x))];
                for (int x = 0; x < w; x++) {
                    dst[row + x] = sum * norm;
                    sum += src[row + Math.min(w - 1, x + r + 1)] - src[row + Math.max(0, x - r)];
                }
            }
        } else {
            for (int x = 0; x < w; x++) {
                float sum = 0;
                for (int y = -r; y <= r; y++) sum += src[Math.max(0, Math.min(h - 1, y)) * w + x];
                for (int y = 0; y < h; y++) {
                    dst[y * w + x] = sum * norm;
                    sum += src[Math.min(h - 1, y + r + 1) * w + x] - src[Math.max(0, y - r) * w + x];
                }
            }
        }
    }
}
