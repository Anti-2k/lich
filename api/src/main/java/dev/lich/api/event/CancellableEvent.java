package dev.lich.api.event;

/** Base class for events that implement {@link Cancellable}. */
public abstract class CancellableEvent implements Cancellable {
    private boolean canceled;

    @Override
    public boolean isCanceled() {
        return canceled;
    }

    @Override
    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }
}
