package org.lwjgl.openal;

/** LWJGL 2's unchecked OpenAL error. */
public class OpenALException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public OpenALException() {
    }

    public OpenALException(int errorCode) {
        super("OpenAL error: " + AL10.alGetString(errorCode) + " (" + errorCode + ")");
    }

    public OpenALException(String message) {
        super(message);
    }

    public OpenALException(String message, Throwable cause) {
        super(message, cause);
    }

    public OpenALException(Throwable cause) {
        super(cause);
    }
}
