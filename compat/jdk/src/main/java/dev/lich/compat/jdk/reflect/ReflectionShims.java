package dev.lich.compat.jdk.reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redirect targets for the "clear {@code final} through {@code Field.modifiers}" hack. Java 12 hides
 * {@code Field.modifiers} from reflection, so the hack throws {@code NoSuchFieldException}. Lich hands
 * out a dummy field instead: reading and writing it through {@code getInt}/{@code setInt} (or {@code set})
 * reads and writes the target field's modifiers, and clearing {@code final} makes that field writable
 * ({@link UnfinalizedFields}). A {@code Field.set} or {@code Field.set<Primitive>} redirected here writes a
 * {@code static final} field anyway. The compat pass redirects {@code getDeclaredField} in every class, since
 * mods look the field up through helpers that take its name as an argument (IC2's {@code ReflectionUtil}), and the
 * writes only in classes that contain the string "modifiers".
 */
public final class ReflectionShims {
    /** What old code gets for {@code Field.modifiers}; the redirects below never let a write land here. */
    @SuppressWarnings("unused")
    private static int modifiers;

    /**
     * What old code gets for {@code ClassLoader.packages}, which Java 12 hides too. NormalASM and its forks only read
     * the names in it, to share their strings: with none, the game runs the same and that saving is gone.
     */
    @SuppressWarnings("unused")
    private static final Map<String, Object> packages = new ConcurrentHashMap<>();

    private static final Field DUMMY_MODIFIERS;
    private static final Field DUMMY_PACKAGES;

    static {
        try {
            DUMMY_MODIFIERS = ReflectionShims.class.getDeclaredField("modifiers");
            DUMMY_PACKAGES = ReflectionShims.class.getDeclaredField("packages");
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
        if (owner == ClassLoader.class && name.equals("packages")) {
            return DUMMY_PACKAGES;
        }
        return owner.getDeclaredField(name);
    }

    public static void set(Field field, Object target, Object value) throws IllegalAccessException {
        if (field == DUMMY_MODIFIERS && target instanceof Field written && value instanceof Integer newModifiers) {
            UnfinalizedFields.setModifiers(written, newModifiers);
        } else if (Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())) {
            UnsafeFields.set(field, target, value);
        } else {
            field.set(target, value);
        }
    }

    public static void setInt(Field field, Object target, int value) throws IllegalAccessException {
        if (field == DUMMY_MODIFIERS && target instanceof Field written) {
            UnfinalizedFields.setModifiers(written, value);
        } else if (isStaticFinal(field)) {
            setStaticFinal(field, value);
        } else {
            field.setInt(target, value);
        }
    }

    public static void setBoolean(Field field, Object target, boolean value) throws IllegalAccessException {
        if (isStaticFinal(field)) {
            setStaticFinal(field, value);
        } else {
            field.setBoolean(target, value);
        }
    }

    public static void setByte(Field field, Object target, byte value) throws IllegalAccessException {
        if (isStaticFinal(field)) {
            setStaticFinal(field, value);
        } else {
            field.setByte(target, value);
        }
    }

    public static void setChar(Field field, Object target, char value) throws IllegalAccessException {
        if (isStaticFinal(field)) {
            setStaticFinal(field, value);
        } else {
            field.setChar(target, value);
        }
    }

    public static void setShort(Field field, Object target, short value) throws IllegalAccessException {
        if (isStaticFinal(field)) {
            setStaticFinal(field, value);
        } else {
            field.setShort(target, value);
        }
    }

    public static void setLong(Field field, Object target, long value) throws IllegalAccessException {
        if (isStaticFinal(field)) {
            setStaticFinal(field, value);
        } else {
            field.setLong(target, value);
        }
    }

    public static void setFloat(Field field, Object target, float value) throws IllegalAccessException {
        if (isStaticFinal(field)) {
            setStaticFinal(field, value);
        } else {
            field.setFloat(target, value);
        }
    }

    public static void setDouble(Field field, Object target, double value) throws IllegalAccessException {
        if (isStaticFinal(field)) {
            setStaticFinal(field, value);
        } else {
            field.setDouble(target, value);
        }
    }

    private static boolean isStaticFinal(Field field) {
        return Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers());
    }

    /** A primitive write, with reflection's widening and type checks, to a field reflection won't write. */
    private static void setStaticFinal(Field field, Object boxed) {
        if (!field.getType().isPrimitive()) {
            throw new IllegalArgumentException("Can not set " + field + " to a primitive");
        }
        UnsafeFields.set(field, null, UnfinalizedFields.widen(boxed, field.getType()));
    }

    /** Old code reads the modifiers it is about to change this way as often as through {@code getModifiers()}. */
    public static int getInt(Field field, Object target) throws IllegalAccessException {
        if (field == DUMMY_MODIFIERS && target instanceof Field read) {
            return read.getModifiers();
        }
        return field.getInt(target);
    }
}
