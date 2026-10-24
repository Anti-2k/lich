package org.lwjgl.opengl;

/** LWJGL 2's unchecked GL error, as {@link Util#checkGLError} throws it. */
public class OpenGLException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public OpenGLException(int glErrorCode) {
        super(Util.translateGLErrorString(glErrorCode) + " (" + glErrorCode + ")");
    }

    public OpenGLException() {
    }

    public OpenGLException(String message) {
        super(message);
    }

    public OpenGLException(String message, Throwable cause) {
        super(message, cause);
    }

    public OpenGLException(Throwable cause) {
        super(cause);
    }
}
