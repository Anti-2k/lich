package dev.lich.compat.jdk.reflect;

import jdk.internal.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * Writes fields that reflection refuses to write since Java 12 ({@code static final} ones), the way
 * {@code sun.reflect.FieldAccessor} wrote them on Java 8. Uses {@code jdk.internal.misc.Unsafe}
 * ({@code --add-exports java.base/jdk.internal.misc}), whose field access isn't being phased out like
 * {@code sun.misc.Unsafe}'s (JEP 471).
 */
final class UnsafeFields {
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();

    private UnsafeFields() {
    }

    static void set(Field field, Object target, Object value) {
        Object base;
        long offset;
        if (Modifier.isStatic(field.getModifiers())) {
            UNSAFE.ensureClassInitialized(field.getDeclaringClass());
            base = UNSAFE.staticFieldBase(field);
            offset = UNSAFE.staticFieldOffset(field);
        } else {
            base = Objects.requireNonNull(target, "target of an instance field");
            offset = UNSAFE.objectFieldOffset(field);
        }
        Class<?> type = field.getType();
        if (!type.isPrimitive()) {
            UNSAFE.putReferenceVolatile(base, offset, type.cast(value));
        } else if (type == boolean.class) {
            UNSAFE.putBooleanVolatile(base, offset, (Boolean) value);
        } else if (type == char.class) {
            UNSAFE.putCharVolatile(base, offset, (Character) value);
        } else if (type == byte.class) {
            UNSAFE.putByteVolatile(base, offset, ((Number) value).byteValue());
        } else if (type == short.class) {
            UNSAFE.putShortVolatile(base, offset, ((Number) value).shortValue());
        } else if (type == int.class) {
            UNSAFE.putIntVolatile(base, offset, ((Number) value).intValue());
        } else if (type == long.class) {
            UNSAFE.putLongVolatile(base, offset, ((Number) value).longValue());
        } else if (type == float.class) {
            UNSAFE.putFloatVolatile(base, offset, ((Number) value).floatValue());
        } else {
            UNSAFE.putDoubleVolatile(base, offset, ((Number) value).doubleValue());
        }
    }
}
