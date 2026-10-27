package dev.lich.harness;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The Java 8 baselines run the game's own LWJGL 2, where Lich's {@code -Dlich.dev.noMouseGrab} doesn't exist. Harness
 * runs share the desktop with whoever is using it, so this builds a copy of LWJGL 2's {@code Mouse} that still keeps
 * the grabbed state the game sees, but never locks the real cursor or moves it: its two calls into the native input
 * layer that do ({@code grabMouse}, {@code setCursorPosition}) just drop their arguments. It goes in a copy of the
 * whole LWJGL jar, first on the class path: LWJGL 2.9.1 (1.7.10's) seals its packages, so {@code org.lwjgl.input}
 * must come from one jar. LaunchWrapper excludes {@code org.lwjgl.}, so the game gets it from the system class loader.
 */
final class NoGrabMouse {
    private static final String MOUSE = "org/lwjgl/input/Mouse.class";
    private static final String INPUT = "org/lwjgl/opengl/InputImplementation";

    private NoGrabMouse() {
    }

    /** Writes a copy of {@code lwjglJar} with the patched {@code Mouse} to {@code target}. */
    static Path build(Path lwjglJar, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        boolean found = false;
        try (ZipFile zip = new ZipFile(lwjglJar.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
            for (ZipEntry entry : zip.stream().toList()) {
                String name = entry.getName();
                // A signature wouldn't match the patched class any more.
                if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                if (name.equals(MOUSE)) {
                    int[] patched = new int[1];
                    bytes = patch(bytes, patched);
                    if (patched[0] != 3) {
                        // Two setCursorPosition calls (setCursorPosition and setGrabbed) and one grabMouse, in 2.9.x.
                        throw new IOException("Expected 3 native cursor calls in LWJGL 2's Mouse, found " + patched[0]);
                    }
                    found = true;
                }
                out.putNextEntry(new JarEntry(name));
                out.write(bytes);
                out.closeEntry();
            }
        }
        if (!found) {
            Files.delete(target);
            throw new IOException(lwjglJar + " has no " + MOUSE);
        }
        return target;
    }

    static byte[] patch(byte[] original, int[] count) {
        ClassReader reader = new ClassReader(original);
        // Same stack depth as before (the pops replace a call that consumed the same values), and class version 49
        // has no stack map frames to recompute.
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        if (opcode == Opcodes.INVOKEINTERFACE && owner.equals(INPUT)) {
                            if (method.equals("grabMouse") && desc.equals("(Z)V")) {
                                super.visitInsn(Opcodes.POP2);
                                count[0]++;
                                return;
                            }
                            if (method.equals("setCursorPosition") && desc.equals("(II)V")) {
                                super.visitInsn(Opcodes.POP2);
                                super.visitInsn(Opcodes.POP);
                                count[0]++;
                                return;
                            }
                        }
                        super.visitMethodInsn(opcode, owner, method, desc, itf);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
