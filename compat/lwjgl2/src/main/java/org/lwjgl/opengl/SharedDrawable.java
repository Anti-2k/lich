package org.lwjgl.opengl;

import dev.lich.compat.lwjgl2.display.ContextHints;
import dev.lich.compat.lwjgl2.display.GlContext;
import dev.lich.compat.lwjgl2.display.Glfw;
import dev.lich.compat.lwjgl2.display.WindowBackend;
import org.lwjgl.LWJGLException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;

/**
 * LWJGL 2's second context sharing objects with another one, used to load textures off the render
 * thread (Forge's loading screen, Essential). It's a hidden 1x1 GLFW window whose context shares with the
 * given drawable's. Under a {@link WindowBackend} it has no window and no OpenGL: only which thread holds it.
 */
public final class SharedDrawable implements Drawable {
    private GlContext context;

    public SharedDrawable(Drawable drawable) throws LWJGLException {
        GlContext shared = contextOf(drawable);
        if (shared == null) {
            throw new LWJGLException("Can only share with the Display, a SharedDrawable or a Pbuffer");
        }
        context = hiddenWindow(1, 1, null, shared);
    }

    static GlContext contextOf(Drawable drawable) {
        if (drawable == Display.getDrawable()) {
            return Display.context();
        }
        if (drawable instanceof SharedDrawable shared) {
            return shared.context;
        }
        if (drawable instanceof Pbuffer pbuffer) {
            return pbuffer.context();
        }
        return null;
    }

    /** A hidden window with a context like the display's, sharing with {@code shared} if given. */
    static GlContext hiddenWindow(int width, int height, PixelFormat format, GlContext shared) throws LWJGLException {
        if (WindowBackend.current() != null) {
            return GlContext.withoutGl(0L);
        }
        Glfw.init();
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        // Like the display's, so the contexts are compatible enough to share.
        ContextHints.apply(format != null ? format : Display.createdFormat(), Display.createdAttribs());
        long window = GLFW.glfwCreateWindow(width, height, "", 0L, shared == null ? 0L : shared.window());
        if (window == 0) {
            throw new LWJGLException("Could not create an offscreen context");
        }
        return new GlContext(window);
    }

    GlContext context() {
        checkDestroyed();
        return context;
    }

    private void checkDestroyed() {
        if (context == null) {
            throw new IllegalStateException("SharedDrawable is destroyed");
        }
    }

    @Override
    public boolean isCurrent() throws LWJGLException {
        checkDestroyed();
        return context.isCurrent();
    }

    @Override
    public void makeCurrent() throws LWJGLException {
        checkDestroyed();
        context.makeCurrent();
    }

    @Override
    public void releaseContext() throws LWJGLException {
        checkDestroyed();
        context.release();
    }

    @Override
    public void destroy() {
        if (context != null) {
            context.destroy();
            context = null;
        }
    }

    @Override
    public void setCLSharingProperties(PointerBuffer properties) throws LWJGLException {
        throw new UnsupportedOperationException("OpenCL sharing isn't supported");
    }

    public void swapBuffers() throws LWJGLException {
        checkDestroyed();
        if (context.hasGl()) {
            GLFW.glfwSwapBuffers(context.window());
        }
    }

    public void setSwapInterval(int value) {
        checkDestroyed();
        if (context.hasGl() && context.isCurrent()) {
            GLFW.glfwSwapInterval(value);
        }
    }

    public void initContext(float r, float g, float b) {
        if (context != null && context.hasGl()) {
            GL11.glClearColor(r, g, b, 0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        }
    }

    public void checkGLError() {
        if (context != null && context.hasGl()) {
            Util.checkGLError();
        }
    }
}
