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
 * Downloads for the harness: mod files into a cache keyed by their hash, and Modrinth API calls. Modpacks name
 * files on many hosts, so every file with a known hash is checked against it, and certificates are always checked.
 */
final class Http {
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
     * The file at {@code url}, from the cache if it's there. {@code sha512} may be null (some packs list files
     * without a hash); such files are cached by URL and trusted once downloaded.
     */
    Path fetch(String url, String fileName, String sha512) throws IOException {
        String key = sha512 != null ? sha512.toLowerCase() : "url-" + hash("SHA-256", url.getBytes(StandardCharsets.UTF_8));
        Path target = cache.resolve(key.substring(0, Math.min(key.length(), 20))).resolve(fileName);
        if (Files.isRegularFile(target) && (sha512 == null || sha512.equalsIgnoreCase(sha512(target)))) {
            return target;
        }
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        IOException failure = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                HttpResponse<Path> response = client.send(request(url).timeout(Duration.ofMinutes(5)).build(),
                        HttpResponse.BodyHandlers.ofFile(part));
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }
                if (sha512 != null && !sha512.equalsIgnoreCase(sha512(part))) {
                    throw new IOException("SHA-512 mismatch for " + url);
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
        return HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT);
    }

    static String sha512(Path file) throws IOException {
        MessageDigest digest = digest("SHA-512");
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
