package org.lwjgl;

/** LWJGL 2's base class for pointer wrappers, with its validity checks and identity by pointer. */
public abstract class PointerWrapperAbstract implements PointerWrapper {
    protected final long pointer;

    protected PointerWrapperAbstract(long pointer) {
        this.pointer = pointer;
    }

    public boolean isValid() {
        return pointer != 0;
    }

    public final void checkValid() {
        if (LWJGLUtil.DEBUG && !isValid()) {
            throw new IllegalStateException("This " + getClass().getSimpleName() + " pointer is not valid.");
        }
    }

    @Override
    public final long getPointer() {
        checkValid();
        return pointer;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof PointerWrapperAbstract other && pointer == other.pointer;
    }

    @Override
    public int hashCode() {
        return (int) (pointer ^ (pointer >>> 32));
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " pointer (0x" + Long.toHexString(pointer).toUpperCase() + ")";
    }
}
