package dev.lich.compat.jdk.reflect;

import jdk.internal.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Objects;

/**
 * What clearing {@code final} through {@code Field.modifiers} did on Java 8: the {@code Field} reports the written
 * modifiers, and it writes the field from then on, wherever {@code Field.set} is called. Mods often clear the flag in
 * one class and write in another (HardcoreEnderExpansion's {@code Unfinalizer} and {@code BlockReplaceHelper}), where
 * the compat pass doesn't redirect the write. So the {@code Field} gets an accessor that writes through
 * {@code Unsafe}, in place of the JDK's, which refuses {@code static final} fields. Reads and access checks are
 * unchanged: {@code Field} still checks the caller's access before it asks the accessor.
 */
final class UnfinalizedFields {
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final long MODIFIERS = UNSAFE.objectFieldOffset(Field.class, "modifiers");
    private static final long FIELD_ACCESSOR = UNSAFE.objectFieldOffset(Field.class, "fieldAccessor");
    private static final long OVERRIDE_FIELD_ACCESSOR = UNSAFE.objectFieldOffset(Field.class, "overrideFieldAccessor");

    private UnfinalizedFields() {
    }

    /** Old code's write to {@code Field.modifiers}. */
    static void setModifiers(Field field, int modifiers) {
        int declared = declaredModifiers(field);
        UNSAFE.putInt(field, MODIFIERS, modifiers);
        if (Modifier.isFinal(declared) && !Modifier.isFinal(modifiers)) {
            Accessor accessor = new Accessor(field, Modifier.isStatic(declared));
            UNSAFE.putReference(field, FIELD_ACCESSOR, accessor);
            UNSAFE.putReference(field, OVERRIDE_FIELD_ACCESSOR, accessor);
        }
    }

    /** The modifiers the class file gave the field: those of a fresh copy, which a write to {@code field} doesn't reach. */
    private static int declaredModifiers(Field field) {
        try {
            return field.getDeclaringClass().getDeclaredField(field.getName()).getModifiers();
        } catch (NoSuchFieldException e) {
            return field.getModifiers();
        }
    }

    /**
     * Reads and writes one field with {@code Unsafe}, with reflection's conversions: unboxing, widening, and an
     * {@code IllegalArgumentException} for values or targets of the wrong type.
     */
    private static final class Accessor implements jdk.internal.reflect.FieldAccessor {
        private final Field field;
        private final Class<?> type;
        private final boolean isStatic;
        // A static field's base and offset are looked up at the first access, after the class is initialized. Both
        // are written before the volatile flag, so a thread that sees the flag sees them.
        private Object staticBase;
        private long offset;
        private volatile boolean staticReady;

        Accessor(Field field, boolean isStatic) {
            this.field = field;
            this.type = field.getType();
            this.isStatic = isStatic;
            if (!isStatic) {
                offset = UNSAFE.objectFieldOffset(field);
            }
        }

        /**
         * The JDK's own accessor initializes the class at the first read or write, not when the field's
         * {@code final} flag goes: ObjectHolderRef clears it while it scans classes whose static initializers
         * need blocks that aren't registered yet (NuclearCraft's {@code NCBiomes}).
         */
        private synchronized void prepareStatic() {
            if (!staticReady) {
                UNSAFE.ensureClassInitialized(field.getDeclaringClass());
                staticBase = UNSAFE.staticFieldBase(field);
                offset = UNSAFE.staticFieldOffset(field);
                staticReady = true;
            }
        }

        private Object base(Object target) {
            if (isStatic) {
                if (!staticReady) {
                    prepareStatic();
                }
                return staticBase;
            }
            Objects.requireNonNull(target, "target of an instance field");
            if (!field.getDeclaringClass().isInstance(target)) {
                throw new IllegalArgumentException("Can not access " + field + " on " + target.getClass().getName());
            }
            return target;
        }

        @Override
        public Object get(Object target) {
            Object base = base(target);
            if (!type.isPrimitive()) {
                return UNSAFE.getReferenceVolatile(base, offset);
            } else if (type == boolean.class) {
                return UNSAFE.getBooleanVolatile(base, offset);
            } else if (type == char.class) {
                return UNSAFE.getCharVolatile(base, offset);
            } else if (type == byte.class) {
                return UNSAFE.getByteVolatile(base, offset);
            } else if (type == short.class) {
                return UNSAFE.getShortVolatile(base, offset);
            } else if (type == int.class) {
                return UNSAFE.getIntVolatile(base, offset);
            } else if (type == long.class) {
                return UNSAFE.getLongVolatile(base, offset);
            } else if (type == float.class) {
                return UNSAFE.getFloatVolatile(base, offset);
            } else {
                return UNSAFE.getDoubleVolatile(base, offset);
            }
        }

        private Object getAs(Object target, Class<?> as) {
            if (!type.isPrimitive()) {
                throw new IllegalArgumentException("Attempt to get " + type.getName() + " field " + field + " as " + as);
            }
            return widen(get(target), as);
        }

        @Override
        public boolean getBoolean(Object target) {
            return (Boolean) getAs(target, boolean.class);
        }

