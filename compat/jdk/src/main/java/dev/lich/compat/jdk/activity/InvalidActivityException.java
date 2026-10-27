package dev.lich.compat.jdk.activity;

import java.io.Serial;
import java.rmi.RemoteException;

/**
 * Look-alike of {@code javax.activity.InvalidActivityException}, which left the JDK with CORBA in Java 11 (JEP 320).
 * The compat pass renames every reference to it. LiteLoader 1.7.10 throws it when a mod is looked up before its
 * startup has finished, and its bootstrap fails to load {@code LiteLoader} without the class. Same superclass and
 * constructors as Java 8's.
 */
public class InvalidActivityException extends RemoteException {
    @Serial
    private static final long serialVersionUID = -3609195990271066461L;

    public InvalidActivityException() {
    }

    public InvalidActivityException(String message) {
        super(message);
    }

    public InvalidActivityException(Throwable cause) {
        this("", cause);
    }

    public InvalidActivityException(String message, Throwable cause) {
        super(message, cause);
    }
}
