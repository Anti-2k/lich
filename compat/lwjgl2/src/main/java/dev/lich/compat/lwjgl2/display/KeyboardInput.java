package dev.lich.compat.lwjgl2.display;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;

/**
 * The keyboard behind LWJGL 2's {@code Keyboard}, fed by GLFW's key and character callbacks while
 * {@code Display} processes window messages. LWJGL 2 delivered a typed character in the same event as the
 * key press that produced it (Windows sends WM_CHAR right after WM_KEYDOWN), and the game relies on that:
 * {@code GuiScreen} types {@code getEventCharacter()} on key-down events. GLFW reports them as two callbacks,
 * in that order, so a key press waits until its character (or the next callback) arrives. Characters with no
 * key press (IME input, dead keys, the second half of a surrogate pair) become events with key 0.
 * Repeat events are queued like any other and skipped on reading unless repeats are on, as in LWJGL 2.
 */
public final class KeyboardInput {
    /** LWJGL 2's keyboard buffer held this many events; more were dropped. */
    private static final int MAX_EVENTS = 512;

    private static final class Event {
        final int key;
        char character;
        final boolean state;
        final boolean repeat;
        final long nanos;

        Event(int key, char character, boolean state, boolean repeat, long nanos) {
            this.key = key;
            this.character = character;
            this.state = state;
            this.repeat = repeat;
            this.nanos = nanos;
        }
    }

    private static final ArrayDeque<Event> QUEUE = new ArrayDeque<>();
    private static final boolean[] DOWN = new boolean[256];
    private static boolean created;
    private static boolean repeatEnabled;
    /** The key press still waiting for its character, already queued. */
    private static Event pending;
    private static Event current = new Event(0, '\0', false, false, 0);

    private KeyboardInput() {
    }

    public static synchronized void create() {
        created = true;
        QUEUE.clear();
        java.util.Arrays.fill(DOWN, false);
        pending = null;
    }

    public static synchronized void destroy() {
        created = false;
        QUEUE.clear();
        pending = null;
    }

    public static synchronized boolean isCreated() {
        return created;
    }

    /** GLFW's key callback. */
    public static synchronized void key(int glfwKey, int scancode, int action) {
        if (!created) {
            return;
        }
        pending = null;
        if (glfwKey == GLFW.GLFW_KEY_UNKNOWN) {
            // Media and vendor keys GLFW has no name for; any character they type still comes, with key 0.
            return;
        }
        int key = KeyCodes.toLwjgl(glfwKey, scancode);
        boolean press = action != GLFW.GLFW_RELEASE;
        if (key > 0 && key < DOWN.length) {
            DOWN[key] = press;
        }
        Event event = new Event(key, '\0', press, action == GLFW.GLFW_REPEAT, System.nanoTime());
        if (offer(event) && press) {
            pending = event;
        }
    }

    /** GLFW's character callback: one code point, one or two UTF-16 characters. */
    public static synchronized void character(int codePoint) {
        if (!created) {
            return;
        }
        for (char c : Character.toChars(codePoint)) {
            if (pending != null && pending.character == '\0') {
                pending.character = c;
            } else {
                boolean repeat = pending != null && pending.repeat;
                offer(new Event(0, c, true, repeat, System.nanoTime()));
            }
        }
    }

    /** End of a round of window messages: a key press can't get its character any more. */
    public static synchronized void endOfMessages() {
        pending = null;
    }

    private static boolean offer(Event event) {
        if (QUEUE.size() >= MAX_EVENTS) {
            return false;
        }
        QUEUE.add(event);
        return true;
    }

    public static synchronized boolean next() {
        for (Event event; (event = QUEUE.poll()) != null; ) {
            if (event == pending) {
                pending = null;
            }
            if (event.repeat && !repeatEnabled) {
                continue;
            }
            current = event;
            return true;
        }
        return false;
    }

    public static synchronized int eventCount() {
        return QUEUE.size();
    }

    public static synchronized boolean isKeyDown(int key) {
        return key >= 0 && key < DOWN.length && DOWN[key];
    }

    public static synchronized void enableRepeatEvents(boolean enable) {
        repeatEnabled = enable;
    }

    public static synchronized boolean areRepeatEventsEnabled() {
        return repeatEnabled;
    }

    public static synchronized int eventKey() {
        return current.key;
    }

    public static synchronized char eventCharacter() {
        return current.character;
    }

    public static synchronized boolean eventState() {
        return current.state;
    }

    public static synchronized boolean eventRepeat() {
        return current.repeat;
    }

    public static synchronized long eventNanoseconds() {
        return current.nanos;
    }
}
