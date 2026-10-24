package dev.lich.compat.jdk.reflect;

/**
 * Stand-in for {@code sun.reflect.FieldAccessor} (gone since Java 9). Old code finds it by name
 * ({@code Class.forName("sun.reflect.FieldAccessor").getDeclaredMethod("set", …)}), which the compat
 * pass renames to this interface, so the same reflective calls land here.
 */
public interface FieldAccessor {
    Object get(Object target);

    void set(Object target, Object value) throws IllegalAccessException;

    default void setBoolean(Object target, boolean value) throws IllegalAccessException {
        set(target, value);
    }

    default void setByte(Object target, byte value) throws IllegalAccessException {
        set(target, value);
    }

    default void setChar(Object target, char value) throws IllegalAccessException {
        set(target, value);
    }

    default void setShort(Object target, short value) throws IllegalAccessException {
        set(target, value);
    }

    default void setInt(Object target, int value) throws IllegalAccessException {
        set(target, value);
    }

    default void setLong(Object target, long value) throws IllegalAccessException {
        set(target, value);
    }

    default void setFloat(Object target, float value) throws IllegalAccessException {
        set(target, value);
    }

    default void setDouble(Object target, double value) throws IllegalAccessException {
        set(target, value);
    }
}
