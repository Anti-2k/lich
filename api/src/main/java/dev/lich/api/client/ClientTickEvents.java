package dev.lich.api.client;

import dev.lich.api.event.Event;
import dev.lich.api.hook.Hooks;

/** The client tick, 20 times a second, in and out of a world (hook {@code client.tick}). */
public final class ClientTickEvents {
    /** Before the tick: input, the screen, the world and the player haven't been ticked yet. */
    public static final Event<Tick> START = Event.create(Hooks.CLIENT_TICK, "ClientTickEvents.START");
    /** After the tick. */
    public static final Event<Tick> END = Event.create(Hooks.CLIENT_TICK, "ClientTickEvents.END");

    private ClientTickEvents() {
    }

    /** A tick carries no data; every tick passes {@link #INSTANCE}. */
    public static final class Tick {
        public static final Tick INSTANCE = new Tick();

        private Tick() {
        }
    }
}
