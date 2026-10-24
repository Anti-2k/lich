package dev.lich.compat.lwjgl2.display;

/**
 * What {@code Mouse} needs to know about the display window, kept up to date by {@code Display}: the GLFW
 * handle, and the framebuffer size with its scale against window coordinates. LWJGL 2's coordinates are
 * framebuffer pixels from the bottom left; GLFW's cursor positions are window coordinates from the top left,
 * which differ from pixels on HiDPI screens (macOS).
 */
public final class WindowState {
    private static volatile long handle;
    private static volatile int framebufferWidth;
    private static volatile int framebufferHeight;
    private static volatile double scaleX = 1;
    private static volatile double scaleY = 1;

    private WindowState() {
    }

    public static long handle() {
        return handle;
    }

    public static int framebufferWidth() {
        return framebufferWidth;
    }

    public static int framebufferHeight() {
        return framebufferHeight;
    }

    public static double scaleX() {
        return scaleX;
    }

    public static double scaleY() {
        return scaleY;
    }

    public static void update(long window, int fbWidth, int fbHeight, int windowWidth, int windowHeight) {
        handle = window;
        framebufferWidth = fbWidth;
        framebufferHeight = fbHeight;
        scaleX = windowWidth > 0 ? (double) fbWidth / windowWidth : 1;
        scaleY = windowHeight > 0 ? (double) fbHeight / windowHeight : 1;
    }

    public static void clear() {
        handle = 0;
    }
}
