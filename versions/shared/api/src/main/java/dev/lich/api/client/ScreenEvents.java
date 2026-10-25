package dev.lich.api.client;

import dev.lich.api.event.CancellableEvent;
import dev.lich.api.event.Event;
import dev.lich.api.hook.Hooks;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import java.util.List;

/** Screens (hook {@code screen}): being set up, their buttons, and the input they handle. */
public final class ScreenEvents {
    /** After a screen set up its buttons (opened or resized); the button list may be changed. */
    public static final Event<Init> INIT_POST = Event.create(Hooks.SCREEN, "ScreenEvents.INIT_POST");
    /** A button was clicked, before the screen acts on it; cancelling stops the action (the click sound has played). */
    public static final Event<Action> ACTION_PRE = Event.create(Hooks.SCREEN, "ScreenEvents.ACTION_PRE");
    /** Before the screen handles one mouse event (the LWJGL {@code Mouse} event is current); cancelling skips it. */
    public static final Event<Input> MOUSE_INPUT_PRE = Event.create(Hooks.SCREEN, "ScreenEvents.MOUSE_INPUT_PRE");
    /** After the screen handled one mouse event. */
    public static final Event<Input> MOUSE_INPUT_POST = Event.create(Hooks.SCREEN, "ScreenEvents.MOUSE_INPUT_POST");
    /** Before the screen handles one keyboard event (the LWJGL {@code Keyboard} event is current); cancelling skips it. */
    public static final Event<Input> KEYBOARD_INPUT_PRE = Event.create(Hooks.SCREEN, "ScreenEvents.KEYBOARD_INPUT_PRE");
    /** After the screen handled one keyboard event. */
    public static final Event<Input> KEYBOARD_INPUT_POST = Event.create(Hooks.SCREEN, "ScreenEvents.KEYBOARD_INPUT_POST");

    private ScreenEvents() {
    }

    public record Init(GuiScreen screen, List<GuiButton> buttons) {
    }

    public static final class Action extends CancellableEvent {
        private final GuiScreen screen;
        private final GuiButton button;

        public Action(GuiScreen screen, GuiButton button) {
            this.screen = screen;
            this.button = button;
        }

        public GuiScreen screen() {
            return screen;
        }

        public GuiButton button() {
            return button;
        }
    }

    public static final class Input extends CancellableEvent {
        private final GuiScreen screen;

        public Input(GuiScreen screen) {
            this.screen = screen;
        }

        public GuiScreen screen() {
            return screen;
        }
    }
}
