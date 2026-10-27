package dev.lich.launchwrapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.CodeSigner;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A jar signed the way EnderTech's is (SHA1withDSA), made with the JDK's own keytool and jarsigner. */
class LegacySignaturesTest {
    private static final String CLASS = "mod/Main.class";
    /** Not part of the signature: added to the jar after it was signed. */
    private static final String UNSIGNED = "mod/Added.class";

    @TempDir
    static Path dir;
    static Path jar;
    static Certificate certificate;

    @BeforeAll
    static void signJar() throws Exception {
        Path signed = dir.resolve("signed.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(signed))) {
            out.putNextEntry(new JarEntry(CLASS));
            out.write(new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe});
        }
        Path keystore = dir.resolve("keys.p12");
        run("keytool", "-genkeypair", "-alias", "mod", "-keyalg", "DSA", "-keysize", "1024", "-sigalg", "SHA1withDSA",
                "-dname", "CN=Mod Author", "-validity", "3650", "-keystore", keystore.toString(),
                "-storepass", "secret12", "-keypass", "secret12");
        run("jarsigner", "-keystore", keystore.toString(), "-storepass", "secret12", "-sigalg", "SHA1withDSA",
                "-digestalg", "SHA-1", signed.toString(), "mod");
        jar = dir.resolve("mod.jar");
        try (ZipFile in = new ZipFile(signed.toFile()); ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (ZipEntry entry : in.stream().toList()) {
                out.putNextEntry(new ZipEntry(entry.getName()));
                try (InputStream data = in.getInputStream(entry)) {
                    data.transferTo(out);
                }
            }
            out.putNextEntry(new ZipEntry(UNSIGNED));
            out.write(new byte[]{(byte) 0xca, (byte) 0xfe});
        }
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            keys.load(in, "secret12".toCharArray());
        }
        certificate = keys.getCertificate("mod");
    }

    @Test
    void theJdkTreatsTheJarAsUnsigned() throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            assertNull(signersFromJdk(file, CLASS));
        }
    }

    @Test
    void aPinnedJarGetsItsCertificateBack() throws IOException {
        LegacySignatures signatures = new LegacySignatures(Map.of(LegacySignatures.sha256(jar), "mod.jar"));
        try (JarFile file = new JarFile(jar.toFile())) {
            CodeSigner[] signers = signatures.find(signersFromJdk(file, CLASS), file, CLASS);
            assertNotNull(signers);
            assertEquals(List.of(certificate), signers[0].getSignerCertPath().getCertificates());
            // What the signature doesn't cover stays unsigned, as on Java 8.
            assertNull(signatures.find(signersFromJdk(file, UNSIGNED), file, UNSIGNED));
        }
    }

    @Test
    void anyOtherJarStaysUnsigned() throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            assertNull(new LegacySignatures(Map.of()).find(null, file, CLASS));
        }
        // A modified copy: same signature files, different bytes.
        Path modified = dir.resolve("modified.jar");
        Files.copy(jar, modified);
        Files.write(modified, new byte[]{0}, StandardOpenOption.APPEND);
        LegacySignatures signatures = new LegacySignatures(Map.of(LegacySignatures.sha256(jar), "mod.jar"));
        try (JarFile file = new JarFile(modified.toFile())) {
            assertNull(signatures.find(null, file, CLASS));
        }
    }

    @Test
    void signersTheJdkFoundAreKept() throws IOException {
        CodeSigner[] fromJdk = new CodeSigner[0];
        try (JarFile file = new JarFile(jar.toFile())) {
            assertSame(fromJdk, new LegacySignatures(Map.of()).find(fromJdk, file, CLASS));
        }
    }

    @Test
    void thePinnedListLoads() {
        Map<String, String> pinned = LegacySignatures.load();
        assertEquals("EnderTech-1.7.10-0.3.2.405.jar", pinned.get("cb264e0777cb934354b06c4c478685645ebb543a8cb13bdf5e0bf52cf7de9b81"));
        pinned.keySet().forEach(hash -> assertEquals(64, hash.length(), hash));
    }

    /** The entry has to be read fully before the JDK knows its signers. */
    private static CodeSigner[] signersFromJdk(JarFile file, String name) throws IOException {
        JarEntry entry = file.getJarEntry(name);
        try (InputStream in = file.getInputStream(entry)) {
            in.readAllBytes();
        }
        return entry.getCodeSigners();
    }

    private static void run(String tool, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", tool).toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IOException(tool + " failed: " + output);
        }
    }
}
