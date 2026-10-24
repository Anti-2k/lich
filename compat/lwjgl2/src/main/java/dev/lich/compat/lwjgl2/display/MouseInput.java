package dev.lich.compat.lwjgl2.display;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;

/**
 * The mouse behind LWJGL 2's {@code Mouse}, fed by GLFW's callbacks while {@code Display} processes window
 * messages. Positions are LWJGL 2's: framebuffer pixels from the bottom left. While grabbed, GLFW's
 * disabled cursor reports an unbounded virtual position, so only its movement is used, as LWJGL 2 used only
 * the deltas of a grabbed mouse; the absolute position keeps moving by those deltas and is clipped to the
 * window like LWJGL 2's. The wheel counts 120 per notch, LWJGL 2's unit on every platform.
 */
public final class MouseInput {
    public static final int BUTTON_COUNT = GLFW.GLFW_MOUSE_BUTTON_LAST + 1;
    private static final int MAX_EVENTS = 512;
    private static final int WHEEL_UNIT = 120;

    private record Event(int button, boolean state, int dx, int dy, int dwheel, int x, int y, long nanos) {
    }

    private static final ArrayDeque<Event> QUEUE = new ArrayDeque<>();
    private static final boolean[] BUTTONS = new boolean[BUTTON_COUNT];
    private static boolean created;
    private static boolean grabbed;
    private static boolean clip = true;
    private static boolean inside;
    /** The pointer in LWJGL coordinates, unclipped, and the last raw position GLFW reported, converted. */
    private static int absoluteX;
    private static int absoluteY;
    private static int lastRawX;
    private static int lastRawY;
    private static int grabX;
    private static int grabY;
    private static int dx;
    private static int dy;
    private static int dwheel;
    private static double wheelRemainder;
    private static Event current = new Event(-1, false, 0, 0, 0, 0, 0, 0);

    private MouseInput() {
    }

