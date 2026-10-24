package dev.lich.compat.lwjgl2.openal;

import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCcontext;
import org.lwjgl.openal.ALCdevice;

import java.nio.IntBuffer;

/** LWJGL 2's {@code ALC10}, which passed {@link ALCdevice} and {@link ALCcontext} where LWJGL 3 passes pointers. */
public final class Lwjgl2ALC10 {
    private Lwjgl2ALC10() {
    }

    static long pointer(ALCdevice device) {
        return device == null ? 0L : device.pointer();
    }

    static long pointer(ALCcontext context) {
        return context == null ? 0L : context.pointer();
    }

    /** The wrapper {@code AL} handed out for this pointer, so it stays the same object, or a new one. */
    static ALCdevice device(long pointer) {
        if (pointer == 0) {
            return null;
        }
        ALCdevice own = Lwjgl2AL.getDevice();
        return own != null && own.pointer() == pointer ? own : new ALCdevice(pointer);
    }

    static ALCcontext context(long pointer) {
        if (pointer == 0) {
            return null;
        }
        ALCcontext own = Lwjgl2AL.getContext();
        return own != null && own.pointer() == pointer ? own : new ALCcontext(pointer);
    }

    public static String alcGetString(ALCdevice device, int pname) {
        return ALC10.alcGetString(pointer(device), pname);
    }

    public static void alcGetInteger(ALCdevice device, int pname, IntBuffer integerdata) {
        ALC10.alcGetIntegerv(pointer(device), pname, integerdata);
    }

    public static ALCdevice alcOpenDevice(String devicename) {
        return device(ALC10.alcOpenDevice(devicename));
    }

    public static boolean alcCloseDevice(ALCdevice device) {
        boolean closed = ALC10.alcCloseDevice(pointer(device));
        if (closed && device != null) {
            device.invalidate();
        }
        return closed;
    }

    public static ALCcontext alcCreateContext(ALCdevice device, IntBuffer attrList) {
        return context(ALC10.alcCreateContext(pointer(device), attrList));
    }

    public static int alcMakeContextCurrent(ALCcontext context) {
        return ALC10.alcMakeContextCurrent(pointer(context)) ? ALC10.ALC_TRUE : ALC10.ALC_FALSE;
    }

    public static void alcProcessContext(ALCcontext context) {
        ALC10.alcProcessContext(pointer(context));
    }

    public static ALCcontext alcGetCurrentContext() {
        return context(ALC10.alcGetCurrentContext());
    }

    public static ALCdevice alcGetContextsDevice(ALCcontext context) {
        return device(ALC10.alcGetContextsDevice(pointer(context)));
    }

    public static void alcSuspendContext(ALCcontext context) {
        ALC10.alcSuspendContext(pointer(context));
    }

    public static void alcDestroyContext(ALCcontext context) {
        ALC10.alcDestroyContext(pointer(context));
        if (context != null) {
            context.invalidate();
        }
    }

    public static int alcGetError(ALCdevice device) {
        return ALC10.alcGetError(pointer(device));
    }

    public static boolean alcIsExtensionPresent(ALCdevice device, String extName) {
        return ALC10.alcIsExtensionPresent(pointer(device), extName);
    }

    public static int alcGetEnumValue(ALCdevice device, String enumName) {
        return ALC10.alcGetEnumValue(pointer(device), enumName);
    }
}
