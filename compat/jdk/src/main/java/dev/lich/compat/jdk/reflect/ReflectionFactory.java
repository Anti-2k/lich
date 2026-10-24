package dev.lich.compat.jdk.reflect;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

/**
 * Stand-in for the parts of {@code sun.reflect.ReflectionFactory} that Java 9 removed,
 * {@code newFieldAccessor} and {@code newConstructorAccessor}. Old code reaches it by name, as Forge's
 * {@code ObjectHolderRef} and {@code EnumHelper} do. The compat pass renames that name to this class
 * only in classes that mention one of the two methods; other users of the real class (still in
 * jdk.unsupported, for serialization libraries) are left alone.
 */
public final class ReflectionFactory {
    private static final ReflectionFactory INSTANCE = new ReflectionFactory();

    private ReflectionFactory() {
    }

    public static ReflectionFactory getReflectionFactory() {
        return INSTANCE;
    }

    /** An accessor that can write {@code static final} fields too, as Java 8's could. */
    public FieldAccessor newFieldAccessor(Field field, boolean override) {
        field.setAccessible(true);
        return new FieldAccessor() {
            @Override
            public Object get(Object target) {
                try {
                    return field.get(target);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public void set(Object target, Object value) {
                UnsafeFields.set(field, target, value);
            }
        };
    }

    /** An accessor that calls the constructor through a method handle, which works for enums too. */
    public ConstructorAccessor newConstructorAccessor(Constructor<?> constructor) {
        MethodHandle handle;
        try {
            handle = MethodHandles.privateLookupIn(constructor.getDeclaringClass(), MethodHandles.lookup())
                    .unreflectConstructor(constructor);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Can't access " + constructor, e);
        }
        return args -> {
            try {
                return handle.invokeWithArguments(args == null ? new Object[0] : args);
            } catch (Throwable t) {
                throw new InvocationTargetException(t);
            }
        };
    }
}
