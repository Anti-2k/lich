package dev.lich.compat.jdk.reflect;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The reflective calls Forge's ObjectHolderRef and EnumHelper make, written as they are but with the
 * names the compat pass gives them.
 */
class LegacyReflectionTest {
    static final class Holder {
        static final Object THING = null;
        static final int COUNT = 1;
    }

    enum Color {
        RED
    }

    @Test
    void objectHolderRefWritesAStaticFinal() throws Exception {
        String factoryName = "dev.lich.compat.jdk.reflect.ReflectionFactory"; // was "sun.reflect.ReflectionFactory"
        String accessorName = "dev.lich.compat.jdk.reflect.FieldAccessor";    // was "sun.reflect.FieldAccessor"
        Method getReflectionFactory = Class.forName(factoryName).getDeclaredMethod("getReflectionFactory");
        Object reflectionFactory = getReflectionFactory.invoke(null);
        Method newFieldAccessor = Class.forName(factoryName).getDeclaredMethod("newFieldAccessor", Field.class, boolean.class);
        Method fieldAccessorSet = Class.forName(accessorName).getDeclaredMethod("set", Object.class, Object.class);
        Field modifiersField = ReflectionShims.getDeclaredField(Field.class, "modifiers"); // was Field.class.getDeclaredField
        modifiersField.setAccessible(true);

        Field thing = Holder.class.getDeclaredField("THING");
        modifiersField.setInt(thing, thing.getModifiers() & ~Modifier.FINAL);
        Object value = new Object();
        Object accessor = newFieldAccessor.invoke(reflectionFactory, thing, false);
        fieldAccessorSet.invoke(accessor, null, value);
        assertSame(value, thing.get(null));

        Field count = Holder.class.getDeclaredField("COUNT");
        fieldAccessorSet.invoke(newFieldAccessor.invoke(reflectionFactory, count, false), null, 7);
        assertEquals(7, count.getInt(null));
    }

    @Test
    void fieldSetWritesAStaticFinalAfterTheModifiersHack() throws Exception {
        Field thing = Holder.class.getDeclaredField("THING");
        thing.setAccessible(true);
        Object value = new Object();
        ReflectionShims.set(thing, null, value); // was thing.set(null, value)
        assertSame(value, thing.get(null));
    }

    @Test
    void enumHelperCreatesAnEnumConstant() throws Exception {
        Constructor<Color> constructor = Color.class.getDeclaredConstructor(String.class, int.class);
        ConstructorAccessor accessor = ReflectionFactory.getReflectionFactory().newConstructorAccessor(constructor);
        Color green = (Color) accessor.newInstance(new Object[]{"GREEN", 1});
        assertEquals("GREEN", green.name());
        assertEquals(1, green.ordinal());
    }
}
