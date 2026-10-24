package org.lwjgl;

/** LWJGL 2's interface for objects that wrap a native pointer (GLSync, debug callbacks). */
public interface PointerWrapper {
    long getPointer();
}
