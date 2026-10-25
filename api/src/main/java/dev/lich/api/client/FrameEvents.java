package dev.lich.api.client;

import dev.lich.api.event.Event;
import dev.lich.api.hook.Hooks;

/**
 * Every frame the game renders, in and out of a world (hook {@code render.frame}): around the world, HUD
 * and screen drawing, which is skipped while the game shows nothing (the window minimised, for example).
 */
public final class FrameEvents {
    /** Before anything of the frame is drawn. */
    public static final Event<Frame> START = Event.create(Hooks.RENDER_FRAME, "FrameEvents.START");
    /** After the frame is drawn, before it's shown. */
    public static final Event<Frame> END = Event.create(Hooks.RENDER_FRAME, "FrameEvents.END");

    private FrameEvents() {
    }

    /** {@code partialTicks}: how far the frame is between the last tick and the next, 0 to 1. */
    public record Frame(float partialTicks) {
    }
}
