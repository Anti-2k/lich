package dev.lich.compat.lwjgl2.display;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

/**
 * An OpenGL context: a GLFW window (visible for the display, hidden for shared drawables and pbuffers)
 * and the LWJGL 3 capabilities that go with it. LWJGL 3 keeps capabilities per thread, LWJGL 2 per context,
 * so making a context current here also makes its capabilities current, on whichever thread does it
 * (Forge's loading screen renders from a thread of its own).
 */
public final class GlContext {
    private final long window;
    private volatile GLCapabilities capabilities;

    public GlContext(long window) {
        this.window = window;
    }

    public long window() {
        return window;
    }

    public void makeCurrent() {
        GLFW.glfwMakeContextCurrent(window);
        GLCapabilities caps = capabilities;
        if (caps == null) {
            capabilities = GL.createCapabilities();
        } else {
            GL.setCapabilities(caps);
        }
    }

    public void release() {
        if (isCurrent()) {
            GLFW.glfwMakeContextCurrent(0L);
            GL.setCapabilities(null);
        }
    }

    public boolean isCurrent() {
        return GLFW.glfwGetCurrentContext() == window;
    }

    /** Destroys the window, after releasing the context if this thread has it. */
    public void destroy() {
        release();
        org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(window);
        GLFW.glfwDestroyWindow(window);
    }
}