    public static synchronized void create() {
        created = true;
        grabbed = false;
        QUEUE.clear();
        java.util.Arrays.fill(BUTTONS, false);
        dx = dy = dwheel = 0;
        wheelRemainder = 0;
        resyncPosition();
        absoluteX = lastRawX;
        absoluteY = lastRawY;
        current = new Event(-1, false, 0, 0, 0, x(), y(), 0);
        long window = WindowState.handle();
        inside = window != 0 && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_HOVERED) == GLFW.GLFW_TRUE;
    }

    public static synchronized void destroy() {
        if (grabbed) {
            setGrabbed(false);
        }
        created = false;
        QUEUE.clear();
    }

    public static synchronized boolean isCreated() {
        return created;
    }

    private static int toLwjglX(double windowX) {
        return (int) Math.floor(windowX * WindowState.scaleX());
    }

    private static int toLwjglY(double windowY) {
        return WindowState.framebufferHeight() - 1 - (int) Math.floor(windowY * WindowState.scaleY());
    }

    /** Takes GLFW's current cursor position as the last one seen, so the next movement has no jump in it. */
    private static void resyncPosition() {
        long window = WindowState.handle();
        if (window == 0) {
            return;
        }
        double[] cx = new double[1];
        double[] cy = new double[1];
        GLFW.glfwGetCursorPos(window, cx, cy);
        lastRawX = toLwjglX(cx[0]);
        lastRawY = toLwjglY(cy[0]);
    }

    private static int clipX(int value) {
        return clip ? Math.max(0, Math.min(WindowState.framebufferWidth() - 1, value)) : value;
    }

    private static int clipY(int value) {
        return clip ? Math.max(0, Math.min(WindowState.framebufferHeight() - 1, value)) : value;
    }

    private static int x() {
        return clipX(absoluteX);
    }

    private static int y() {
        return clipY(absoluteY);
    }

    private static void offer(Event event) {
        if (QUEUE.size() < MAX_EVENTS) {
            QUEUE.add(event);
        }
    }

    /** GLFW's cursor position callback, in window coordinates from the top left. */
    public static synchronized void cursor(double windowX, double windowY) {
        if (!created) {
            return;
        }
        int rawX = toLwjglX(windowX);
        int rawY = toLwjglY(windowY);
        int moveX = rawX - lastRawX;
        int moveY = rawY - lastRawY;
        lastRawX = rawX;
        lastRawY = rawY;
        if (moveX == 0 && moveY == 0) {
            return;
        }
        dx += moveX;
        dy += moveY;
        if (grabbed) {
            absoluteX += moveX;
            absoluteY += moveY;
        } else {
            absoluteX = rawX;
            absoluteY = rawY;
        }
        offer(new Event(-1, false, moveX, moveY, 0, x(), y(), System.nanoTime()));
    }

    public static synchronized void button(int button, boolean pressed) {
        if (!created || button < 0 || button >= BUTTON_COUNT) {
            return;
        }
        BUTTONS[button] = pressed;
        offer(new Event(button, pressed, 0, 0, 0, x(), y(), System.nanoTime()));
    }

    public static synchronized void scroll(double yOffset) {
        if (!created) {
            return;
        }
        wheelRemainder += yOffset * WHEEL_UNIT;
        int amount = (int) wheelRemainder;
        wheelRemainder -= amount;
        if (amount != 0) {
            dwheel += amount;
            offer(new Event(-1, false, 0, 0, amount, x(), y(), System.nanoTime()));
        }
    }

    public static synchronized void enter(boolean entered) {
        inside = entered;
    }

    public static synchronized boolean next() {
        Event event = QUEUE.poll();
        if (event == null) {
            return false;
        }
        current = event;
        return true;
    }

    public static synchronized boolean isGrabbed() {
        return grabbed;
    }

    /**
     * Like LWJGL 2: grabbing remembers the position, ungrabbing puts the cursor back there (or where
     * {@link #setCursorPosition} moved it meanwhile), and either way pending movement and events are dropped.
     */
    public static synchronized void setGrabbed(boolean grab) {
        boolean was = grabbed;
        grabbed = grab;
        long window = WindowState.handle();
        if (!created || window == 0) {
            return;
        }
        if (grab && !was) {
            grabX = x();
            grabY = y();
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        } else if (!grab && was) {
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            moveCursor(grabX, grabY);
        }
        resyncPosition();
        if (!grab) {
            absoluteX = lastRawX;
            absoluteY = lastRawY;
        }
        dx = dy = dwheel = 0;
        QUEUE.clear();
    }

    public static synchronized void setCursorPosition(int newX, int newY) {
        absoluteX = newX;
        absoluteY = newY;
        if (grabbed) {
            grabX = newX;
            grabY = newY;
        } else {
            moveCursor(newX, newY);
        }
    }

    private static void moveCursor(int lwjglX, int lwjglY) {
        long window = WindowState.handle();
        if (window == 0) {
            return;
        }
        double windowX = lwjglX / WindowState.scaleX();
        double windowY = (WindowState.framebufferHeight() - 1 - lwjglY) / WindowState.scaleY();
        GLFW.glfwSetCursorPos(window, windowX, windowY);
        lastRawX = lwjglX;
        lastRawY = lwjglY;
    }

    public static synchronized boolean isButtonDown(int button) {
        return button >= 0 && button < BUTTON_COUNT && BUTTONS[button];
    }

    public static synchronized int getX() {
        return x();
    }

    public static synchronized int getY() {
        return y();
    }

    public static synchronized int takeDX() {
        int result = dx;
        dx = 0;
        return result;
    }

    public static synchronized int takeDY() {
        int result = dy;
        dy = 0;
        return result;
    }

    public static synchronized int takeDWheel() {
        int result = dwheel;
        dwheel = 0;
        return result;
    }

    public static synchronized boolean isInsideWindow() {
        return inside;
    }

    public static synchronized boolean isClipMouseCoordinatesToWindow() {
        return clip;
    }

    public static synchronized void setClipMouseCoordinatesToWindow(boolean clipToWindow) {
        clip = clipToWindow;
    }

    public static synchronized int eventButton() {
        return current.button();
    }

    public static synchronized boolean eventButtonState() {
        return current.state();
    }

    public static synchronized int eventDX() {
        return current.dx();
    }

    public static synchronized int eventDY() {
        return current.dy();
    }

    public static synchronized int eventX() {
        return current.x();
    }

    public static synchronized int eventY() {
        return current.y();
    }

    public static synchronized int eventDWheel() {
        return current.dwheel();
    }

    public static synchronized long eventNanoseconds() {
        return current.nanos();
    }
}
