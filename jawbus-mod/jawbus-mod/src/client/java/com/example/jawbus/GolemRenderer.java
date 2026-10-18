package com.example.jawbus;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the software 3D golem on its own background thread, so the game's render thread never waits for it.
 * The golem is a pure function of time, so the worker simply draws "the picture for time t" whenever it is free and
 * the game draws the newest finished picture. If the worker is slower than the game's frame rate, the golem is shown
 * at a lower picture rate while the game itself keeps its full frame rate.
 *
 * Only plain arrays cross between the threads (an immutable snapshot swapped through a volatile field).
 */
final class GolemRenderer {
    /** One finished picture: merged rectangles in golem-framebuffer pixels (x0, y0, x1, y1, ARGB). */
    static final class Frame {
        final int gen;
        final int n;
        final int[] x0, y0, x1, y1, c;

        Frame(int gen, Golem3D g) {
            this.gen = gen;
            this.n = g.rectCount;
            this.x0 = java.util.Arrays.copyOf(g.rx0, n);
            this.y0 = java.util.Arrays.copyOf(g.ry0, n);
            this.x1 = java.util.Arrays.copyOf(g.rx1, n);
            this.y1 = java.util.Arrays.copyOf(g.ry1, n);
            this.c = java.util.Arrays.copyOf(g.rc, n);
        }
    }

    private final Golem3D g3 = new Golem3D();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jawbus-golem");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile Frame latest;
    private volatile float[] chest;
    private volatile int gen;
    private volatile boolean failed;
    private volatile boolean ready;

    boolean failed() {
        return failed;
    }

    boolean ready() {
        return ready;
    }

    /** Builds the model for a new reveal in the background (this used to stall the game for ~50 ms). */
    void begin(int tier, boolean vial, long seed, double[] cs, double[] ce, double burst) {
        final int myGen = ++gen;
        ready = false;
        latest = null;
        chest = null;
        failed = false;
        busy.set(true);                     // no pictures are requested until the model exists
        pool.execute(() -> {
            try {
                g3.begin(tier, vial, seed, cs, ce, burst);
                chest = g3.chestAt(burst);
                if (myGen == gen) ready = true;
            } catch (Throwable ex) {
                failed = true;
                KillTracker.debug("3D golem could not start (" + ex + "), continuing without it.");
            } finally {
                busy.set(false);
            }
        });
    }

    /** The chest position (framebuffer pixels) once the model is built, else null. */
    float[] chest() {
        return chest;
    }

    /** Asks for the picture at time t (ignored while the worker is still busy with the previous one). */
    void request(double t, float alpha) {
        if (!ready || failed) return;
        if (!busy.compareAndSet(false, true)) return;
        final int myGen = gen;
        try {
            pool.execute(() -> {
                try {
                    if (myGen != gen) return;
                    g3.draw(t);
                    g3.buildRects(alpha);
                    Frame f = new Frame(myGen, g3);
                    if (myGen == gen) latest = f;
                } catch (Throwable ex) {
                    failed = true;
                    KillTracker.debug("3D golem failed (" + ex + "), continuing without it.");
                } finally {
                    busy.set(false);
                }
            });
        } catch (RuntimeException ex) {
            busy.set(false);
        }
    }

    /** Newest finished picture of the current reveal, or null. */
    Frame frame() {
        Frame f = latest;
        return f != null && f.gen == gen ? f : null;
    }

    /** Stops showing the old reveal immediately (the worker may still be finishing a picture; it is discarded). */
    void clear() {
        gen++;
        latest = null;
        ready = false;
    }
}
