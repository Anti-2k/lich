package dev.lich.compat.jdk.reflect;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    /** Shaped like Forge's {@code BiomeDictionary.Type}, whose constructor is varargs. */
    enum Tag {
        HOT,
        DESERT(HOT);

        final Tag[] subTags;

        Tag(Tag... subTags) {
            this.subTags = subTags;
        }
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

    static final class Blocks {
        static final Object DRAGON_EGG = new Object();
        static final long SEED = Long.parseLong("1");
    }

    /**
     * HardcoreEnderExpansion clears {@code final} in one class ({@code Unfinalizer}, whose {@code setInt} the compat
     * pass redirects) and writes the field in another ({@code BlockReplaceHelper}), with a plain {@code Field.set}.
     */
    @Test
    void fieldSetWritesAStaticFinalUnfinalizedElsewhere() throws Exception {
        Field modifiersField = ReflectionShims.getDeclaredField(Field.class, "modifiers");
        modifiersField.setAccessible(true);

        Field egg = Blocks.class.getDeclaredField("DRAGON_EGG");
        assertNotNull(egg.get(null)); // Caches the JDK's accessor first, as HEE's search through Blocks does.
        egg.setAccessible(true);
        int modifiers = ReflectionShims.getInt(modifiersField, egg);
        assertEquals(egg.getModifiers(), modifiers);
        ReflectionShims.setInt(modifiersField, egg, modifiers & ~Modifier.FINAL);
        assertFalse(Modifier.isFinal(egg.getModifiers()));
        Object replacement = new Object();
        egg.set(null, replacement);
        assertSame(replacement, egg.get(null));
        assertSame(replacement, Blocks.DRAGON_EGG);

        // Without setAccessible, and with reflection's widening and type checks.
        Field seed = Blocks.class.getDeclaredField("SEED");
        ReflectionShims.setInt(modifiersField, seed, seed.getModifiers() & ~Modifier.FINAL);
        seed.setInt(null, 5);
        assertEquals(5L, seed.getLong(null));
        assertEquals(5.0, seed.getDouble(null));
        assertThrows(IllegalArgumentException.class, () -> seed.getInt(null));
        assertThrows(IllegalArgumentException.class, () -> seed.set(null, "5"));
        assertThrows(IllegalArgumentException.class, () -> seed.set(null, null));
    }

    @Test
    void enumHelperCreatesAnEnumConstant() throws Exception {
        Constructor<Color> constructor = Color.class.getDeclaredConstructor(String.class, int.class);
        ConstructorAccessor accessor = ReflectionFactory.getReflectionFactory().newConstructorAccessor(constructor);
        Color green = (Color) accessor.newInstance(new Object[]{"GREEN", 1});
        assertEquals("GREEN", green.name());
        assertEquals(1, green.ordinal());
    }

    @Test
    void enumHelperPassesAVarargsArrayThrough() throws Exception {
        Constructor<Tag> constructor = Tag.class.getDeclaredConstructor(String.class, int.class, Tag[].class);
        ConstructorAccessor accessor = ReflectionFactory.getReflectionFactory().newConstructorAccessor(constructor);
        Tag[] subTags = {Tag.HOT, Tag.DESERT};
        Tag wasteland = (Tag) accessor.newInstance(new Object[]{"WASTELAND", 2, subTags});
        assertEquals("WASTELAND", wasteland.name());
        assertSame(subTags, wasteland.subTags);
    }
}
