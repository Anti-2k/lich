package dev.lich.compat.lwjgl2.display;

import java.util.Set;

/**
 * A renderer that takes the game window in place of OpenGL (DOCS.md §2.5): a Lich mod that draws the game
 * with another graphics API installs one before the window exists (from its {@code preLaunch} entrypoint).
 * {@code Display} then creates the window with the backend's hints and no OpenGL context, and hands it
 * every buffer swap; contexts ({@code Display}'s, {@code SharedDrawable}s, {@code Pbuffer}s) only remember
 * which thread holds them. The backend is also what {@code GLContext.getCapabilities()} reports, since no
 * driver is there to ask.
 *
 * <p>The game's own OpenGL calls are the mod's to handle: with a backend installed no thread has an OpenGL
 * context, and LWJGL 3 aborts the JVM on a GL call without one. Without a backend nothing here is used.
 */
public interface WindowBackend {
    /** Sets GLFW window hints after {@code Display}'s own, at least {@code GLFW_CLIENT_API = GLFW_NO_API}. */
    void windowHints();

    /** The window exists and is still hidden. Called on the thread that created it. */
    void windowCreated(long window);

    /** What {@code Display.update} and {@code swapBuffers} do in place of {@code glfwSwapBuffers}. */
    void swapBuffers(long window);

    /** {@code Display.setSwapInterval} and {@code setVSyncEnabled}; also called once when the window is created. */
    void setSwapInterval(int interval);

    /** The window is about to be destroyed. */
    void windowDestroyed(long window);

    /**
     * The names {@code ContextCapabilities} has as true, spelled as its fields: {@code OpenGL11},
     * {@code GL_ARB_multitexture}, ...
     */
    Set<String> capabilities();

    /** Installs the backend for the windows created from now on; {@code null} goes back to OpenGL. */
    static void install(WindowBackend backend) {
        Holder.backend = backend;
    }

    /** The installed backend, or {@code null}: the game renders with OpenGL. */
    static WindowBackend current() {
        return Holder.backend;
    }

    /** Where the installed backend is kept: an interface can't have a mutable field. */
    final class Holder {
        private static volatile WindowBackend backend;

        private Holder() {
        }
    }
}
