package dev.lich.compat.jdk.reflect;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code sun.reflect.Reflection}'s frame counting, and what old code gets for hidden fields. */
class ReflectionTest {
    /** Asks for the frames above itself, as Future MC's {@code cannotGenerate} does. */
    public static final class Asking {
        public static Class<?> at(int depth) {
            return Reflection.getCallerClass(depth);
        }

        public static Class<?> caller() {
            return Reflection.getCallerClass();
        }
    }

    @Test
    void framesCountFromTheStandInUpward() {
        assertSame(Reflection.class, Asking.at(0));
        assertSame(Asking.class, Asking.at(1));
        assertSame(ReflectionTest.class, Asking.at(2));
    }

    @Test
    void reflectionsOwnFramesDontCount() throws ReflectiveOperationException {
        Object caller = Asking.class.getMethod("at", int.class).invoke(null, 2);
        assertSame(ReflectionTest.class, caller);
    }

    @Test
    void theNoArgumentFormIsTheCallerOfTheMethodAsking() {
        assertSame(ReflectionTest.class, Asking.caller());
    }

    @Test
    void framesBeyondTheStackAreNull() {
        assertEquals(null, Asking.at(100_000));
    }

    @Test
    void classLoaderPackagesReadsAsAnEmptyMap() throws ReflectiveOperationException {
        Field packages = ReflectionShims.getDeclaredField(ClassLoader.class, "packages");
        packages.setAccessible(true);
        assertTrue(((Map<?, ?>) packages.get(getClass().getClassLoader())).isEmpty());
    }
}
