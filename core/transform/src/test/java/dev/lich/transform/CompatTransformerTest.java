package dev.lich.transform;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatTransformerTest {
    private final List<String> changes = new ArrayList<>();
    private final CompatTransformer compat = CompatTransformer.builder()
            .renameType("java/util/jar/Pack200", "shim/Pack200")
            .renameType("java/util/jar/Pack200$Unpacker", "shim/Pack200$Unpacker")
            .redirect("java/lang/System", "setSecurityManager", "(Ljava/lang/SecurityManager;)V", "shim/SystemShims")
            .redirect("java/lang/Thread", "stop", "()V", "shim/ThreadShims")
            .renamePackage("old/lib/", "new/lib/")
            .redirect("new/lib/Type", "getType", "(Ljava/lang/String;)Lnew/lib/Type;", "shim/Types")
            .rebase("new/lib/Verifier", "shim/Verifier")
            .renamePackage("kept/lib/", "new/lib/", () -> false)
            .fixInterfaceHandles(owner -> owner.equals("test/Comparing"))
            .replaceInstantiations("lib/Writer", "shim/Writer")
            .replaceIntConstant(0x50000, 0x90000, "asmlib/", "raised the API level")
            .patch("test.Patched", node -> {
                node.access |= Opcodes.ACC_FINAL;
                return true;
            })
            .listener((className, what) -> changes.add(className + ": " + what))
            .build();

    @Test
    void classesWithNothingToDoComeBackAsTheSameArray() {
        byte[] bytes = generate("test.Plain", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
            mv.visitInsn(Opcodes.POP2);
        });
        assertSame(bytes, compat.transform("test.Plain", bytes));
        assertTrue(changes.isEmpty());
        assertNull(compat.transform("test.Missing", null));
    }

    @Test
    void staticCallsAreRedirected() {
        byte[] bytes = generate("test.Fml", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setSecurityManager", "(Ljava/lang/SecurityManager;)V", false);
        });
        MethodInsnNode call = onlyCall(compat.transform("test.Fml", bytes));
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals("shim/SystemShims", call.owner);
        assertEquals("setSecurityManager", call.name);
        assertEquals("(Ljava/lang/SecurityManager;)V", call.desc);
        assertEquals(List.of("test.Fml: redirected java/lang/System.setSecurityManager to shim/SystemShims"), changes);
    }

    @Test
    void instanceCallsBecomeStaticCallsThatTakeTheReceiver() {
        byte[] bytes = generate("test.Stopper", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "stop", "()V", false);
        });
        ClassNode node = read(compat.transform("test.Stopper", bytes));
        MethodInsnNode stop = calls(node).get(1);
        assertEquals(Opcodes.INVOKESTATIC, stop.getOpcode());
        assertEquals("shim/ThreadShims", stop.owner);
        assertEquals("(Ljava/lang/Thread;)V", stop.desc);
    }

    @Test
    void removedTypesAreRenamedEverywhere() {
        byte[] bytes = generate("test.Patcher", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/jar/Pack200", "newUnpacker", "()Ljava/util/jar/Pack200$Unpacker;", false);
            mv.visitInsn(Opcodes.POP);
        });
        MethodInsnNode call = onlyCall(compat.transform("test.Patcher", bytes));
        assertEquals("shim/Pack200", call.owner);
        assertEquals("()Lshim/Pack200$Unpacker;", call.desc);
    }

    @Test
    void packagesAreRenamedWithTheirSubpackages() {
        byte[] bytes = generate("test.Mixin", mv -> {
            mv.visitTypeInsn(Opcodes.NEW, "old/lib/tree/ClassNode");
            mv.visitInsn(Opcodes.POP);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "old/lib/ClassReader", "read", "(Lold/lib/tree/ClassNode;)Lold/library/Other;", false);
            mv.visitInsn(Opcodes.POP);
        });
        ClassNode node = read(compat.transform("test.Mixin", bytes));
        assertEquals("new/lib/tree/ClassNode", ((TypeInsnNode) node.methods.getFirst().instructions.getFirst()).desc);
        MethodInsnNode call = calls(node).getFirst();
        assertEquals("new/lib/ClassReader", call.owner);
        assertEquals("(Lnew/lib/tree/ClassNode;)Lold/library/Other;", call.desc);
        assertEquals(List.of("test.Mixin: renamed package old/lib/ to new/lib/ (logged once for test.*)"), changes);

        compat.transform("test.Mixin2", generate("test.Mixin2", mv -> {
            mv.visitTypeInsn(Opcodes.NEW, "old/lib/tree/ClassNode");
            mv.visitInsn(Opcodes.POP);
        }));
        assertEquals(1, changes.size());
    }

    @Test
    void namespaces() {
        assertEquals("org.spongepowered", CompatTransformer.namespace("org.spongepowered.asm.mixin.Mixin"));
        assertEquals("dev.twok", CompatTransformer.namespace("dev.twok.Client"));
        assertEquals("optifine", CompatTransformer.namespace("optifine.Config"));
        assertEquals("", CompatTransformer.namespace("Config"));
    }

    @Test
    void classesInARenamedPackageKeepTheirNames() {
        byte[] bytes = generate("old.lib.ClassWriter", mv -> {
            mv.visitTypeInsn(Opcodes.NEW, "old/lib/Item");
            mv.visitInsn(Opcodes.POP);
        });
        assertSame(bytes, compat.transform("old.lib.ClassWriter", bytes));
    }

    @Test
    void redirectsMatchOwnersSpelledWithTheOldPackage() {
        byte[] bytes = generate("test.Plugin", mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "old/lib/Type", "getType", "(Ljava/lang/String;)Lold/lib/Type;", false);
            mv.visitInsn(Opcodes.POP);
        });
        MethodInsnNode call = onlyCall(compat.transform("test.Plugin", bytes));
        assertEquals("shim/Types", call.owner);
        assertEquals("(Ljava/lang/String;)Lnew/lib/Type;", call.desc);
    }

    @Test
    void subclassesAreRebasedButNewInstancesAreNot() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        // Spelled with the old package, as Mixin 0.7 does before the package rename.
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/MixinVerifier", null, "old/lib/Verifier", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        // super(new Verifier()): the inner call belongs to the NEW, the outer one is the super call.
        mv.visitTypeInsn(Opcodes.NEW, "old/lib/Verifier");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "old/lib/Verifier", "<init>", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "old/lib/Verifier", "<init>", "(Lold/lib/Verifier;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();

        ClassNode node = read(compat.transform("test.MixinVerifier", writer.toByteArray()));
        assertEquals("shim/Verifier", node.superName);
        List<MethodInsnNode> calls = calls(node);
        assertEquals("new/lib/Verifier", calls.get(0).owner);
        assertEquals("shim/Verifier", calls.get(1).owner);
        assertEquals("(Lnew/lib/Verifier;)V", calls.get(1).desc);
        assertTrue(changes.contains("test.MixinVerifier: rebased from new/lib/Verifier onto shim/Verifier"));
    }

    @Test
    void instantiationsAreReplacedButSuperCallsAreNot() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/MyWriter", null, "lib/Writer", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        // super(new Writer(new Writer())): two instantiations, then the super call.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.NEW, "lib/Writer");
        mv.visitInsn(Opcodes.DUP);
        mv.visitTypeInsn(Opcodes.NEW, "lib/Writer");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "lib/Writer", "<init>", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "lib/Writer", "<init>", "(Llib/Writer;)V", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "lib/Writer", "<init>", "(Llib/Writer;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();

        ClassNode node = read(compat.transform("test.MyWriter", writer.toByteArray()));
        assertEquals("lib/Writer", node.superName);
        List<String> news = new ArrayList<>();
        node.methods.getFirst().instructions.forEach(insn -> {
            if (insn instanceof TypeInsnNode type) {
                news.add(type.desc);
            }
        });
        assertEquals(List.of("shim/Writer", "shim/Writer"), news);
        assertEquals(List.of("shim/Writer", "shim/Writer", "lib/Writer"), calls(node).stream().map(call -> call.owner).toList());
    }

    @Test
    void intConstantsAreReplacedOnlyInClassesUsingTheMarker() {
        byte[] visitor = generate("test.Visitor", mv -> {
            mv.visitLdcInsn(0x50000);
            mv.visitTypeInsn(Opcodes.NEW, "asmlib/ClassVisitor");
            mv.visitInsn(Opcodes.POP2);
        });
        ClassNode node = read(compat.transform("test.Visitor", visitor));
        assertEquals(0x90000, ((LdcInsnNode) node.methods.getFirst().instructions.getFirst()).cst);
        assertEquals(List.of("test.Visitor: raised the API level (logged once for test.*)"), changes);

        byte[] unrelated = generate("test.Unrelated", mv -> {
            mv.visitLdcInsn(0x50000);
            mv.visitInsn(Opcodes.POP);
        });
        assertSame(unrelated, compat.transform("test.Unrelated", unrelated));
    }

    @Test
    void conditionalPackageRenamesCanSayNo() {
        byte[] bytes = generate("test.UniMixin", mv -> {
            mv.visitTypeInsn(Opcodes.NEW, "kept/lib/tree/ClassNode");
            mv.visitInsn(Opcodes.POP);
        });
        assertSame(bytes, compat.transform("test.UniMixin", bytes));
    }

    @Test
    void methodHandlesToInterfaceMethodsGetTheInterfaceFlag() {
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "test/ScoreFunction",
                null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create", "()V", null, null);
        mv.visitCode();
        // What ASM 5.0-based reobfuscation wrote: no interface flag, on its own lambda body, on another
        // interface's static method, and (correctly) on a class's.
        for (String owner : List.of("test/ScoreFunction", "test/Comparing", "test/Helper")) {
            mv.visitInvokeDynamicInsn("get", "()Ljava/util/function/IntSupplier;", metafactory,
                    Type.getType("()I"), new Handle(Opcodes.H_INVOKESTATIC, owner, "lambda$0", "()I", false), Type.getType("()I"));
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();

        ClassNode node = read(compat.transform("test.ScoreFunction", writer.toByteArray()));
        List<Boolean> flags = new ArrayList<>();
        node.methods.getFirst().instructions.forEach(insn -> {
            if (insn instanceof InvokeDynamicInsnNode indy) {
                assertFalse(indy.bsm.isInterface());
                flags.add(((Handle) indy.bsmArgs[1]).isInterface());
            }
        });
        assertEquals(List.of(true, true, false), flags);
        assertEquals(List.of("test.ScoreFunction: marked method handles to interface methods (logged once for test.*)"), changes);
    }

    @Test
    void patchesApplyByClassName() {
        byte[] bytes = generate("test.Patched", mv -> {
        });
        assertEquals(Opcodes.ACC_FINAL, read(compat.transform("test.Patched", bytes)).access & Opcodes.ACC_FINAL);
        assertSame(bytes, compat.transform("test.Other", bytes));
    }

    private final CompatTransformer tables = CompatTransformer.builder()
            .calls(new CompatTransformer.CallTable("old/gl/")
                    .redirect("old/gl/GL11", "glFog", "(ILjava/nio/FloatBuffer;)V", "shim/GL11")
                    .redirect("old/gl/Buffer", "flip", "()Lold/gl/Buffer;", "shim/Buffer")
                    .move("old/gl/GL11", "glGetFloat", "(ILjava/nio/FloatBuffer;)V", "old/gl/GL11", "glGetFloatv",
                            "(ILjava/nio/FloatBuffer;)V")
                    .redirectConstructor("old/gl/Buffer", "(Ljava/nio/ByteBuffer;)V", "shim/Buffer", "newBuffer"))
            .listener((className, what) -> changes.add(className + ": " + what))
            .build();

    @Test
    void callTablesRedirectRenameAndReplaceConstructors() {
        byte[] bytes = generate("test.Renderer", mv -> {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "old/gl/GL11", "glFog", "(ILjava/nio/FloatBuffer;)V", false);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "old/gl/GL11", "glGetFloat", "(ILjava/nio/FloatBuffer;)V", false);
            mv.visitTypeInsn(Opcodes.NEW, "old/gl/Buffer");
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "old/gl/Buffer", "<init>", "(Ljava/nio/ByteBuffer;)V", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "old/gl/Buffer", "flip", "()Lold/gl/Buffer;", false);
            mv.visitInsn(Opcodes.POP);
        });
        ClassNode node = read(tables.transform("test.Renderer", bytes));
        List<String> calls = calls(node).stream()
                .map(call -> call.getOpcode() + " " + call.owner + "." + call.name + call.desc).toList();
        assertEquals(List.of(
                Opcodes.INVOKESTATIC + " shim/GL11.glFog(ILjava/nio/FloatBuffer;)V",
                Opcodes.INVOKESTATIC + " old/gl/GL11.glGetFloatv(ILjava/nio/FloatBuffer;)V",
                Opcodes.INVOKESTATIC + " shim/Buffer.newBuffer(Ljava/nio/ByteBuffer;)Lold/gl/Buffer;",
                Opcodes.INVOKESTATIC + " shim/Buffer.flip(Lold/gl/Buffer;)Lold/gl/Buffer;"), calls);
        node.methods.getFirst().instructions.forEach(insn -> {
            assertFalse(insn.getOpcode() == Opcodes.NEW || insn.getOpcode() == Opcodes.DUP);
        });
        assertEquals(4, changes.size());

        // Reported once per namespace, not per class.
        tables.transform("test.Renderer2", generate("test.Renderer2", mv -> {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "old/gl/GL11", "glFog", "(ILjava/nio/FloatBuffer;)V", false);
        }));
        assertEquals(4, changes.size());
    }

    @Test
    void constructorRedirectsLeaveSuperCallsAndOtherConstructorsAlone() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/MyBuffer", null, "old/gl/Buffer", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        // super(null) matches the redirected constructor but has no NEW; new Buffer(0) isn't redirected.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "old/gl/Buffer", "<init>", "(Ljava/nio/ByteBuffer;)V", false);
        mv.visitTypeInsn(Opcodes.NEW, "old/gl/Buffer");
        mv.visitInsn(Opcodes.DUP);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "old/gl/Buffer", "<init>", "(I)V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();

        byte[] bytes = writer.toByteArray();
        assertSame(bytes, tables.transform("test.MyBuffer", bytes));
    }

    @Test
    void callTablesOnlyLookAtClassesWithTheirGate() {
        byte[] bytes = generate("test.Plain", mv -> {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "new/gl/GL11", "glFog", "(ILjava/nio/FloatBuffer;)V", false);
        });
        assertSame(bytes, tables.transform("test.Plain", bytes));
    }

    @Test
    void firstArgumentsArePassedThroughTheFilterEvenInSuperCalls() throws Exception {
        CompatTransformer filtering = CompatTransformer.builder()
                .filterFirstArgument("java/net/URLClassLoader", "<init>", "([Ljava/net/URL;Ljava/lang/ClassLoader;)V",
                        "dev/lich/transform/CompatTransformerTest$Filters", "dropNulls")
                .build();
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/MyLoader", null, "java/net/URLClassLoader", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        // super(new URL[]{null}, null), which Java 9+ rejects.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/net/URL");
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/net/URLClassLoader", "<init>", "([Ljava/net/URL;Ljava/lang/ClassLoader;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();

        byte[] bytes = filtering.transform("test.MyLoader", writer.toByteArray());
        Class<?> loader = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() {
                return defineClass("test.MyLoader", bytes, 0, bytes.length);
            }
        }.define();
        java.net.URLClassLoader instance = (java.net.URLClassLoader) loader.getDeclaredConstructor().newInstance();
        assertEquals(0, instance.getURLs().length);
    }

    /** The filter for the test above; public, since the generated class calls it from another loader. */
    public static final class Filters {
        public static java.net.URL[] dropNulls(java.net.URL[] urls) {
            return java.util.Arrays.stream(urls).filter(java.util.Objects::nonNull).toArray(java.net.URL[]::new);
        }
    }

    @Test
    void byteSearch() {
        byte[] haystack = "abcabd".getBytes();
        assertEquals(3, CompatTransformer.indexOf(haystack, "abd".getBytes()));
        assertEquals(-1, CompatTransformer.indexOf(haystack, "abe".getBytes()));
        assertEquals(-1, CompatTransformer.indexOf("ab".getBytes(), "abc".getBytes()));
    }

    /** A Java 8 class with one static method {@code run()V} whose body {@code code} writes. */
    private static byte[] generate(String name, Consumer<MethodVisitor> code) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        code.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<MethodInsnNode> calls(ClassNode node) {
        List<MethodInsnNode> calls = new ArrayList<>();
        node.methods.getFirst().instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) {
                calls.add(call);
            }
        });
        return calls;
    }

    private static MethodInsnNode onlyCall(byte[] bytes) {
        List<MethodInsnNode> calls = calls(read(bytes));
        assertEquals(1, calls.size());
        return calls.getFirst();
    }
}
