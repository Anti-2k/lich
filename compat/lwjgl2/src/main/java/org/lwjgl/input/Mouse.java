package org.lwjgl.input;

import dev.lich.compat.lwjgl2.display.MouseInput;
import dev.lich.compat.lwjgl2.display.WindowState;
import org.lwjgl.LWJGLException;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.Display;

import java.util.HashMap;
import java.util.Map;

/**
 * LWJGL 2's mouse, on GLFW (see {@link MouseInput} for coordinates, grabbing and the wheel). Button names,
 * the exceptions for using it before {@link #create} and the native cursor handling are LWJGL 2's.
 */
public class Mouse {
    public static final int EVENT_SIZE = 1 + 1 + 4 + 4 + 4 + 8;

    private static final String[] BUTTON_NAMES = new String[16];
    private static final Map<String, Integer> BUTTON_INDICES = new HashMap<>(16);
    private static Cursor currentCursor;

    static {
        for (int i = 0; i < BUTTON_NAMES.length; i++) {
            BUTTON_NAMES[i] = "BUTTON" + i;
            BUTTON_INDICES.put(BUTTON_NAMES[i], i);
        }
    }

    public Mouse() {
    }

    public static Cursor getNativeCursor() {
        return currentCursor;
    }

    /** Sets the cursor shown over the window ({@code null}: the system's), and returns the previous one. */
    public static Cursor setNativeCursor(Cursor cursor) throws LWJGLException {
        if (!isCreated()) {
            throw new IllegalStateException("Create the Mouse before setting the native cursor");
        }
        Cursor old = currentCursor;
        currentCursor = cursor;
        GLFW.glfwSetCursor(WindowState.handle(), cursor == null ? 0L : cursor.handle());
        return old;
    }

    public static boolean isClipMouseCoordinatesToWindow() {
        return MouseInput.isClipMouseCoordinatesToWindow();
    }

    public static void setClipMouseCoordinatesToWindow(boolean clip) {
        MouseInput.setClipMouseCoordinatesToWindow(clip);
    }

    public static void setCursorPosition(int newX, int newY) {
        if (!isCreated()) {
            throw new IllegalStateException("Mouse is not created");
        }
        MouseInput.setCursorPosition(newX, newY);
    }

    public static void create() throws LWJGLException {
        if (!Display.isCreated()) {
            throw new IllegalStateException("Display must be created.");
        }
        MouseInput.create();
    }

    public static boolean isCreated() {
        return MouseInput.isCreated();
    }

    public static void destroy() {
        MouseInput.destroy();
        currentCursor = null;
    }

    /** Events arrive while {@code Display} processes messages; there's nothing left to fetch here. */
    public static void poll() {
        if (!isCreated()) {
            throw new IllegalStateException("Mouse must be created before you can poll it");
        }
    }

    public static boolean isButtonDown(int button) {
        if (!isCreated()) {
            throw new IllegalStateException("Mouse must be created before you can poll the button state.");
        }
        return MouseInput.isButtonDown(button);
    }

    public static String getButtonName(int button) {
        return button >= 0 && button < BUTTON_NAMES.length ? BUTTON_NAMES[button] : null;
    }

    public static int getButtonIndex(String buttonName) {
        Integer index = BUTTON_INDICES.get(buttonName);
        return index == null ? -1 : index;
    }

    public static boolean next() {
        if (!isCreated()) {
            throw new IllegalStateException("Mouse must be created before you can read events");
        }
        return MouseInput.next();
    }

    public static int getEventButton() {
        return MouseInput.eventButton();
    }

    public static boolean getEventButtonState() {
        return MouseInput.eventButtonState();
    }

    public static int getEventDX() {
        return MouseInput.eventDX();
    }

    public static int getEventDY() {
        return MouseInput.eventDY();
    }

    public static int getEventX() {
        return MouseInput.eventX();
    }

    public static int getEventY() {
        return MouseInput.eventY();
    }

    public static int getEventDWheel() {
        return MouseInput.eventDWheel();
    }

    public static long getEventNanoseconds() {
        return MouseInput.eventNanoseconds();
    }

    public static int getX() {
        return MouseInput.getX();
    }

    public static int getY() {
        return MouseInput.getY();
    }

    public static int getDX() {
        return MouseInput.takeDX();
    }

    public static int getDY() {
        return MouseInput.takeDY();
    }

    public static int getDWheel() {
        return MouseInput.takeDWheel();
    }

    public static int getButtonCount() {
        return MouseInput.BUTTON_COUNT;
    }

    public static boolean hasWheel() {
        return true;
    }

    public static boolean isGrabbed() {
        return MouseInput.isGrabbed();
    }

    public static void setGrabbed(boolean grab) {
        MouseInput.setGrabbed(grab);
    }

    /** Animated cursors aren't supported, so there's no frame to advance. */
    public static void updateCursor() {
    }

    public static boolean isInsideWindow() {
        return MouseInput.isInsideWindow();
    }
}
