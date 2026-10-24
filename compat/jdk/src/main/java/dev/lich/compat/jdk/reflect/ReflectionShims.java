package dev.lich.compat.jdk.reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Redirect targets for the "clear {@code final} through {@code Field.modifiers}" hack. Java 12 hides
 * {@code Field.modifiers} from reflection, so the hack throws {@code NoSuchFieldException}. Lich hands
 * out a dummy field instead, and makes the {@code Field.set} that follows write the final field anyway.
 * The compat pass only redirects these calls in classes that contain the string "modifiers".
 */
public final class ReflectionShims {
    /** What old code gets for {@code Field.modifiers}; writes to it go nowhere. */
    @SuppressWarnings("unused")
    private static int modifiers;

    private static final Field DUMMY_MODIFIERS;

    static {
        try {
            DUMMY_MODIFIERS = ReflectionShims.class.getDeclaredField("modifiers");
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ReflectionShims() {
    }

    public static Field getDeclaredField(Class<?> owner, String name) throws NoSuchFieldException {
        if (owner == Field.class && name.equals("modifiers")) {
            return DUMMY_MODIFIERS;
        }
        return owner.getDeclaredField(name);
    }

    public static void set(Field field, Object target, Object value) throws IllegalAccessException {
        if (Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())) {
            UnsafeFields.set(field, target, value);
        } else {
            field.set(target, value);
        }
    }
}
