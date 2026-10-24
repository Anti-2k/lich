package org.lwjgl;

import dev.lich.compat.lwjgl2.display.Glfw;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.Platform;

import javax.swing.JOptionPane;
import java.io.IOException;
import java.util.List;

/** LWJGL 2's system helpers: the timer the game reads its clock from, URLs, alerts and the clipboard. */
public final class Sys {
    /** Milliseconds, as LWJGL 2 used on Windows: {@code Minecraft.getSystemTime()} divides by this. */
    private static final long TIMER_RESOLUTION = 1000;

    private Sys() {
    }

    /** LWJGL 3's version: the game logs it and puts it in crash reports, where it should say what really ran. */
    public static String getVersion() {
        return Version.getVersion();
    }

    public static void initialize() {
    }

    public static boolean is64Bit() {
        return org.lwjgl.system.Pointer.BITS64;
    }

    public static long getTimerResolution() {
        return TIMER_RESOLUTION;
    }

    /** Monotonic, like the high-resolution clock the game compares it with. */
    public static long getTime() {
        return System.nanoTime() / 1_000_000L;
    }

    public static void alert(String title, String message) {
        System.err.println("[LWJGL] " + title + ": " + message);
        // AWT and GLFW don't mix on macOS.
        if (Platform.get() != Platform.MACOSX && !java.awt.GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, title, JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Opens {@code url} in the browser, as the platform's own handler would. */
    public static boolean openURL(String url) {
        List<String> command = switch (Platform.get()) {
            case WINDOWS -> List.of("rundll32", "url.dll,FileProtocolHandler", url);
            case MACOSX -> List.of("open", url);
            default -> List.of("xdg-open", url);
        };
        try {
            new ProcessBuilder(command).inheritIO().start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static String getClipboard() {
        Glfw.init();
        return GLFW.glfwGetClipboardString(0L);
    }
}
