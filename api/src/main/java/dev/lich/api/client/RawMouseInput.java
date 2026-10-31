package dev.lich.api.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Raw mouse input for grabbed movement (in game, not in menus): the mouse's own counts instead of the cursor's
 * movement, so the OS's pointer speed and acceleration ("Enhance pointer precision" on Windows) no longer apply.
 * Off by default, as on LWJGL 2. Changes how far a given hand movement turns the camera, never anything the server
 * sees beyond the rotation the player picks. Client thread only.
 * <p>
 * Only Lich's LWJGL 3 has it; under {@code -Dlich.lwjgl=2} {@link #isSupported()} is false and the rest does
 * nothing. The LWJGL 2 layer is found through {@code org.lwjgl.input.Mouse}'s class loader, which is the copy the
 * game uses ({@code org.lwjgl.} is excluded from LaunchClassLoader; this class may not be).
 */
public final class RawMouseInput {
    private static final MethodHandle SUPPORTED, ENABLED, SET;

    static {
        MethodHandle supported = null, enabled = null, set = null;
        try {
            ClassLoader loader = Class.forName("org.lwjgl.input.Mouse").getClassLoader();
            Class<?> input = Class.forName("dev.lich.compat.lwjgl2.display.MouseInput", true, loader);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            supported = lookup.findStatic(input, "isRawMotionSupported", MethodType.methodType(boolean.class));
            enabled = lookup.findStatic(input, "isRawMotion", MethodType.methodType(boolean.class));
            set = lookup.findStatic(input, "setRawMotion", MethodType.methodType(void.class, boolean.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            // LWJGL 2 runs (or an older Lich layer): no raw input.
        }
        SUPPORTED = supported;
        ENABLED = enabled;
        SET = set;
    }

    private RawMouseInput() {
    }

    /** Whether raw input is available: Lich's LWJGL 3 runs, and the platform reports raw mouse motion. */
    public static boolean isSupported() {
        try {
            return SUPPORTED != null && (boolean) SUPPORTED.invokeExact();
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean isEnabled() {
        try {
            return ENABLED != null && (boolean) ENABLED.invokeExact();
        } catch (Throwable e) {
            return false;
        }
    }

    /** Turns raw input on or off; it applies straight away, and stays set across grabs and window changes. */
    public static void setEnabled(boolean enabled) {
        if (SET == null) {
            return;
        }
        try {
            SET.invokeExact(enabled);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}
