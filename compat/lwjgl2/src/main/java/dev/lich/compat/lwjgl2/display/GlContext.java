package dev.lich.compat.lwjgl2.display;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

/**
 * An OpenGL context: a GLFW window (visible for the display, hidden for shared drawables and pbuffers)
 * and the LWJGL 3 capabilities that go with it. LWJGL 3 keeps capabilities per thread, LWJGL 2 per context,
 * so making a context current here also makes its capabilities current, on whichever thread does it
 * (Forge's loading screen renders from a thread of its own).
 *
 * <p>Under a {@link WindowBackend} there is no OpenGL behind it ({@link #withoutGl}): it only remembers
 * which thread made it current, so code that passes contexts between threads still sees what it expects.
 */
public final class GlContext {
    private final long window;
    private final boolean gl;
    private volatile GLCapabilities capabilities;
    /** Without OpenGL: the thread this context is current on. */
    private volatile Thread owner;

    public GlContext(long window) {
        this(window, true);
    }

    private GlContext(long window, boolean gl) {
        this.window = window;
        this.gl = gl;
    }

    /** A context for a {@link WindowBackend}'s window, or for an offscreen drawable under one ({@code window} 0). */
    public static GlContext withoutGl(long window) {
        return new GlContext(window, false);
    }

    public long window() {
        return window;
    }

    /** Whether an OpenGL context is behind this; false under a {@link WindowBackend}. */
    public boolean hasGl() {
        return gl;
    }

    public void makeCurrent() {
        if (!gl) {
            owner = Thread.currentThread();
            return;
        }
        GLFW.glfwMakeContextCurrent(window);
        GLCapabilities caps = capabilities;
        if (caps == null) {
            capabilities = GL.createCapabilities();
        } else {
            GL.setCapabilities(caps);
        }
    }

    public void release() {
        if (!gl) {
            if (owner == Thread.currentThread()) {
                owner = null;
            }
            return;
        }
        if (isCurrent()) {
            GLFW.glfwMakeContextCurrent(0L);
            GL.setCapabilities(null);
        }
    }

    public boolean isCurrent() {
        if (!gl) {
            return owner == Thread.currentThread();
        }
        return GLFW.glfwGetCurrentContext() == window;
    }

    /** Destroys the window, after releasing the context if this thread has it. */
    public void destroy() {
        release();
        if (window != 0) {
            org.lwjgl.glfw.Callbacks.glfwFreeCallbacks(window);
            GLFW.glfwDestroyWindow(window);
        }
    }
}
