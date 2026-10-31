package org.lwjgl.input;

import dev.lich.compat.lwjgl2.display.MouseInput;
import dev.lich.compat.lwjgl2.display.WindowState;
import org.lwjgl.LWJGLException;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.Display;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * LWJGL 2's mouse, on GLFW (see {@link MouseInput} for coordinates, grabbing and the wheel). Button names,
 * the exceptions for using it before {@link #create} and the native cursor handling are LWJGL 2's.
 *
 * <p>The position the getters return lives in LWJGL 2's own private fields, {@code x}, {@code y}, {@code event_x}
 * and {@code event_y}, refreshed when LWJGL 2 refreshed them: {@link #poll} (from {@code Display.update}), {@link #next},
 * {@link #setCursorPosition} and grabbing. Essential sets them by reflection to move the position the game sees while
 * it draws an overlay, which then holds until the next refresh, as on LWJGL 2. Likewise {@code readBuffer} holds the
 * events {@link #next} hasn't returned yet, in LWJGL 2's layout: TC4Tweak checks it for pending events.
 *
 * <p>The class declares all of LWJGL 2.9's fields, by name and in its order, because mods index
 * {@code getDeclaredFields()}: Witching Gadgets takes element 17 for {@code readBuffer}. Those this class doesn't use
 * keep their defaults.
 */
@SuppressWarnings("unused")
public class Mouse {
    public static final int EVENT_SIZE = 1 + 1 + 4 + 4 + 4 + 8;

    private static boolean created;
    private static ByteBuffer buttons;
    private static int x;
    private static int y;
    private static int absolute_x;
    private static int absolute_y;
    private static IntBuffer coord_buffer;
    private static int dx;
    private static int dy;
    private static int dwheel;
    private static int buttonCount = -1;
    private static boolean hasWheel;
    private static Cursor currentCursor;
    private static String[] buttonName = new String[16];
    private static final Map<String, Integer> buttonMap = new HashMap<>(16);
    private static boolean initialized;
    // Not final, direct and in native order, as LWJGL 2 made it (BufferUtils).
    private static ByteBuffer readBuffer = ByteBuffer.allocateDirect(EVENT_SIZE * MouseInput.MAX_EVENTS)
            .order(ByteOrder.nativeOrder());
    private static int eventButton;
    private static boolean eventState;
    private static int event_dx;
    private static int event_dy;
    private static int event_dwheel;
    private static int event_x;
    private static int event_y;
    private static long event_nanos;
    private static int grab_x;
    private static int grab_y;
    private static int last_event_raw_x;
    private static int last_event_raw_y;
    private static final int BUFFER_SIZE = 50;
    private static boolean isGrabbed;
    /** LWJGL 2's {@code org.lwjgl.opengl.InputImplementation}, which Lich doesn't have. */
    private static Object implementation;
    private static final boolean emulateCursorAnimation = false;
    private static boolean clipMouseCoordinatesToWindow = true;

    static {
        for (int i = 0; i < buttonName.length; i++) {
            buttonName[i] = "BUTTON" + i;
            buttonMap.put(buttonName[i], i);
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
        x = event_x = newX;
        y = event_y = newY;
    }

    public static void create() throws LWJGLException {
        if (!Display.isCreated()) {
            throw new IllegalStateException("Display must be created.");
        }
        MouseInput.create();
        refresh();
        event_x = x;
        event_y = y;
    }

    public static boolean isCreated() {
        return MouseInput.isCreated();
    }

    public static void destroy() {
        MouseInput.destroy();
        currentCursor = null;
    }

    /** Events arrive while {@code Display} processes messages; this only takes the position they left. */
    public static void poll() {
        if (!isCreated()) {
            throw new IllegalStateException("Mouse must be created before you can poll it");
        }
        refresh();
    }

    private static void refresh() {
        x = MouseInput.getX();
        y = MouseInput.getY();
        MouseInput.writePending(readBuffer);
    }

    public static boolean isButtonDown(int button) {
        if (!isCreated()) {
            throw new IllegalStateException("Mouse must be created before you can poll the button state.");
        }
        return MouseInput.isButtonDown(button);
    }

    public static String getButtonName(int button) {
        return button >= 0 && button < buttonName.length ? buttonName[button] : null;
    }

    public static int getButtonIndex(String name) {
        Integer index = buttonMap.get(name);
        return index == null ? -1 : index;
    }

    public static boolean next() {
        if (!isCreated()) {
            throw new IllegalStateException("Mouse must be created before you can read events");
        }
        if (!MouseInput.next()) {
            return false;
        }
        event_x = MouseInput.eventX();
        event_y = MouseInput.eventY();
        MouseInput.writePending(readBuffer);
        return true;
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
        return event_x;
    }

    public static int getEventY() {
        return event_y;
    }

    public static int getEventDWheel() {
        return MouseInput.eventDWheel();
    }

    public static long getEventNanoseconds() {
        return MouseInput.eventNanoseconds();
    }

    public static int getX() {
        return x;
    }

    public static int getY() {
        return y;
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
        if (isCreated()) {
            refresh();
            event_x = x;
            event_y = y;
        }
    }

    /** Animated cursors aren't supported, so there's no frame to advance. */
    public static void updateCursor() {
    }

    public static boolean isInsideWindow() {
        return MouseInput.isInsideWindow();
    }
}
