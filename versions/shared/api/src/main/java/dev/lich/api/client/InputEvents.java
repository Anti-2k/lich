package dev.lich.api.client;

import dev.lich.api.event.CancellableEvent;
import dev.lich.api.event.Event;
import dev.lich.api.hook.Hooks;

/** Input while no screen takes it (hook {@code input.mouse}). */
public final class InputEvents {
    /**
     * One mouse event in game, before the game acts on it (attacking, using, scrolling the hotbar, key
     * bindings); cancelling makes the game skip it. The LWJGL {@code Mouse} event is current while listeners run.
     */
    public static final Event<Mouse> MOUSE = Event.create(Hooks.INPUT_MOUSE, "InputEvents.MOUSE");

    private InputEvents() {
    }

    /** {@code button} is -1 for movement and scrolling; {@code wheel} counts 120 per notch, as LWJGL 2 does. */
    public static final class Mouse extends CancellableEvent {
        private final int button;
        private final boolean pressed;
        private final int wheel;
        private final int dx;
        private final int dy;

        public Mouse(int button, boolean pressed, int wheel, int dx, int dy) {
            this.button = button;
            this.pressed = pressed;
            this.wheel = wheel;
            this.dx = dx;
            this.dy = dy;
        }

        public int button() {
            return button;
        }

        public boolean pressed() {
            return pressed;
        }

        public int wheel() {
            return wheel;
        }

        public int dx() {
            return dx;
        }

        public int dy() {
            return dy;
        }
    }
}
