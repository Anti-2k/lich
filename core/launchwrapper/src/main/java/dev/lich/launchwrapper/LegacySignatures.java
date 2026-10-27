package dev.lich.launchwrapper;

import net.minecraft.launchwrapper.LogWrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertPath;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Code signers for mod jars signed with SHA-1, which Java 8 accepted and current JDKs treat as unsigned
 * ({@code jdk.jar.disabledAlgorithms}: {@code SHA1 denyAfter 2019-01-01}; Java 8 itself since 8u351). Forge gives a
 * mod the certificates of its classes' code source, mods compare them with their own fingerprint, and some stop the
 * game when it's missing (EnderTech: {@code error.fingerprint.tampered}). The players these packs were made for ran
 * an older Java 8 that still accepted the signatures.
 * <p>
 * Lich doesn't loosen the JDK's rule. Only jars on a pinned list ({@code legacy-signed-jars.txt}: the SHA-256 of the
 * whole file, each from a well-known pack and checked to verify in full) get their certificates back, read from the
 * jar's signature block. A modified or different jar doesn't match, so it stays unsigned, as the JDK sees it.
 * Entries the signature doesn't cover (no digest in the manifest) stay unsigned, as on Java 8.
 */
public final class LegacySignatures {
    private static final Map<String, String> PINNED = load();
    private static final LegacySignatures INSTANCE = new LegacySignatures(PINNED);

    private final Map<String, String> pinned;
    /** Per jar file: its signers if it's a pinned signed jar. Hashing a jar once is enough. */
    private final Map<String, Optional<CodeSigner[]>> byJar = new ConcurrentHashMap<>();

    LegacySignatures(Map<String, String> pinned) {
        this.pinned = pinned;
    }

    /** {@code jdkSigners} if the JDK found any; otherwise those of a pinned jar for an entry its signature covers. */
    public static CodeSigner[] signers(CodeSigner[] jdkSigners, JarFile jar, String entryName) {
        return INSTANCE.find(jdkSigners, jar, entryName);
    }

    CodeSigner[] find(CodeSigner[] jdkSigners, JarFile jar, String entryName) {
        if (jdkSigners != null || jar == null) {
            return jdkSigners;
        }
        Optional<CodeSigner[]> signers = byJar.computeIfAbsent(jar.getName(), name -> read(jar));
        if (signers.isEmpty() || !covered(jar, entryName)) {
            return null;
        }
        return signers.get().clone();
    }

    private Optional<CodeSigner[]> read(JarFile jar) {
        try {
            JarEntry block = signatureBlock(jar);
            // Most jars aren't signed at all: no need to hash those.
            if (block == null) {
                return Optional.empty();
            }
            String name = pinned.get(sha256(Path.of(jar.getName())));
            if (name == null) {
                return Optional.empty();
            }
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<Certificate> certificates;
            try (InputStream in = jar.getInputStream(block)) {
                certificates = new ArrayList<>(factory.generateCertificates(in));
            }
            if (certificates.isEmpty()) {
                return Optional.empty();
            }
            CertPath path = factory.generateCertPath(certificates);
            LogWrapper.info("Compat: gave %s back its SHA-1 signature (a pinned copy, see legacy-signed-jars.txt)", name);
            return Optional.of(new CodeSigner[]{new CodeSigner(path, null)});
        } catch (IOException | CertificateException e) {
            LogWrapper.warning("Couldn't read the signature of %s: %s", jar.getName(), e);
            return Optional.empty();
        }
    }

    /** {@code META-INF/X.DSA}, {@code .RSA} or {@code .EC} next to its {@code X.SF}, as jarsigner writes them. */
    private static JarEntry signatureBlock(JarFile jar) {
        for (JarEntry entry : jar.stream().toList()) {
            String name = entry.getName();
            int dot = name.lastIndexOf('.');
            if (!name.startsWith("META-INF/") || name.indexOf('/', "META-INF/".length()) >= 0 || dot < 0) {
                continue;
            }
            String extension = name.substring(dot + 1);
            if ((extension.equals("DSA") || extension.equals("RSA") || extension.equals("EC"))
                    && jar.getJarEntry(name.substring(0, dot) + ".SF") != null) {
                return entry;
            }
        }
        return null;
    }

    /** Whether the manifest has a digest for the entry, which is what makes it part of the signature. */
    private static boolean covered(JarFile jar, String entryName) {
        try {
            Manifest manifest = jar.getManifest();
            Attributes attributes = manifest == null ? null : manifest.getAttributes(entryName);
            if (attributes == null) {
                return false;
            }
            for (Object key : attributes.keySet()) {
                if (key.toString().endsWith("-Digest")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** SHA-256 → file name, from {@code legacy-signed-jars.txt}. */
    static Map<String, String> load() {
        Map<String, String> pinned = new HashMap<>();
        try (InputStream in = LegacySignatures.class.getResourceAsStream("legacy-signed-jars.txt")) {
            if (in == null) {
                throw new IllegalStateException("legacy-signed-jars.txt is missing");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\\s+", 3);
                pinned.put(fields[0].toLowerCase(), fields[1]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.copyOf(pinned);
    }
}
