package org.lwjgl.opengl;

import org.lwjgl.PointerWrapperAbstract;

/**
 * LWJGL 2's KHR_debug (and GL 4.3) message callback: a {@link Handler} for {@code glDebugMessageCallback}.
 * LWJGL 3 wants a callback object of its own, which Lich's mirror of {@code glDebugMessageCallback} makes
 * from {@link #getHandler()}. The default handler prints to {@code System.err}, like LWJGL 2's.
 */
public final class KHRDebugCallback extends PointerWrapperAbstract {
    private final Handler handler;

    public interface Handler {
        void handleMessage(int source, int type, int id, int severity, String message);
    }

    public KHRDebugCallback() {
        this(KHRDebugCallback::print);
    }

    public KHRDebugCallback(Handler handler) {
        // Not a native pointer: just non-null, so isValid() holds.
        super(1L);
        this.handler = handler;
    }

    /** Public here (package-private in LWJGL 2) for the mirror that registers it with LWJGL 3. */
    public Handler getHandler() {
        return handler;
    }

    private static void print(int source, int type, int id, int severity, String message) {
        System.err.println("[LWJGL] KHR_debug message");
        System.err.println("\tID: " + id);
        System.err.println("\tSource: " + name(source, new int[]{0x8246, 0x8247, 0x8248, 0x8249, 0x824A, 0x824B},
                "API", "WINDOW SYSTEM", "SHADER COMPILER", "THIRD PARTY", "APPLICATION", "OTHER"));
        System.err.println("\tType: " + name(type, new int[]{0x824C, 0x824D, 0x824E, 0x824F, 0x8250, 0x8251, 0x8268, 0x8269, 0x826A},
                "ERROR", "DEPRECATED BEHAVIOR", "UNDEFINED BEHAVIOR", "PORTABILITY", "PERFORMANCE", "OTHER", "MARKER",
                "PUSH GROUP", "POP GROUP"));
        System.err.println("\tSeverity: " + name(severity, new int[]{0x9146, 0x9147, 0x9148, 0x826B},
                "HIGH", "MEDIUM", "LOW", "NOTIFICATION"));
        System.err.println("\tMessage: " + message);
    }

    static String name(int value, int[] values, String... names) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return names[i];
            }
        }
        return "Unknown (0x" + Integer.toHexString(value).toUpperCase() + ")";
    }
}
