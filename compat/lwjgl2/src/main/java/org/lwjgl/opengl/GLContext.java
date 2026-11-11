package org.lwjgl.opengl;

import dev.lich.compat.lwjgl2.display.WindowBackend;
import org.lwjgl.LWJGLException;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * LWJGL 2's per-context capabilities lookup. Each LWJGL 3 {@link GLCapabilities} gets one
 * {@link ContextCapabilities} view, built the first time it's asked for. Under a {@link WindowBackend} there
 * is no OpenGL to ask: the capabilities are the ones the backend names, on every thread.
 */
public final class GLContext {
    private static final Map<GLCapabilities, ContextCapabilities> CAPABILITIES = Collections.synchronizedMap(new WeakHashMap<>());

    /** The installed backend's capabilities, with the backend they were built for. */
    private record BackendCapabilities(WindowBackend backend, ContextCapabilities capabilities) {
    }

    private static volatile BackendCapabilities backendCapabilities;

    public GLContext() {
    }

    /** The current thread's context's capabilities, or {@code null} without a current context. */
    public static ContextCapabilities getCapabilities() {
        WindowBackend backend = WindowBackend.current();
        if (backend != null) {
            BackendCapabilities cached = backendCapabilities;
            if (cached == null || cached.backend() != backend) {
                cached = new BackendCapabilities(backend, new ContextCapabilities(backend.capabilities()));
                backendCapabilities = cached;
            }
            return cached.capabilities();
        }
        GLCapabilities caps;
        try {
            caps = GL.getCapabilities();
        } catch (IllegalStateException noContext) {
            return null;
        }
        return CAPABILITIES.computeIfAbsent(caps, ContextCapabilities::new);
    }

    /** Only "no context" ({@code null}) means anything without LWJGL 2's own context objects. */
    public static synchronized void useContext(Object context) throws LWJGLException {
        useContext(context, false);
    }

    public static synchronized void useContext(Object context, boolean forwardCompatible) throws LWJGLException {
        if (context == null) {
            GL.setCapabilities(null);
            return;
        }
        throw new LWJGLException("GLContext.useContext needs LWJGL 2's own contexts; use Drawable.makeCurrent");
    }

    /** Package-private, as in LWJGL 2, where mods found it by reflection to load GL functions themselves. */
    static long getFunctionAddress(String name) {
        return GL.getFunctionProvider().getFunctionAddress(name);
    }

    static long getFunctionAddress(String[] aliases) {
        for (String alias : aliases) {
            long address = getFunctionAddress(alias);
            if (address != 0) {
                return address;
            }
        }
        return 0;
    }

    public static synchronized void loadOpenGLLibrary() throws LWJGLException {
    }

    public static synchronized void unloadOpenGLLibrary() {
    }
}
