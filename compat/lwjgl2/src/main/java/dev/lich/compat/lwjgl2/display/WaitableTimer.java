package dev.lich.compat.lwjgl2.display;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * A Windows high-resolution waitable timer ({@code CREATE_WAITABLE_TIMER_HIGH_RESOLUTION}, Windows 10 1803+), through
 * the Foreign Function API (Lich starts the game with {@code --enable-native-access=ALL-UNNAMED}). It wakes within
 * about half a millisecond of the deadline whatever the system timer resolution, where {@code Thread.sleep(1)}
 * relies on the 1 ms timer resolution and often oversleeps by a millisecond or more. {@link #create()} returns null
 * anywhere it isn't available, and {@link FrameLimiter} then sleeps as LWJGL 2 did.
 */
@SuppressWarnings("restricted")
final class WaitableTimer {
    private static final int CREATE_WAITABLE_TIMER_HIGH_RESOLUTION = 0x2;
    private static final int TIMER_ALL_ACCESS = 0x1F0003;
    private static final int INFINITE = 0xFFFFFFFF;

    private final MethodHandle set;
    private final MethodHandle wait;
    private final MemorySegment handle;
    private final MemorySegment due = Arena.global().allocate(ValueLayout.JAVA_LONG);

    private WaitableTimer(MethodHandle set, MethodHandle wait, MemorySegment handle) {
        this.set = set;
        this.wait = wait;
        this.handle = handle;
    }

    static WaitableTimer create() {
        if (!System.getProperty("os.name", "").startsWith("Windows") || "false".equals(System.getProperty("lich.preciseSync"))) {
            return null;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            MethodHandle create = linker.downcallHandle(kernel32.find("CreateWaitableTimerExW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT));
            MethodHandle set = linker.downcallHandle(kernel32.find("SetWaitableTimer").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MethodHandle wait = linker.downcallHandle(kernel32.find("WaitForSingleObject").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            MemorySegment handle = (MemorySegment) create.invokeExact(MemorySegment.NULL, MemorySegment.NULL,
                    CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
            return handle.address() == 0 ? null : new WaitableTimer(set, wait, handle);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Blocks for about {@code nanos}; false if the timer failed, so the caller falls back to sleeping. */
    boolean sleep(long nanos) {
        // A negative due time is relative, in 100 ns units.
        due.set(ValueLayout.JAVA_LONG, 0, -Math.max(1, nanos / 100));
        try {
            int ok = (int) set.invokeExact(handle, due, 0, MemorySegment.NULL, MemorySegment.NULL, 0);
            return ok != 0 && (int) wait.invokeExact(handle, INFINITE) == 0;
        } catch (Throwable e) {
            return false;
        }
    }
}
