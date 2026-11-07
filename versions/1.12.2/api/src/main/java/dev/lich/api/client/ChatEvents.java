package dev.lich.api.client;

import dev.lich.api.event.CancellableEvent;
import dev.lich.api.event.Event;
import dev.lich.api.hook.Hooks;
import net.minecraft.util.text.ITextComponent;

/** Messages from the server (hook {@code chat.receive}). */
public final class ChatEvents {
    /**
     * A chat or action bar message, right before it's shown (with Forge, after Forge mods have had it): set
     * another message to show that instead, or cancel to show nothing. Only changes what this client shows.
     */
    public static final Event<Receive> RECEIVE = Event.create(Hooks.CHAT_RECEIVE, "ChatEvents.RECEIVE");

    private ChatEvents() {
    }

    public static final class Receive extends CancellableEvent {
        private final boolean actionBar;
        private ITextComponent message;

        public Receive(boolean actionBar, ITextComponent message) {
            this.actionBar = actionBar;
            this.message = message;
        }

        /** Whether it goes above the hotbar (chat type GAME_INFO) rather than into the chat. */
        public boolean actionBar() {
            return actionBar;
        }

        public ITextComponent message() {
            return message;
        }

        public void setMessage(ITextComponent message) {
            this.message = message;
        }
    }
}
