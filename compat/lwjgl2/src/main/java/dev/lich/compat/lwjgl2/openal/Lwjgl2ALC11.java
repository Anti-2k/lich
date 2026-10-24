package dev.lich.compat.lwjgl2.openal;

import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALCdevice;

import java.nio.ByteBuffer;

/** LWJGL 2's {@code ALC11} capture functions, with {@link ALCdevice} for the pointers LWJGL 3 passes. */
public final class Lwjgl2ALC11 {
    private Lwjgl2ALC11() {
    }

    public static ALCdevice alcCaptureOpenDevice(String devicename, int frequency, int format, int buffersize) {
        long device = ALC11.alcCaptureOpenDevice(devicename, frequency, format, buffersize);
        return device == 0 ? null : new ALCdevice(device);
    }

    public static boolean alcCaptureCloseDevice(ALCdevice device) {
        boolean closed = ALC11.alcCaptureCloseDevice(Lwjgl2ALC10.pointer(device));
        if (closed && device != null) {
            device.invalidate();
        }
        return closed;
    }

    public static void alcCaptureStart(ALCdevice device) {
        ALC11.alcCaptureStart(Lwjgl2ALC10.pointer(device));
    }

    public static void alcCaptureStop(ALCdevice device) {
        ALC11.alcCaptureStop(Lwjgl2ALC10.pointer(device));
    }

    public static void alcCaptureSamples(ALCdevice device, ByteBuffer buffer, int samples) {
        ALC11.alcCaptureSamples(Lwjgl2ALC10.pointer(device), buffer, samples);
    }
}
