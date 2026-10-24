package dev.lich.compat.lwjgl2.display;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.Platform;

/**
 * GLFW setup for the LWJGL 2 classes: initialized once, by the first call that needs it (the game's main
 * thread, in practice). Window events can only be processed on the thread that created the window, which
 * {@code Display.create} records. GLFW errors are logged, never thrown: LWJGL 2 ignored most of what GLFW
 * reports (an unsupported window icon on macOS, say).
 */
public final class Glfw {
    static final Logger LOG = LogManager.getLogger("LWJGL");
    private static volatile boolean initialized;
    private static volatile Thread eventThread;
    @SuppressWarnings("unused") // Kept reachable: GLFW holds only a native pointer to it.
    private static GLFWErrorCallback errors;

    private Glfw() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        if (Platform.get() == Platform.MACOSX && System.getenv("JAVA_STARTED_ON_FIRST_THREAD_" + ProcessHandle.current().pid()) == null
                && Configuration.GLFW_LIBRARY_NAME.get() == null) {
            // Without -XstartOnFirstThread, Cocoa calls must be sent to the main thread: GLFW's async build does that.
            Configuration.GLFW_LIBRARY_NAME.set("glfw_async");
        }
        errors = GLFWErrorCallback.create((error, description) ->
                LOG.warn("GLFW error 0x{}: {}", Integer.toHexString(error), GLFWErrorCallback.getDescription(description)));
        GLFW.glfwSetErrorCallback(errors);
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }
        initialized = true;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /** Called by {@code Display.create}: the window's events belong to this thread. */
    public static void setEventThread(Thread thread) {
        eventThread = thread;
    }

    /** Whether the current thread created the window, so it can process the window's events. */
    public static boolean isEventThread() {
        return Thread.currentThread() == eventThread;
    }
}
