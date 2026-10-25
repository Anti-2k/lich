package dev.lich.api.client;

import dev.lich.api.event.CancellableEvent;
import dev.lich.api.event.Event;
import dev.lich.api.hook.Hooks;
import net.minecraft.util.IChatComponent;

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
        private IChatComponent message;

        public Receive(boolean actionBar, IChatComponent message) {
            this.actionBar = actionBar;
            this.message = message;
        }

        /** Whether it goes above the hotbar (packet type 2) rather than into the chat; always false on 1.7.10. */
        public boolean actionBar() {
            return actionBar;
        }

        public IChatComponent message() {
            return message;
        }

        public void setMessage(IChatComponent message) {
            this.message = message;
        }
    }
}
