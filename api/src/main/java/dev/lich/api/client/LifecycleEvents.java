package dev.lich.api.client;

import dev.lich.api.event.Event;

/** The game starting and stopping. Always available: every Lich mod gets the lifecycle hook. */
public final class LifecycleEvents {
    /**
     * The game is shutting down normally (quit from the menu or the window closed), on the client thread,
     * before it releases the window and the sound system. Not fired on a crash or a forced exit.
     */
    public static final Event<Shutdown> SHUTDOWN = Event.create(null, "LifecycleEvents.SHUTDOWN");

    private LifecycleEvents() {
    }

    /** Shutting down carries no data; it passes {@link #INSTANCE}. */
    public static final class Shutdown {
        public static final Shutdown INSTANCE = new Shutdown();

        private Shutdown() {
        }
    }
}
