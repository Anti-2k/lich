package dev.lich.devlaunch;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/** Cached, SHA-1-checked downloads. Safe to use from many threads at once. */
final class Downloader {
    private static final int ATTEMPTS = 3;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    /**
     * Downloads {@code url} to {@code target}, unless the target already exists and matches {@code sha1}.
     * With no known hash, any existing file is trusted, so that cached files keep working offline.
     */
    Path fetch(String url, Path target, String sha1) throws IOException {
        if (Files.isRegularFile(target) && (sha1 == null || sha1.equalsIgnoreCase(sha1(target)))) {
            return target;
        }
        Files.createDirectories(target.getParent());
        // A partial file never takes the real name, so an interrupted run can't leave a corrupt cache entry.
        Path part = target.resolveSibling(target.getFileName() + ".part");
        IOException failure = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).build();
                HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(part));
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }
                if (sha1 != null) {
                    String actual = sha1(part);
                    if (!sha1.equalsIgnoreCase(actual)) {
                        throw new IOException("SHA-1 mismatch for " + url + ": expected " + sha1 + ", got " + actual);
                    }
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

    static String sha1(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
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
}
