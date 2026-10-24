package dev.lich.compat.lwjgl2.display;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.Platform;

/**
 * GLFW key → LWJGL 2 key code (LWJGL 2 used DirectInput scan codes as its key codes). GLFW names printable
 * keys by their position on a US keyboard, while LWJGL 2 on Windows and Linux named them by what the active
 * layout prints: on a German keyboard the key labelled Z is {@code KEY_Z} there, though GLFW calls it
 * {@code GLFW_KEY_Y}. So printable keys are mapped by the layout's character first. macOS LWJGL 2 was
 * positional, like GLFW.
 */
public final class KeyCodes {
    private static final int[] BY_GLFW_KEY = new int[GLFW.GLFW_KEY_LAST + 1];
    private static final int[] BY_CHARACTER = new int[128];
    private static final boolean LAYOUT_NAMES = Platform.get() != Platform.MACOSX;

    static {
        map(GLFW.GLFW_KEY_SPACE, 57, ' ');
        map(GLFW.GLFW_KEY_APOSTROPHE, 40, '\'');
        map(GLFW.GLFW_KEY_COMMA, 51, ',');
        map(GLFW.GLFW_KEY_MINUS, 12, '-');
        map(GLFW.GLFW_KEY_PERIOD, 52, '.');
        map(GLFW.GLFW_KEY_SLASH, 53, '/');
        map(GLFW.GLFW_KEY_0, 11, '0');
        for (int i = 1; i <= 9; i++) {
            map(GLFW.GLFW_KEY_1 + i - 1, 1 + i, (char) ('0' + i));
        }
        map(GLFW.GLFW_KEY_SEMICOLON, 39, ';');
        map(GLFW.GLFW_KEY_EQUAL, 13, '=');
        int[] letters = {30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44};
        for (int i = 0; i < 26; i++) {
            map(GLFW.GLFW_KEY_A + i, letters[i], (char) ('a' + i));
        }
        map(GLFW.GLFW_KEY_LEFT_BRACKET, 26, '[');
        map(GLFW.GLFW_KEY_BACKSLASH, 43, '\\');
        map(GLFW.GLFW_KEY_RIGHT_BRACKET, 27, ']');
        map(GLFW.GLFW_KEY_GRAVE_ACCENT, 41, '`');
        // The extra key next to left shift on ISO keyboards: DIK_OEM_102, which LWJGL 2 has no name for.
        map(GLFW.GLFW_KEY_WORLD_1, 86, '\0');
        map(GLFW.GLFW_KEY_WORLD_2, 0, '\0');

        map(GLFW.GLFW_KEY_ESCAPE, 1, '\0');
        map(GLFW.GLFW_KEY_ENTER, 28, '\0');
        map(GLFW.GLFW_KEY_TAB, 15, '\0');
        map(GLFW.GLFW_KEY_BACKSPACE, 14, '\0');
        map(GLFW.GLFW_KEY_INSERT, 210, '\0');
        map(GLFW.GLFW_KEY_DELETE, 211, '\0');
        map(GLFW.GLFW_KEY_RIGHT, 205, '\0');
        map(GLFW.GLFW_KEY_LEFT, 203, '\0');
        map(GLFW.GLFW_KEY_DOWN, 208, '\0');
        map(GLFW.GLFW_KEY_UP, 200, '\0');
        map(GLFW.GLFW_KEY_PAGE_UP, 201, '\0');
        map(GLFW.GLFW_KEY_PAGE_DOWN, 209, '\0');
        map(GLFW.GLFW_KEY_HOME, 199, '\0');
        map(GLFW.GLFW_KEY_END, 207, '\0');
        map(GLFW.GLFW_KEY_CAPS_LOCK, 58, '\0');
        map(GLFW.GLFW_KEY_SCROLL_LOCK, 70, '\0');
        map(GLFW.GLFW_KEY_NUM_LOCK, 69, '\0');
        map(GLFW.GLFW_KEY_PRINT_SCREEN, 183, '\0');
        map(GLFW.GLFW_KEY_PAUSE, 197, '\0');
        for (int i = 0; i < 10; i++) {
            map(GLFW.GLFW_KEY_F1 + i, 59 + i, '\0');
        }
        map(GLFW.GLFW_KEY_F11, 87, '\0');
        map(GLFW.GLFW_KEY_F12, 88, '\0');
        for (int i = 0; i < 6; i++) {
            map(GLFW.GLFW_KEY_F13 + i, 100 + i, '\0');
        }
        map(GLFW.GLFW_KEY_F19, 113, '\0');
        int[] keypad = {82, 79, 80, 81, 75, 76, 77, 71, 72, 73};
        for (int i = 0; i < 10; i++) {
            map(GLFW.GLFW_KEY_KP_0 + i, keypad[i], '\0');
        }
        map(GLFW.GLFW_KEY_KP_DECIMAL, 83, '\0');
        map(GLFW.GLFW_KEY_KP_DIVIDE, 181, '\0');
        map(GLFW.GLFW_KEY_KP_MULTIPLY, 55, '\0');
        map(GLFW.GLFW_KEY_KP_SUBTRACT, 74, '\0');
        map(GLFW.GLFW_KEY_KP_ADD, 78, '\0');
        map(GLFW.GLFW_KEY_KP_ENTER, 156, '\0');
        map(GLFW.GLFW_KEY_KP_EQUAL, 141, '\0');
        map(GLFW.GLFW_KEY_LEFT_SHIFT, 42, '\0');
        map(GLFW.GLFW_KEY_LEFT_CONTROL, 29, '\0');
        map(GLFW.GLFW_KEY_LEFT_ALT, 56, '\0');
        map(GLFW.GLFW_KEY_LEFT_SUPER, 219, '\0');
        map(GLFW.GLFW_KEY_RIGHT_SHIFT, 54, '\0');
        map(GLFW.GLFW_KEY_RIGHT_CONTROL, 157, '\0');
        map(GLFW.GLFW_KEY_RIGHT_ALT, 184, '\0');
        map(GLFW.GLFW_KEY_RIGHT_SUPER, 220, '\0');
        map(GLFW.GLFW_KEY_MENU, 221, '\0');
    }

