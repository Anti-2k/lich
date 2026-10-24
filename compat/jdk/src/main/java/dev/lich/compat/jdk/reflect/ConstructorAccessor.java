package dev.lich.compat.jdk.reflect;

import java.lang.reflect.InvocationTargetException;

/**
 * Stand-in for {@code sun.reflect.ConstructorAccessor} (gone since Java 9). Forge's {@code EnumHelper}
 * and mods that copied it use it to create enum constants, which {@code Constructor.newInstance}
 * refuses to do.
 */
public interface ConstructorAccessor {
    Object newInstance(Object[] args) throws InstantiationException, InvocationTargetException;
}
