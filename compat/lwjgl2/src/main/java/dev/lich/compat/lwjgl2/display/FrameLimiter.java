package dev.lich.compat.lwjgl2.display;

import org.lwjgl.system.Platform;

/**
 * {@code Display.sync}: holds the calling thread until the next frame is due at the requested rate. Like
 * LWJGL 2's, it sleeps while a typical sleep still fits before the deadline, then yields for the rest, and it
 * learns how long sleeps and yields really take. A late frame moves the schedule instead of being caught up.
 * <p>
 * On Windows it sleeps on a high-resolution waitable timer instead ({@link WaitableTimer}): one wait to within
 * {@value #TIMER_SLACK} ns of the deadline, then yields, so frames come out evenly spaced without spinning through
 * most of the last millisecond. {@code -Dlich.preciseSync=false} keeps LWJGL 2's way.
 */
public final class FrameLimiter {
    private static final long SECOND = 1_000_000_000L;
    private static final RunningAverage SLEEPS = new RunningAverage(1_000_000L);
    private static final RunningAverage YIELDS = new RunningAverage(0L);
    /** How early the waitable timer is asked to wake: its own lateness, worst case, is about this. */
    private static final long TIMER_SLACK = 500_000L;
    private static WaitableTimer timer;
    private static long nextFrame;
    private static boolean started;

    private FrameLimiter() {
    }

    public static synchronized void sync(int fps) {
        if (fps <= 0) {
            return;
        }
        if (!started) {
            start();
        }
        try {
            long left = nextFrame - System.nanoTime();
            if (timer != null) {
                if (left > TIMER_SLACK && !timer.sleep(left - TIMER_SLACK)) {
                    timer = null;
                }
            } else {
                for (long t0 = System.nanoTime(), t1; nextFrame - t0 > SLEEPS.average(); t0 = t1) {
                    Thread.sleep(1);
                    SLEEPS.add((t1 = System.nanoTime()) - t0);
                }
                SLEEPS.dampen();
            }
            for (long t0 = System.nanoTime(), t1; nextFrame - t0 > YIELDS.average(); t0 = t1) {
                Thread.yield();
                YIELDS.add((t1 = System.nanoTime()) - t0);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        nextFrame = Math.max(nextFrame + SECOND / fps, System.nanoTime());
    }

    private static void start() {
        started = true;
        nextFrame = System.nanoTime();
        timer = WaitableTimer.create();
        if (Platform.get() == Platform.WINDOWS) {
            // A sleeping thread whose sleep isn't a multiple of 10 ms makes HotSpot raise the Windows timer
            // resolution to 1 ms for the whole process, which 1 ms sleeps rely on. LWJGL 2 did the same.
            Thread timer = new Thread(() -> {
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException ignored) {
                    // Daemon: lives as long as the game.
                }
            }, "LWJGL Timer");
            timer.setDaemon(true);
            timer.start();
        }
    }

    /** The mean of the last few durations, in nanoseconds. */
    private static final class RunningAverage {
        private final long[] samples = new long[10];
        private int next;

        RunningAverage(long initial) {
            java.util.Arrays.fill(samples, initial);
        }

        void add(long value) {
            samples[next] = value;
            next = (next + 1) % samples.length;
        }

        long average() {
            long sum = 0;
            for (long sample : samples) {
                sum += sample;
            }
            return sum / samples.length;
        }

        /** Forgets old outliers slowly: one 15 ms sleep on a coarse timer shouldn't stop all sleeping. */
        void dampen() {
            if (average() > SECOND / 100) {
                for (int i = 0; i < samples.length; i++) {
                    samples[i] = samples[i] * 99 / 100;
                }
            }
        }
    }
}
