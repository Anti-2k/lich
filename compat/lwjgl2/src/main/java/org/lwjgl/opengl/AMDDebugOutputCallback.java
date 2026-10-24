package org.lwjgl.opengl;

import org.lwjgl.PointerWrapperAbstract;

/** LWJGL 2's AMD_debug_output message callback; see {@link KHRDebugCallback}, which works the same way. */
public final class AMDDebugOutputCallback extends PointerWrapperAbstract {
    private final Handler handler;

    public interface Handler {
        void handleMessage(int id, int category, int severity, String message);
    }

    public AMDDebugOutputCallback() {
        this(AMDDebugOutputCallback::print);
    }

    public AMDDebugOutputCallback(Handler handler) {
        super(1L);
        this.handler = handler;
    }

    /** Public here (package-private in LWJGL 2) for the mirror that registers it with LWJGL 3. */
    public Handler getHandler() {
        return handler;
    }

    private static void print(int id, int category, int severity, String message) {
        System.err.println("[LWJGL] AMD_debug_output message");
        System.err.println("\tID: " + id);
        System.err.println("\tCategory: " + KHRDebugCallback.name(category,
                new int[]{0x9149, 0x914A, 0x914B, 0x914C, 0x914D, 0x914E, 0x914F, 0x9150},
                "API ERROR", "WINDOW SYSTEM", "DEPRECATION", "UNDEFINED BEHAVIOR", "PERFORMANCE", "SHADER COMPILER",
                "APPLICATION", "OTHER"));
        System.err.println("\tSeverity: " + KHRDebugCallback.name(severity, new int[]{0x9146, 0x9147, 0x9148},
                "HIGH", "MEDIUM", "LOW"));
        System.err.println("\tMessage: " + message);
    }
}
