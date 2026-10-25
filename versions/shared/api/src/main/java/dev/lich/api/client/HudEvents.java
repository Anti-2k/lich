package dev.lich.api.client;

import dev.lich.api.event.CancellableEvent;
import dev.lich.api.event.Event;
import dev.lich.api.hook.Hooks;
import net.minecraft.client.gui.ScaledResolution;

import java.util.List;

/**
 * The in-game HUD (hook {@code render.hud}), the same with and without Forge: events before and after each
 * {@link HudElement}, and the F3 text.
 */
public final class HudEvents {
    /** Before an element is drawn; cancelling skips it (cancelling {@link HudElement#ALL} skips the whole HUD). */
    public static final Event<Render> PRE = Event.create(Hooks.RENDER_HUD, "HudEvents.PRE");
    /** After an element is drawn (not after a cancelled one, as with Forge's HUD events). */
    public static final Event<Render> POST = Event.create(Hooks.RENDER_HUD, "HudEvents.POST");
    /** The F3 screen's text, left and right column, while F3 is open; change the lists to change what's drawn. */
    public static final Event<DebugText> DEBUG_TEXT = Event.create(Hooks.RENDER_HUD, "HudEvents.DEBUG_TEXT");

    private HudEvents() {
    }

    /**
     * One element of one frame. {@link #top} is the y of the row a bar is drawn in (health and armour on the
     * left, food, air and mount health on the right); -1 for elements that aren't one of those bars.
     */
    public static final class Render extends CancellableEvent {
        private final HudElement element;
        private final float partialTicks;
        private final ScaledResolution resolution;
        private final int top;

        public Render(HudElement element, float partialTicks, ScaledResolution resolution, int top) {
            this.element = element;
            this.partialTicks = partialTicks;
            this.resolution = resolution;
            this.top = top;
        }

        public HudElement element() {
            return element;
        }

        public float partialTicks() {
            return partialTicks;
        }

        public ScaledResolution resolution() {
            return resolution;
        }

        public int top() {
            return top;
        }
    }

    /**
     * The F3 text: both lists are mutable, and what's in them after the listeners is drawn, one line per entry.
     * Gaps are empty or {@code null} entries (1.7.10 uses {@code null}, as Forge 1.7.10 does).
     */
    public record DebugText(List<String> left, List<String> right) {
    }
}
