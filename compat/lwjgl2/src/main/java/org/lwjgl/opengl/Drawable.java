package org.lwjgl.opengl;

import org.lwjgl.LWJGLException;
import org.lwjgl.PointerBuffer;

/** LWJGL 2's handle on an OpenGL context: the display's, a {@link SharedDrawable}'s or a {@link Pbuffer}'s. */
public interface Drawable {
    boolean isCurrent() throws LWJGLException;

    void makeCurrent() throws LWJGLException;

    void releaseContext() throws LWJGLException;

    void destroy();

    void setCLSharingProperties(PointerBuffer properties) throws LWJGLException;
}
