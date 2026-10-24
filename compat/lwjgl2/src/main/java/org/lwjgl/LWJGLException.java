package org.lwjgl;

/** LWJGL 2's checked exception, which LWJGL 3 doesn't have: thrown by Lich's Display, Mouse, AL and friends. */
public class LWJGLException extends Exception {
    private static final long serialVersionUID = 1L;

    public LWJGLException() {
    }

    public LWJGLException(String message) {
        super(message);
    }

    public LWJGLException(String message, Throwable cause) {
        super(message, cause);
    }

    public LWJGLException(Throwable cause) {
        super(cause);
    }
}
