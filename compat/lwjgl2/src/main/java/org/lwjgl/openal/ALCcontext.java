package org.lwjgl.openal;

/** LWJGL 2's OpenAL context handle; see {@link ALCdevice}, which works the same way. */
public final class ALCcontext {
    private final long context;
    private volatile boolean valid = true;

    public ALCcontext(long context) {
        this.context = context;
    }

    public long pointer() {
        return context;
    }

    public void invalidate() {
        valid = false;
    }

    public boolean isValid() {
        return valid;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ALCcontext other && other.context == context;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(context);
    }
}
