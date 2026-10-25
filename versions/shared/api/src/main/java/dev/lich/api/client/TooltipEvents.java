package dev.lich.api.client;

import dev.lich.api.event.CancellableEvent;
import dev.lich.api.event.Event;
import dev.lich.api.hook.Hooks;

import java.util.List;

/** Hover tooltips being drawn, item tooltips and others (hook {@code render.tooltip}), with and without Forge. */
public final class TooltipEvents {
    /** A tooltip is about to be drawn at the mouse position {@code x}, {@code y}. */
    public static final Event<Pre> PRE = Event.create(Hooks.RENDER_TOOLTIP, "TooltipEvents.PRE");
    /** One line of it is about to be drawn; cancelling skips the line, so a listener can draw something else there. */
    public static final Event<Line> LINE = Event.create(Hooks.RENDER_TOOLTIP, "TooltipEvents.LINE");

    private TooltipEvents() {
    }

    public record Pre(List<String> lines, int x, int y) {
    }

    /** {@code x}, {@code y}: where the line's text starts. */
    public static final class Line extends CancellableEvent {
        private final String text;
        private final float x;
        private final float y;

        public Line(String text, float x, float y) {
            this.text = text;
            this.x = x;
            this.y = y;
        }

        public String text() {
            return text;
        }

        public float x() {
            return x;
        }

        public float y() {
            return y;
        }
    }
}
