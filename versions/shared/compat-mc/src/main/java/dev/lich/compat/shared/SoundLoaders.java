package dev.lich.compat.shared;

import java.util.concurrent.TimeUnit;

/** Called from the patched {@code SoundManager}; see {@link SoundLoaderRace}. */
public final class SoundLoaders {
    /** Longer than paulscode's own 30 seconds, after which a stuck loader gives up by itself. */
    private static final long TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(35);

    private SoundLoaders() {
    }

    /** Waits for a sound loader thread that's still starting the sound system. */
    public static void await(Thread loader) {
        if (loader == null || loader == Thread.currentThread()) {
            return;
        }
        try {
            loader.join(TIMEOUT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
