package dev.lich.compat.lwjgl2.display;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaitableTimerTest {
    @Test
    @EnabledOnOs(OS.WINDOWS)
    // It measures the machine's timer, not Lich: a shared CI VM can't promise sub-millisecond wake-ups (the release
    // job's Windows runner missed the median by a little), so it runs on real machines only.
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
    void wakesCloseToTheDeadline() {
        WaitableTimer timer = WaitableTimer.create();
        assertNotNull(timer, "high-resolution waitable timer unavailable");
        long[] lateness = new long[50];
        for (int i = 0; i < lateness.length; i++) {
            long start = System.nanoTime();
            assertTrue(timer.sleep(1_500_000L));
            lateness[i] = System.nanoTime() - start - 1_500_000L;
        }
        Arrays.sort(lateness);
        // Never early by more than a tick of rounding, and the median within a millisecond (Thread.sleep(1) on a
        // 15.6 ms default timer resolution would be ~14 ms late).
        assertTrue(lateness[0] > -200_000L, "woke early: " + lateness[0] + " ns");
        assertTrue(lateness[lateness.length / 2] < 1_000_000L, "median lateness " + lateness[lateness.length / 2] + " ns");
    }
}
