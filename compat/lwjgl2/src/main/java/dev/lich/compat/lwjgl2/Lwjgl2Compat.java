package dev.lich.compat.lwjgl2;

import dev.lich.transform.CompatTransformer;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The call table that points LWJGL 2 code at LWJGL 3: members LWJGL 3 renamed ({@code renames.txt},
 * generated) and members it dropped or changed, which go to the static mirrors in this package. Classes
 * LWJGL 3 doesn't have at all ({@code Display}, {@code Keyboard}, ...) need no entry: this jar has them
 * under their LWJGL 2 names.
 *
 * <p>A mirror class holds public static methods named and typed like the LWJGL 2 members they replace;
 * every one of them is redirected, whether or not LWJGL 3 has a member with that signature (some do, with
 * other behavior: {@code AL.destroy()}). A mirror of an instance method is marked {@link Receiver} and takes
 * the receiver first; a mirror of a constructor is marked {@link Constructor} and returns the new object.
 * One mirror may serve several LWJGL 2 classes that had the same functions ({@code GL32} and {@code ARBSync}).
 */
public final class Lwjgl2Compat {
    private static final String RENAMES = "/dev/lich/compat/lwjgl2/renames.txt";
    private static final String GL = "org/lwjgl/opengl/";
    private static final String MIRROR = "dev/lich/compat/lwjgl2/";

    /** LWJGL 2 class → the mirror of its members that LWJGL 3 dropped or changed. */
    private static final Map<String, String> MIRRORS = Map.ofEntries(
            Map.entry("org/lwjgl/PointerBuffer", MIRROR + "Lwjgl2PointerBuffer"),
            Map.entry("org/lwjgl/openal/AL", MIRROR + "openal/Lwjgl2AL"),
            Map.entry("org/lwjgl/openal/ALC10", MIRROR + "openal/Lwjgl2ALC10"),
            Map.entry("org/lwjgl/openal/ALC11", MIRROR + "openal/Lwjgl2ALC11"),
            Map.entry(GL + "GL11", MIRROR + "opengl/Lwjgl2GL11"),
            Map.entry(GL + "GL14", MIRROR + "opengl/Lwjgl2GL14"),
            Map.entry(GL + "GL15", MIRROR + "opengl/Lwjgl2GL15"),
            Map.entry(GL + "ARBBufferObject", MIRROR + "opengl/Lwjgl2GL15"),
            Map.entry(GL + "ARBVertexBufferObject", MIRROR + "opengl/Lwjgl2GL15"),
            Map.entry(GL + "ARBPixelBufferObject", MIRROR + "opengl/Lwjgl2GL15"),
            Map.entry(GL + "GL20", MIRROR + "opengl/Lwjgl2GL20"),
            Map.entry(GL + "ARBShaderObjects", MIRROR + "opengl/Lwjgl2ARBShaderObjects"),
            Map.entry(GL + "ARBVertexShader", MIRROR + "opengl/Lwjgl2ARBVertexShader"),
            Map.entry(GL + "GL30", MIRROR + "opengl/Lwjgl2GL30"),
            Map.entry(GL + "GL32", MIRROR + "opengl/Lwjgl2GL32"),
            Map.entry(GL + "ARBSync", MIRROR + "opengl/Lwjgl2GL32"),
            Map.entry(GL + "GL43", MIRROR + "opengl/Lwjgl2DebugOutput"),
            Map.entry(GL + "KHRDebug", MIRROR + "opengl/Lwjgl2DebugOutput"),
            Map.entry(GL + "ARBDebugOutput", MIRROR + "opengl/Lwjgl2DebugOutput"),
            Map.entry(GL + "AMDDebugOutput", MIRROR + "opengl/Lwjgl2DebugOutput"),
            // Not LWJGL 2: LWJGL 3.3.1 signatures that later 3.3 releases changed, for mods built against it.
            Map.entry("org/lwjgl/nanovg/NanoVG", MIRROR + "nanovg/Lwjgl331NanoVG"));

    /** Marks a mirror of an instance method; its first parameter is the receiver. */
    public @interface Receiver {
    }

    /** Marks a mirror of a constructor: it takes the constructor's parameters and returns the new object. */
    public @interface Constructor {
    }

    private Lwjgl2Compat() {
    }

    public static CompatTransformer.Builder register(CompatTransformer.Builder builder) {
        return builder
                .calls(table())
                // Mods that bring their own LWJGL 3 (OneConfig, for NanoVG) load it in a class loader of their
                // own, unless they find PojavLauncher's LWJGL 3, which they detect by this class. With the game
                // on LWJGL 3, theirs must not load: two LWJGL 3 copies in one JVM overwrite each other's
                // per-thread GL function table, and the game's next GL call aborts the JVM. Only in classes
                // that load LWJGL themselves (they name org.lwjgl.system), so touch-input checks don't match.
                .renameString("org.lwjgl.glfw.CallbackBridge", "org.lwjgl.glfw.GLFW", "org.lwjgl.system");
    }

    /** The whole table: generated renames first, so a mirror wins over a rename of the same member. */
    public static CompatTransformer.CallTable table() {
        CompatTransformer.CallTable table = new CompatTransformer.CallTable("org/lwjgl/");
        readRenames(table);
        MIRRORS.forEach((owner, mirror) -> readMirror(table, owner, mirror));
        return table;
    }

    private static void readRenames(CompatTransformer.CallTable table) {
        try (InputStream in = Lwjgl2Compat.class.getResourceAsStream(RENAMES)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RENAMES);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split(" ");
                table.move(f[0], f[1], f[2], f[3], f[4], f[5]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads the mirror's method list from its class file, so building the table loads no class. */
    private static void readMirror(CompatTransformer.CallTable table, String owner, String mirror) {
        byte[] bytes;
        try (InputStream in = Lwjgl2Compat.class.getResourceAsStream("/" + mirror + ".class")) {
            if (in == null) {
                throw new IllegalStateException("Missing mirror " + mirror);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String receiver = Type.getDescriptor(Receiver.class);
        String constructor = Type.getDescriptor(Constructor.class);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                        || (access & Opcodes.ACC_SYNTHETIC) != 0) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    private boolean instance;
                    private boolean factory;

                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        instance |= annotation.equals(receiver);
                        factory |= annotation.equals(constructor);
                        return null;
                    }

                    @Override
                    public void visitEnd() {
                        if (factory) {
                            // (args)Lorg/lwjgl/Owner; → <init>(args)V
                            String init = descriptor.substring(0, descriptor.indexOf(')') + 1) + "V";
                            table.redirectConstructor(owner, init, mirror, name);
                        } else if (instance) {
                            // (Lorg/lwjgl/Owner;args)ret → (args)ret
                            String own = "L" + owner + ";";
                            if (!descriptor.startsWith("(" + own)) {
                                throw new IllegalStateException(mirror + "." + name + descriptor + " doesn't take an " + owner);
                            }
                            table.redirect(owner, name, "(" + descriptor.substring(own.length() + 1), mirror);
                        } else {
                            table.move(owner, name, descriptor, mirror, name, descriptor);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
}
