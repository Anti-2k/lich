package dev.lich.compat.asm;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A coremod computing frames through LaunchClassLoader gets ASM's answers without the classes being loaded, so no
 * transformer runs inside the one that is computing them (Mixin preparing a target would throw on re-entrance).
 */
class LaunchClassHierarchyTest {
    private static final String P = "lichtest/hierarchy/";

    @TempDir
    Path classes;

    private LaunchClassLoader loader;
    private final List<String> transformed = new ArrayList<>();
    private ClassLoader previousContext;

    @BeforeEach
    void setUp() throws Exception {
        write(P + "Base", "java/lang/Object", Opcodes.ACC_PUBLIC);
        write(P + "Left", P + "Base", Opcodes.ACC_PUBLIC);
        write(P + "Right", P + "Base", Opcodes.ACC_PUBLIC, P + "Marker");
        write(P + "Marker", "java/lang/Object", Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT);
        write(P + "Lone", "java/lang/Object", Opcodes.ACC_PUBLIC, P + "Marker");
        loader = new LaunchClassLoader(new URL[]{classes.toUri().toURL()});
        transformers().add((IClassTransformer) (name, transformedName, bytes) -> {
            transformed.add(transformedName);
            return bytes;
        });
        previousContext = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
    }

    @AfterEach
    void tearDown() {
        Thread.currentThread().setContextClassLoader(previousContext);
    }

    private void write(String name, String superName, int access, String... interfaces) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, access, name, null, superName, interfaces);
        writer.visitEnd();
        Path file = classes.resolve(name + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, writer.toByteArray());
    }

    @SuppressWarnings("unchecked")
    private List<IClassTransformer> transformers() throws ReflectiveOperationException {
        Field field = LaunchClassLoader.class.getDeclaredField("transformers");
        field.setAccessible(true);
        return (List<IClassTransformer>) field.get(loader);
    }

    @SuppressWarnings("unchecked")
    private boolean defined(String name) throws ReflectiveOperationException {
        Field field = LaunchClassLoader.class.getDeclaredField("cachedClasses");
        field.setAccessible(true);
        return ((Map<String, Class<?>>) field.get(loader)).containsKey(name);
    }

    private static String common(String type1, String type2) {
        return new LaunchClassLoaderClassWriter(ClassWriter.COMPUTE_FRAMES).getCommonSuperClass(type1, type2);
    }

    @Test
    void siblingsMeetAtTheirSuperclassWithoutBeingLoaded() throws Exception {
        assertEquals(P + "Base", common(P + "Left", P + "Right"));
        assertEquals(P + "Base", common(P + "Right", P + "Left"));
        assertEquals(P + "Base", common(P + "Base", P + "Left"));
        assertTrue(transformed.isEmpty(), "no transformer runs: " + transformed);
        assertFalse(defined("lichtest.hierarchy.Left"));
        assertFalse(defined("lichtest.hierarchy.Right"));
    }

    @Test
    void interfacesAndJdkTypesFollowAsmsRules() {
        assertEquals(P + "Marker", common(P + "Marker", P + "Right"));
        assertEquals("java/lang/Object", common(P + "Marker", P + "Left"));
        assertEquals("java/lang/Object", common(P + "Lone", P + "Left"));
        assertEquals("java/lang/Object", common(P + "Left", "java/util/ArrayList"));
        assertEquals("java/util/AbstractList", common("java/util/ArrayList", "java/util/LinkedList"));
        assertTrue(transformed.isEmpty(), "no transformer runs: " + transformed);
    }

    @Test
    void aLoadedClassIsReadFromItsClass() throws Exception {
        loader.loadClass("lichtest.hierarchy.Right");
        transformed.clear();
        assertEquals(P + "Base", common(P + "Left", P + "Right"));
        assertTrue(transformed.isEmpty(), "no transformer runs: " + transformed);
    }
}
