package dev.lich.api.event;

import dev.lich.api.hook.Hooks;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * One kind of event, with its listeners. Listeners run on the thread that fires the event (the client
 * thread for every event Lich fires), highest {@link EventPriority} first and, within a priority, in the
 * order they were registered. Once a {@link Cancellable} event is cancelled, only the listeners registered
 * with {@link #registerReceivingCanceled} still see it.
 *
 * <p>Registering and unregistering may happen from any thread, also while the event fires; firing only
 * reads an array, so a game hook with no listeners costs one field read ({@link #hasListeners}).
 *
 * <p>Events that belong to a hook ({@link Hooks}) only fire if Lich wove that hook, which it does when a
 * loaded mod lists it in its {@code lich.mod.json}; registering for one nobody listed fails, naming the
 * mod and the hook.
 */
public final class Event<E> {
    private record Registration<E>(Consumer<? super E> listener, EventPriority priority, boolean receiveCanceled) {
    }

    @SuppressWarnings("rawtypes")
    private static final Registration[] NONE = new Registration[0];

    private final String hook;
    private final String name;
    @SuppressWarnings("unchecked")
    private volatile Registration<E>[] registrations = NONE;

    private Event(String hook, String name) {
        this.hook = hook;
        this.name = name;
    }

    /** An event of {@code hook} ({@code null} for one that always fires), called {@code name} in messages. */
    public static <E> Event<E> create(String hook, String name) {
        return new Event<>(hook, name);
    }

    /** The hook this event belongs to, or {@code null}. */
    public String hook() {
        return hook;
    }

    /** Whether anything listens: game hooks check this before they build the event. */
    public boolean hasListeners() {
        return registrations.length != 0;
    }

    public void register(Consumer<? super E> listener) {
        register(EventPriority.NORMAL, listener);
    }

    public void register(EventPriority priority, Consumer<? super E> listener) {
        add(new Registration<>(listener, priority, false));
    }

    /** Registers a listener that also sees the event after another listener cancelled it. */
    public void registerReceivingCanceled(EventPriority priority, Consumer<? super E> listener) {
        add(new Registration<>(listener, priority, true));
    }

    private synchronized void add(Registration<E> registration) {
        if (hook != null) {
            Hooks.require(hook, name);
        }
        Registration<E>[] current = registrations;
        Registration<E>[] updated = Arrays.copyOf(current, current.length + 1);
        // After every registration of the same or a higher priority.
        int at = current.length;
        while (at > 0 && updated[at - 1].priority().compareTo(registration.priority()) > 0) {
            updated[at] = updated[at - 1];
            at--;
        }
        updated[at] = registration;
        registrations = updated;
    }

    /** Removes {@code listener} (the same object that was registered); returns whether it was registered. */
    public synchronized boolean unregister(Consumer<? super E> listener) {
        Registration<E>[] current = registrations;
        for (int i = 0; i < current.length; i++) {
            if (current[i].listener() == listener) {
                Registration<E>[] updated = Arrays.copyOf(current, current.length - 1);
                System.arraycopy(current, i + 1, updated, i, current.length - i - 1);
                registrations = updated;
                return true;
            }
        }
        return false;
    }

    /** Runs the listeners on {@code event} and returns it, so the caller can read what they changed. */
    public E fire(E event) {
        Registration<E>[] current = registrations;
        Cancellable cancellable = event instanceof Cancellable c ? c : null;
        for (Registration<E> registration : current) {
            if (cancellable == null || !cancellable.isCanceled() || registration.receiveCanceled()) {
                registration.listener().accept(event);
            }
        }
        return event;
    }

    @Override
    public String toString() {
        return name;
    }
}