    private KeyCodes() {
    }

    private static void map(int glfwKey, int lwjglKey, char character) {
        BY_GLFW_KEY[glfwKey] = lwjglKey;
        if (character != '\0') {
            BY_CHARACTER[character] = lwjglKey;
        }
    }

    /** The LWJGL 2 key code for a GLFW key event, or 0 ({@code KEY_NONE}) for keys LWJGL 2 didn't know. */
    public static int toLwjgl(int glfwKey, int scancode) {
        if (glfwKey < 0 || glfwKey >= BY_GLFW_KEY.length) {
            return 0;
        }
        if (LAYOUT_NAMES && isPrintable(glfwKey) && Glfw.isInitialized()) {
            String name = GLFW.glfwGetKeyName(glfwKey, scancode);
            if (name != null && name.length() == 1) {
                char c = Character.toLowerCase(name.charAt(0));
                // Windows keeps the digit row's key codes on every layout (AZERTY prints "&é\"'..." there).
                boolean digitRow = glfwKey >= GLFW.GLFW_KEY_0 && glfwKey <= GLFW.GLFW_KEY_9;
                if (c < BY_CHARACTER.length && BY_CHARACTER[c] != 0 && (!digitRow || Character.isDigit(c))) {
                    return BY_CHARACTER[c];
                }
            }
        }
        return BY_GLFW_KEY[glfwKey];
    }

    private static boolean isPrintable(int glfwKey) {
        return glfwKey >= GLFW.GLFW_KEY_APOSTROPHE && glfwKey <= GLFW.GLFW_KEY_GRAVE_ACCENT
                || glfwKey == GLFW.GLFW_KEY_WORLD_1 || glfwKey == GLFW.GLFW_KEY_WORLD_2;
    }
}
