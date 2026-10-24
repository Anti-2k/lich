package org.lwjgl.opengl;

import org.lwjgl.PointerWrapperAbstract;

/** LWJGL 2's ARB_debug_output message callback; see {@link KHRDebugCallback}, which works the same way. */
public final class ARBDebugOutputCallback extends PointerWrapperAbstract {
    private final Handler handler;

    public interface Handler {
        void handleMessage(int source, int type, int id, int severity, String message);
    }

    public ARBDebugOutputCallback() {
        this(ARBDebugOutputCallback::print);
    }

    public ARBDebugOutputCallback(Handler handler) {
        super(1L);
        this.handler = handler;
    }

    /** Public here (package-private in LWJGL 2) for the mirror that registers it with LWJGL 3. */
    public Handler getHandler() {
        return handler;
    }

    private static void print(int source, int type, int id, int severity, String message) {
        System.err.println("[LWJGL] ARB_debug_output message");
        System.err.println("\tID: " + id);
        System.err.println("\tSource: " + KHRDebugCallback.name(source, new int[]{0x8246, 0x8247, 0x8248, 0x8249, 0x824A, 0x824B},
                "API", "WINDOW SYSTEM", "SHADER COMPILER", "THIRD PARTY", "APPLICATION", "OTHER"));
        System.err.println("\tType: " + KHRDebugCallback.name(type, new int[]{0x824C, 0x824D, 0x824E, 0x824F, 0x8250, 0x8251},
                "ERROR", "DEPRECATED BEHAVIOR", "UNDEFINED BEHAVIOR", "PORTABILITY", "PERFORMANCE", "OTHER"));
        System.err.println("\tSeverity: " + KHRDebugCallback.name(severity, new int[]{0x9146, 0x9147, 0x9148},
                "HIGH", "MEDIUM", "LOW"));
        System.err.println("\tMessage: " + message);
    }
}
