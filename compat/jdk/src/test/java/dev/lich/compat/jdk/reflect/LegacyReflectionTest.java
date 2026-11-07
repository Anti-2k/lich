package dev.lich.compat.jdk.reflect;

import dev.lich.compat.jdk.JdkCompat;
import dev.lich.transform.CompatTransformer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    enum Biome {
        PLAINS
    }

    /**
     * All of {@code EnumHelper.addEnum} as Forge 1.8.9 writes it: the new constant goes into {@code $VALUES}, and
     * {@code cleanEnumCache} blanks {@code Class.enumConstantDirectory} and {@code Class.enumConstants} (found by name
     * among {@code Class}'s declared fields), so {@code valueOf}, {@code values} and {@code EnumSet} see it.
     */
    @Test
    void enumHelperResetsTheEnumCaches() throws Exception {
        assertEquals(Biome.PLAINS, Biome.valueOf("PLAINS")); // Fills both caches first, as the game has by then.
        assertEquals(1, java.util.EnumSet.allOf(Biome.class).size());

        Constructor<Biome> constructor = Biome.class.getDeclaredConstructor(String.class, int.class);
        Biome desert = (Biome) ReflectionFactory.getReflectionFactory().newConstructorAccessor(constructor)
                .newInstance(new Object[]{"DESERT", 1});
        Field values = null;
        for (Field field : Biome.class.getDeclaredFields()) {
            if (field.isSynthetic() && field.getType() == Biome[].class) {
                values = field;
            }
        }
        assertNotNull(values, "$VALUES");
        setFailsafeFieldValue(values, null, new Biome[]{Biome.PLAINS, desert});
        blankField(Biome.class, "enumConstantDirectory");
        blankField(Biome.class, "enumConstants");

        assertSame(desert, Biome.valueOf("DESERT"));
        assertEquals(2, Biome.values().length);
        assertEquals(2, java.util.EnumSet.allOf(Biome.class).size());
    }

    private static void blankField(Class<?> enumClass, String fieldName) throws Exception {
        for (Field field : Class.class.getDeclaredFields()) {
            if (field.getName().contains(fieldName)) {
                field.setAccessible(true);
                setFailsafeFieldValue(field, enumClass, null);
                break;
            }
        }
    }

    /** {@code EnumHelper.setFailsafeFieldValue}, with the names the compat pass gives its calls. */
    private static void setFailsafeFieldValue(Field field, Object target, Object value) throws Exception {
        field.setAccessible(true);
        Field modifiersField = ReflectionShims.getDeclaredField(Field.class, "modifiers");
        modifiersField.setAccessible(true);
        int modifiers = ReflectionShims.getInt(modifiersField, field);
        ReflectionShims.setInt(modifiersField, field, modifiers & ~Modifier.FINAL);
        ReflectionFactory.getReflectionFactory().newFieldAccessor(field, false).set(target, value);
    }

    @Test
    void primitiveSettersWriteStaticFinals() throws Exception {
        Field count = Primitives.class.getDeclaredField("COUNT");
        Field enabled = Primitives.class.getDeclaredField("ENABLED");
        Field scale = Primitives.class.getDeclaredField("SCALE");
        ReflectionShims.setLong(count, null, 9L); // was count.setLong(null, 9L), without clearing final
        ReflectionShims.setBoolean(enabled, null, true);
        ReflectionShims.setInt(scale, null, 3); // int widens to double, as reflection allows
        assertEquals(9L, count.getLong(null));
        assertEquals(true, enabled.getBoolean(null));
        assertEquals(3.0, scale.getDouble(null));
        assertThrows(IllegalArgumentException.class, () -> ReflectionShims.setDouble(count, null, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> ReflectionShims.setInt(Holder.class.getDeclaredField("THING"), null, 1));
    }

    /** The compat pass redirects a {@code Field.setLong} in a class that names "modifiers", and the write lands. */
    @Test
    void compatPassRedirectsPrimitiveSetters() throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, "test/FinalWriter", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "write",
                "(Ljava/lang/reflect/Field;J)V", null, new String[]{"java/lang/IllegalAccessException"});
        mv.visitCode();
        mv.visitLdcInsn("modifiers");
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.LLOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "setLong", "(Ljava/lang/Object;J)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();

        CompatTransformer compat = JdkCompat.register(CompatTransformer.builder()).build();
        byte[] redirected = compat.transform("test.FinalWriter", writer.toByteArray());
        Class<?> finalWriter = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass("test.FinalWriter", redirected, 0, redirected.length);
            }
        }.define();
        Field seed = Primitives.class.getDeclaredField("SEED");
        finalWriter.getMethod("write", Field.class, long.class).invoke(null, seed, 42L);
        assertEquals(42L, seed.getLong(null));
    }

    static final class Primitives {
        static final long SEED = Long.parseLong("0");
        static final long COUNT = Long.parseLong("1");
        static final boolean ENABLED = Boolean.parseBoolean("false");
        static final double SCALE = Double.parseDouble("1");
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

    /** Set by {@link LateInit}'s static initializer: reading it can't start the initializer being watched. */
    static final class Probe {
        static volatile boolean initialized;
    }

    /** A holder class whose static initializer must not run until a field is first used. */
    static final class LateInit {
        static final Object BLOCK;

        static {
            Probe.initialized = true;
            BLOCK = null;
        }
    }

    /**
     * ObjectHolderRef clears {@code final} while it scans classes whose static initializers need things that aren't
     * registered yet (NuclearCraft's {@code NCBiomes}); Java 8 initialized the class at the first read or write.
     */
    @Test
    void clearingFinalDoesNotInitializeTheClassButTheFirstAccessDoes() throws Exception {
        Field block = LateInit.class.getDeclaredField("BLOCK");
        block.setAccessible(true);
        Field modifiersField = ReflectionShims.getDeclaredField(Field.class, "modifiers");
        modifiersField.setAccessible(true);
        ReflectionShims.setInt(modifiersField, block, block.getModifiers() & ~Modifier.FINAL);
        assertFalse(Probe.initialized, "clearing final must leave the class alone");

        Object value = new Object();
        block.set(null, value);
        assertTrue(Probe.initialized);
        assertSame(value, block.get(null));
    }
}
