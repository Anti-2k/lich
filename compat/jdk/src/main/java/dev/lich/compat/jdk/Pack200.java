package dev.lich.compat.jdk;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

/**
 * Stand-in for {@code java.util.jar.Pack200}, removed in Java 14. The compat pass renames references to
 * the JDK class to this one, so FML's {@code ClassPatchManager} can still unpack
 * {@code binpatches.pack.lzma}. Same shape as the JDK API; the work is done by commons-compress's
 * Pack200 port, loaded in isolation by {@link IsolatedLibraries}.
 */
public final class Pack200 {
    private static final String FACADE = "org.apache.commons.compress.java.util.jar.Pack200";
    private static final String ARCHIVE = "org.apache.commons.compress.harmony.unpack200.Archive";

    private Pack200() {
    }

    /**
     * An unpacker that drives commons-compress's {@code Archive} directly rather than through its
     * {@code Pack200} facade, to work around three things the JDK's unpacker never did; see
     * {@link #opaque}, and the comments in {@code unpack}.
     */
    public static Unpacker newUnpacker() {
        return new Unpacker() {
            private final SortedMap<String, String> properties = new TreeMap<>();

            @Override
            public void unpack(InputStream in, JarOutputStream out) throws IOException {
                // commons-compress logs to System.out and closes its log stream after every segment,
                // which would close the game's stdout. Give it a log file instead (quiet: it stays empty).
                Path log = Files.createTempFile("lich-pack200", ".log");
                // Like the JDK's unpacker, this closes its input but not its output.
                try (in) {
                    Class<?> archiveType = type(ARCHIVE);
                    Object archive = archiveType.getConstructor(InputStream.class, JarOutputStream.class)
                            .newInstance(opaque(in), out);
                    callIo(archiveType, archive, "setQuiet", new Class<?>[]{boolean.class}, true);
                    callIo(archiveType, archive, "setLogFile", new Class<?>[]{String.class}, log.toString());
                    callIo(archiveType, archive, "unpack", new Class<?>[0]);
                } catch (InvocationTargetException e) {
                    throw new IOException("Pack200 unpacking failed", e.getCause());
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("Lich's nested commons-compress has no usable Archive", e);
                } finally {
                    Files.deleteIfExists(log);
                }
                // commons-compress leaves the last entry open, its data still in the deflater. FML reads
                // the jar's bytes without closing the stream, so the entry has to be complete now.
                out.closeEntry();
            }

            @Override
            public void unpack(File in, JarOutputStream out) throws IOException {
                unpack(new BufferedInputStream(Files.newInputStream(in.toPath())), out);
            }

            @Override
            public SortedMap<String, String> properties() {
                // Accepted and ignored: none of the JDK's unpacker properties change the result.
                return properties;
            }
        };
    }

    public static Packer newPacker() {
        Object delegate = call(facade(), null, "newPacker", new Class<?>[0]);
        return new Packer() {
            @Override
            public void pack(JarFile in, OutputStream out) throws IOException {
                callIo(packer(), delegate, "pack", new Class<?>[]{JarFile.class, OutputStream.class}, in, out);
            }

            @Override
            public void pack(JarInputStream in, OutputStream out) throws IOException {
                callIo(packer(), delegate, "pack", new Class<?>[]{JarInputStream.class, OutputStream.class}, in, out);
            }

            @Override
            @SuppressWarnings("unchecked")
            public SortedMap<String, String> properties() {
                return (SortedMap<String, String>) call(packer(), delegate, "properties", new Class<?>[0]);
            }
        };
    }

    /** The parts of {@code java.util.jar.Pack200.Unpacker} that old code calls. */
    public interface Unpacker {
        void unpack(InputStream in, JarOutputStream out) throws IOException;

        void unpack(File in, JarOutputStream out) throws IOException;

        SortedMap<String, String> properties();
    }

    /** The parts of {@code java.util.jar.Pack200.Packer} that old code calls. */
    public interface Packer {
        void pack(JarFile in, OutputStream out) throws IOException;

        void pack(JarInputStream in, OutputStream out) throws IOException;

        SortedMap<String, String> properties();
    }

    /**
     * Hides what kind of stream {@code in} is. commons-compress "unwraps" any {@code FilterInputStream}
     * by reflection to find the file underneath and bound the read by its size. FML hands it an
     * {@code LzmaInputStream}, which is a FilterInputStream, so commons-compress would skip the LZMA
     * layer, read the compressed bytes, and silently unpack nothing.
     */
    private static InputStream opaque(InputStream in) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                return in.read();
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                return in.read(buffer, offset, length);
            }
        };
    }

    private static Class<?> facade() {
        return type(FACADE);
    }

    private static Class<?> packer() {
        return type(FACADE + "$Packer");
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name, true, IsolatedLibraries.pack200());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Lich's nested commons-compress is incomplete", e);
        }
    }

    private static Object call(Class<?> owner, Object target, String name, Class<?>[] types, Object... args) {
        try {
            return invoke(owner, target, name, types, args);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void callIo(Class<?> owner, Object target, String name, Class<?>[] types, Object... args) throws IOException {
        invoke(owner, target, name, types, args);
    }

    private static Object invoke(Class<?> owner, Object target, String name, Class<?>[] types, Object... args) throws IOException {
        try {
            Method method = owner.getMethod(name, types);
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException(cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Can't call commons-compress " + owner.getName() + "." + name, e);
        }
    }
}
