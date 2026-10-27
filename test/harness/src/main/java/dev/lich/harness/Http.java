package dev.lich.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Downloads for the harness: mod files into a cache keyed by their hash, and pack API calls (Modrinth, Technic's
 * Solder, FTB). Modpacks name files on many hosts, so every file with a known hash is checked against
 * it, and certificates are always checked.
 */
final class Http {
    /** A file's expected digest, in whichever algorithm its pack format gives: SHA-512, SHA-1 or MD5. */
    record Hash(String algorithm, String hex) {
        static Hash sha512(String hex) {
            return hex == null ? null : new Hash("SHA-512", hex);
        }

        static Hash sha1(String hex) {
            return hex == null ? null : new Hash("SHA-1", hex);
        }

        static Hash md5(String hex) {
            return hex == null ? null : new Hash("MD5", hex);
        }

        boolean matches(Path file) throws IOException {
            return hex.equalsIgnoreCase(digest(algorithm, file));
        }
    }

    private static final int ATTEMPTS = 3;
    // Modrinth asks API clients to identify themselves.
    private static final String USER_AGENT = "lich-harness/0.1 (Lich dev test harness)";

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final Path cache;

    Http(Path cache) {
        this.cache = cache;
    }

    /**
     * The file at {@code url}, from the cache if it's there. {@code hash} may be null (some packs list files without
     * one); such files are cached by URL and trusted once downloaded.
     */
    Path fetch(String url, String fileName, Hash hash) throws IOException {
        String key = hash != null ? hash.hex().toLowerCase() : "url-" + hash("SHA-256", url.getBytes(StandardCharsets.UTF_8));
        Path target = cache.resolve(key.substring(0, Math.min(key.length(), 20))).resolve(fileName);
        if (Files.isRegularFile(target) && (hash == null || hash.matches(target))) {
            return target;
        }
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        IOException failure = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                // Long enough for a pack's world save over a slow mirror (Blightfall's is 189 MB).
                HttpResponse<Path> response = client.send(request(url).timeout(Duration.ofMinutes(30)).build(),
                        HttpResponse.BodyHandlers.ofFile(part));
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }
                if (hash != null && !hash.matches(part)) {
                    throw new IOException(hash.algorithm() + " mismatch for " + url);
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return target;
            } catch (IOException e) {
                failure = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while downloading " + url);
            }
        }
        Files.deleteIfExists(part);
        throw failure;
    }

    String getString(String url) throws IOException {
        try {
            HttpResponse<String> response = client.send(request(url).timeout(Duration.ofMinutes(1)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while fetching " + url);
        }
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(uri(url)).header("User-Agent", USER_AGENT);
    }

    /**
     * {@code url} as a URI, with characters a URI can't hold percent-encoded the way a browser sends them: pack
     * manifests give some URLs raw (FTB's CurseForge links keep the file name's spaces and brackets).
     */
    static URI uri(String url) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : url.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (c < 0x80 && (Character.isLetterOrDigit(c) || "_-!.~'()*,;:$&+=/@%?#".indexOf(c) >= 0)) {
                encoded.append(c);
            } else {
                encoded.append('%').append(HexFormat.of().withUpperCase().toHexDigits(b));
            }
        }
        return URI.create(encoded.toString());
    }

    static String digest(String algorithm, Path file) throws IOException {
        MessageDigest digest = digest(algorithm);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hash(String algorithm, byte[] data) {
        return HexFormat.of().formatHex(digest(algorithm).digest(data));
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
