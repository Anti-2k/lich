package dev.lich.api.event;

/**
 * An event whose listeners can stop what the game was about to do. What cancelling means is documented
 * on each event; after it, later listeners only see the event if they registered to receive cancelled ones.
 */
public interface Cancellable {
    boolean isCanceled();

    void setCanceled(boolean canceled);

    default void cancel() {
        setCanceled(true);
    }
}
