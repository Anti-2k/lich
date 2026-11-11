package org.lwjgl.opengl;

import dev.lich.compat.lwjgl2.display.GlContext;
import org.lwjgl.LWJGLException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;

/**
 * LWJGL 2's offscreen context. GLFW has no pbuffers; a hidden window of the same size does the job for
 * what mods used them for (an offscreen context to test or render in). Render-to-texture isn't supported.
 */
public final class Pbuffer implements Drawable {
    public static final int PBUFFER_SUPPORTED = 1 << 0;
    public static final int RENDER_TEXTURE_SUPPORTED = 1 << 1;
    public static final int RENDER_TEXTURE_RECTANGLE_SUPPORTED = 1 << 2;
    public static final int RENDER_DEPTH_TEXTURE_SUPPORTED = 1 << 3;
    public static final int MIPMAP_LEVEL = 0x207B;
    public static final int CUBE_MAP_FACE = 0x207C;
    public static final int TEXTURE_CUBE_MAP_POSITIVE_X = 0x207D;
    public static final int TEXTURE_CUBE_MAP_NEGATIVE_X = 0x207E;
    public static final int TEXTURE_CUBE_MAP_POSITIVE_Y = 0x207F;
    public static final int TEXTURE_CUBE_MAP_NEGATIVE_Y = 0x2080;
    public static final int TEXTURE_CUBE_MAP_POSITIVE_Z = 0x2081;
    public static final int TEXTURE_CUBE_MAP_NEGATIVE_Z = 0x2082;
    public static final int FRONT_LEFT_BUFFER = 0x2083;
    public static final int FRONT_RIGHT_BUFFER = 0x2084;
    public static final int BACK_LEFT_BUFFER = 0x2085;
    public static final int BACK_RIGHT_BUFFER = 0x2086;
    public static final int DEPTH_BUFFER = 0x2087;

    private final int width;
    private final int height;
    private GlContext context;

    public Pbuffer(int width, int height, PixelFormat pixelFormat, Drawable sharedDrawable) throws LWJGLException {
        this.width = width;
        this.height = height;
        GlContext shared = sharedDrawable == null ? null : SharedDrawable.contextOf(sharedDrawable);
        context = SharedDrawable.hiddenWindow(width, height, pixelFormat, shared);
    }

    public static int getCapabilities() {
        return PBUFFER_SUPPORTED;
    }

    GlContext context() {
        checkDestroyed();
        return context;
    }

    private void checkDestroyed() {
        if (context == null) {
            throw new IllegalStateException("Pbuffer is destroyed");
        }
    }

    public synchronized boolean isBufferLost() {
        return false;
    }

    public synchronized void setAttrib(int attrib, int value) {
        throw new UnsupportedOperationException("Render-to-texture pbuffers aren't supported");
    }

    public synchronized void bindTexImage(int buffer) {
        throw new UnsupportedOperationException("Render-to-texture pbuffers aren't supported");
    }

    public synchronized void releaseTexImage(int buffer) {
        throw new UnsupportedOperationException("Render-to-texture pbuffers aren't supported");
    }

    public synchronized int getHeight() {
        return height;
    }

    public synchronized int getWidth() {
        return width;
    }

    @Override
    public boolean isCurrent() throws LWJGLException {
        return context().isCurrent();
    }

    @Override
    public void makeCurrent() throws LWJGLException {
        context().makeCurrent();
    }

    @Override
    public void releaseContext() throws LWJGLException {
        context().release();
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
        if (context().hasGl()) {
            GLFW.glfwSwapBuffers(context().window());
        }
    }

    public void setSwapInterval(int value) {
        if (context().hasGl() && context().isCurrent()) {
            GLFW.glfwSwapInterval(value);
        }
    }

    public void initContext(float r, float g, float b) {
        if (context().hasGl()) {
            GL11.glClearColor(r, g, b, 0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        }
    }

    public void checkGLError() {
        if (context().hasGl()) {
            Util.checkGLError();
        }
    }
}
