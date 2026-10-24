package dev.lich.compat.lwjgl2.openal;

import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCcontext;
import org.lwjgl.openal.ALCdevice;

import java.nio.IntBuffer;

/**
 * LWJGL 2's {@code AL}, which opened a device and made one context current in one call; LWJGL 3's
 * {@code AL} only holds capabilities. The capabilities are set for the whole process, since the game's
 * sound system calls OpenAL from threads of its own.
 */
public final class Lwjgl2AL {
    private static ALCdevice device;
    private static ALCcontext context;
    private static boolean created;

    private Lwjgl2AL() {
    }

    public static synchronized boolean isCreated() {
        return created;
    }

    public static void create() throws LWJGLException {
        create(null, 44100, 60, false);
    }

    public static void create(String deviceArguments, int contextFrequency, int contextRefresh, boolean contextSynchronized)
            throws LWJGLException {
        create(deviceArguments, contextFrequency, contextRefresh, contextSynchronized, true);
    }

    public static synchronized void create(String deviceArguments, int contextFrequency, int contextRefresh,
                                           boolean contextSynchronized, boolean openDevice) throws LWJGLException {
        if (created) {
            throw new IllegalStateException("Only one OpenAL context may be instantiated at any one time.");
        }
        if (!openDevice) {
            created = true;
            return;
        }
        long devicePointer = ALC10.alcOpenDevice(deviceArguments);
        if (devicePointer == 0) {
            throw new LWJGLException("Could not open ALC device");
        }
        IntBuffer attributes = null;
        if (contextFrequency != -1) {
            attributes = BufferUtils.createIntBuffer(7);
            attributes.put(ALC10.ALC_FREQUENCY).put(contextFrequency)
                    .put(ALC10.ALC_REFRESH).put(contextRefresh)
                    .put(ALC10.ALC_SYNC).put(contextSynchronized ? ALC10.ALC_TRUE : ALC10.ALC_FALSE)
                    .put(0).flip();
        }
        long contextPointer = ALC10.alcCreateContext(devicePointer, attributes);
        if (contextPointer == 0) {
            ALC10.alcCloseDevice(devicePointer);
            throw new LWJGLException("Could not create an OpenAL context");
        }
        ALC10.alcMakeContextCurrent(contextPointer);
        ALCCapabilities deviceCaps = ALC.createCapabilities(devicePointer);
        AL.createCapabilities(deviceCaps);
        device = new ALCdevice(devicePointer);
        context = new ALCcontext(contextPointer);
        created = true;
    }

    public static synchronized void destroy() {
        if (context != null) {
            ALC10.alcMakeContextCurrent(0L);
            ALC10.alcDestroyContext(context.pointer());
            context.invalidate();
            context = null;
        }
        if (device != null) {
            ALC10.alcCloseDevice(device.pointer());
            device.invalidate();
            device = null;
        }
        AL.setCurrentProcess(null);
        created = false;
    }

    public static synchronized ALCcontext getContext() {
        return context;
    }

    public static synchronized ALCdevice getDevice() {
        return device;
    }
}