        @Override
        public byte getByte(Object target) {
            return (Byte) getAs(target, byte.class);
        }

        @Override
        public char getChar(Object target) {
            return (Character) getAs(target, char.class);
        }

        @Override
        public short getShort(Object target) {
            return (Short) getAs(target, short.class);
        }

        @Override
        public int getInt(Object target) {
            return (Integer) getAs(target, int.class);
        }

        @Override
        public long getLong(Object target) {
            return (Long) getAs(target, long.class);
        }

        @Override
        public float getFloat(Object target) {
            return (Float) getAs(target, float.class);
        }

        @Override
        public double getDouble(Object target) {
            return (Double) getAs(target, double.class);
        }

        @Override
        public void set(Object target, Object value) {
            Object base = base(target);
            if (!type.isPrimitive()) {
                if (value != null && !type.isInstance(value)) {
                    throw new IllegalArgumentException("Can not set " + field + " to " + value.getClass().getName());
                }
                UNSAFE.putReferenceVolatile(base, offset, value);
                return;
            }
            Object converted = widen(value, type);
            if (type == boolean.class) {
                UNSAFE.putBooleanVolatile(base, offset, (Boolean) converted);
            } else if (type == char.class) {
                UNSAFE.putCharVolatile(base, offset, (Character) converted);
            } else if (type == byte.class) {
                UNSAFE.putByteVolatile(base, offset, (Byte) converted);
            } else if (type == short.class) {
                UNSAFE.putShortVolatile(base, offset, (Short) converted);
            } else if (type == int.class) {
                UNSAFE.putIntVolatile(base, offset, (Integer) converted);
            } else if (type == long.class) {
                UNSAFE.putLongVolatile(base, offset, (Long) converted);
            } else if (type == float.class) {
                UNSAFE.putFloatVolatile(base, offset, (Float) converted);
            } else {
                UNSAFE.putDoubleVolatile(base, offset, (Double) converted);
            }
        }

        /** A primitive into a reference field is refused, as reflection refuses it. */
        private void setPrimitive(Object target, Object value) {
            if (!type.isPrimitive()) {
                throw new IllegalArgumentException("Can not set " + field + " to a primitive");
            }
            set(target, value);
        }

        @Override
        public void setBoolean(Object target, boolean value) {
            setPrimitive(target, value);
        }

        @Override
        public void setByte(Object target, byte value) {
            setPrimitive(target, value);
        }

        @Override
        public void setChar(Object target, char value) {
            setPrimitive(target, value);
        }

        @Override
        public void setShort(Object target, short value) {
            setPrimitive(target, value);
        }

        @Override
        public void setInt(Object target, int value) {
            setPrimitive(target, value);
        }

        @Override
        public void setLong(Object target, long value) {
            setPrimitive(target, value);
        }

        @Override
        public void setFloat(Object target, float value) {
            setPrimitive(target, value);
        }

        @Override
        public void setDouble(Object target, double value) {
            setPrimitive(target, value);
        }
    }

    /**
     * {@code value} (boxed) as a boxed {@code to}, by Java's widening primitive conversions: what reflection accepts
     * when a field of one primitive type is read or written as another.
     */
    static Object widen(Object value, Class<?> to) {
        Class<?> from = primitiveOf(value);
        if (from == to) {
            return value;
        }
        if (from == null || from == boolean.class || to == boolean.class || to == char.class || rank(from) >= rank(to)) {
            throw new IllegalArgumentException("Can not convert "
                    + (value == null ? "null" : value.getClass().getName()) + " to " + to.getName());
        }
        // From here, a char only widens to int or wider (rank), and everything else is a Number.
        if (to == short.class) {
            return ((Number) value).shortValue();
        }
        long integral;
        if (value instanceof Character c) {
            integral = c;
        } else if (value instanceof Float || value instanceof Double) {
            integral = 0; // Unused: these only widen to float or double, which take the value itself.
        } else {
            integral = ((Number) value).longValue();
        }
        if (to == int.class) {
            return (int) integral;
        } else if (to == long.class) {
            return integral;
        } else if (to == float.class) {
            return value instanceof Float f ? f : (float) integral;
        } else {
            return value instanceof Float f ? (double) f : (double) integral;
        }
    }

    /** Widening order: byte, short, int, long, float, double; char sits beside short and widens to int and up. */
    private static int rank(Class<?> type) {
        if (type == byte.class) {
            return 0;
        } else if (type == short.class || type == char.class) {
            return 1;
        } else if (type == int.class) {
            return 2;
        } else if (type == long.class) {
            return 3;
        } else if (type == float.class) {
            return 4;
        } else {
            return 5;
        }
    }

    private static Class<?> primitiveOf(Object value) {
        return switch (value) {
            case Boolean _ -> boolean.class;
            case Character _ -> char.class;
            case Byte _ -> byte.class;
            case Short _ -> short.class;
            case Integer _ -> int.class;
            case Long _ -> long.class;
            case Float _ -> float.class;
            case Double _ -> double.class;
            case null, default -> null;
        };
    }
}
