package dev.lich.compat.jdk.reflect;

import java.lang.StackWalker.StackFrame;
import java.util.Optional;

/**
 * Stand-in for {@code sun.reflect.Reflection}, which Java 9 moved out of reach: mods ask it who called them (Future MC
 * only grows bee nests when {@code BiomeDecorator} is {@code getCallerClass(4)}). Frames count as they did: 0 is this
 * class, 1 the method asking, and reflection's own frames don't count, which is {@link StackWalker}'s default too.
 */
public final class Reflection {
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private Reflection() {
    }

    public static Class<?> getCallerClass(int depth) {
        Optional<StackFrame> frame = WALKER.walk(frames -> frames.skip(depth).findFirst());
        return frame.<Class<?>>map(StackFrame::getDeclaringClass).orElse(null);
    }

    /** The class that called the method asking. */
    public static Class<?> getCallerClass() {
        return getCallerClass(3); // This method and getCallerClass(int) are two frames, not one.
    }

    /** Hid fields from reflection (Botania Tweaks guards its own). Only protects against other mods, so a no-op. */
    public static void registerFieldsToFilter(Class<?> owner, String... names) {
    }
}
