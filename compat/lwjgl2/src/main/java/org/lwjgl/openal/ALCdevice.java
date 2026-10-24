package org.lwjgl.openal;

/**
 * LWJGL 2's OpenAL device handle; LWJGL 3 passes the pointer itself. Lich's mirrors keep one per pointer,
 * so the device {@code AL.getDevice()} returns becomes invalid when it's closed, as in LWJGL 2. The
 * constructor and {@link #pointer()} are public (they weren't in LWJGL 2) for those mirrors.
 */
public final class ALCdevice {
    private final long device;
    private volatile boolean valid = true;

    public ALCdevice(long device) {
        this.device = device;
    }

    public long pointer() {
        return device;
    }

    public void invalidate() {
        valid = false;
    }

    public boolean isValid() {
        return valid;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ALCdevice other && other.device == device;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(device);
    }
}
