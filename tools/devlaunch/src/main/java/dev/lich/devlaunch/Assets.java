package dev.lich.devlaunch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Downloads a version's asset index and objects, and lays out legacy "virtual" asset directories. */
final class Assets {
    private static final String OBJECTS_URL = "https://resources.download.minecraft.net/";
    private static final int PARALLEL_DOWNLOADS = 16;

    private Assets() {
    }

    /** Returns the directory the game should get as {@code assets_root} / {@code game_assets}. */
    static Path prepare(JsonObject version, Path assetsDir, Path gameDir, Downloader downloader, boolean skipObjects)
            throws IOException {
        JsonObject reference = version.getAsJsonObject("assetIndex");
        String id = reference.get("id").getAsString();
        Path indexFile = downloader.fetch(reference.get("url").getAsString(),
                assetsDir.resolve("indexes").resolve(id + ".json"), reference.get("sha1").getAsString());
        if (skipObjects) {
            return assetsDir;
        }

        JsonObject index = VersionResolver.read(indexFile);
        JsonObject objects = index.getAsJsonObject("objects");
        // Indexes list the same hash under several names; download each object once.
        Set<String> hashes = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            hashes.add(entry.getValue().getAsJsonObject().get("hash").getAsString());
        }
        download(hashes, assetsDir, downloader);

        boolean virtual = index.has("virtual") && index.get("virtual").getAsBoolean();
        boolean mapToResources = index.has("map_to_resources") && index.get("map_to_resources").getAsBoolean();
        if (!virtual && !mapToResources) {
            return assetsDir;
        }
        // Pre-1.7 indexes want the files under their real names instead of the hashed object store.
        Path target = virtual ? assetsDir.resolve("virtual").resolve(id) : gameDir.resolve("resources");
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            Path file = target.resolve(entry.getKey());
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.copy(objectPath(assetsDir, entry.getValue().getAsJsonObject().get("hash").getAsString()), file);
            }
        }
        return virtual ? target : assetsDir;
    }

    private static void download(Set<String> hashes, Path assetsDir, Downloader downloader) throws IOException {
        AtomicInteger done = new AtomicInteger();
        Semaphore permits = new Semaphore(PARALLEL_DOWNLOADS);
        List<Future<Path>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String hash : hashes) {
                futures.add(executor.submit(() -> {
                    permits.acquire();
                    try {
                        return downloader.fetch(OBJECTS_URL + hash.substring(0, 2) + "/" + hash, objectPath(assetsDir, hash), hash);
                    } finally {
                        permits.release();
                        int count = done.incrementAndGet();
                        if (count % 250 == 0 || count == hashes.size()) {
                            Main.log("Assets " + count + "/" + hashes.size());
                        }
                    }
                }));
            }
            for (Future<Path> future : futures) {
                future.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading assets", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        }
    }

    private static Path objectPath(Path assetsDir, String hash) {
        return assetsDir.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
    }
}
