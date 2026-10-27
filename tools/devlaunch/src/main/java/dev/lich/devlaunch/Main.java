package dev.lich.devlaunch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * devlaunch: a minimal Minecraft launcher for development. It resolves a version JSON (Mojang's, or a
 * local one that uses {@code inheritsFrom}), downloads the client, libraries, natives, assets and log
 * config into {@code <run-dir>/cache}, and starts the game offline in {@code <run-dir>/<version id>} (or
 * {@code --game-dir}).
 */
public final class Main {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^}]+)}");

    private Main() {
    }

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        try {
            System.exit(launch(options));
        } catch (IOException | RuntimeException e) {
            System.err.println("[devlaunch] Failed: " + e);
            e.printStackTrace();
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(130);
        }
    }

    static int launch(Options options) throws IOException, InterruptedException {
        Path cache = options.runDir().resolve("cache");
        Downloader downloader = new Downloader();
        VersionResolver resolver = new VersionResolver(downloader, cache.resolve("versions"));

        JsonObject version = options.versionJson() != null
                ? resolver.resolveFile(options.versionJson())
                : resolver.resolve(options.version());
        String id = version.get("id").getAsString();
        Path gameDir = options.gameDir() != null ? options.gameDir() : options.runDir().resolve(id);
        Files.createDirectories(gameDir);
        log("Version " + id + ", game directory " + gameDir);

        log("Libraries");
        Path nativesDir = cache.resolve("natives").resolve(id);
        List<Path> classpath = new ArrayList<>(options.extraClasspath());
        classpath.addAll(Libraries.resolve(version, cache.resolve("libraries"), nativesDir, downloader));
        classpath.add(clientJar(version, cache.resolve("versions"), downloader));

        log(options.skipAssets() ? "Asset index (objects skipped)" : "Assets");
        Path assetsRoot = Assets.prepare(version, cache.resolve("assets"), gameDir, downloader, options.skipAssets());

        Map<String, String> variables = variables(options, version, gameDir, nativesDir, assetsRoot, classpath, cache);

        List<String> command = new ArrayList<>();
        command.add(options.java().toString());
        command.addAll(jvmArguments(version, variables));
        command.addAll(loggingArgument(version, cache.resolve("log_configs"), downloader));
        command.addAll(options.jvmArgs());
        command.add(options.mainClass() != null ? options.mainClass() : version.get("mainClass").getAsString());
        command.addAll(gameArguments(version, variables));
        command.addAll(options.gameArgs());

        if (options.dryRun()) {
            log("Dry run, command:");
            command.forEach(part -> System.out.println("  " + part));
            return 0;
        }
        log("Starting " + options.java());
        Process process = new ProcessBuilder(command).directory(gameDir.toFile()).inheritIO().start();
        int exit = process.waitFor();
        log("Game exited with code " + exit);
        return exit;
    }

    private static Path clientJar(JsonObject version, Path versionsDir, Downloader downloader) throws IOException {
        String jar = version.get("jar").getAsString();
        JsonObject client = version.getAsJsonObject("downloads").getAsJsonObject("client");
        return downloader.fetch(client.get("url").getAsString(), versionsDir.resolve(jar).resolve(jar + ".jar"),
                client.get("sha1").getAsString());
    }

    private static Map<String, String> variables(Options options, JsonObject version, Path gameDir, Path nativesDir,
                                                 Path assetsRoot, List<Path> classpath, Path cache) {
        // The same UUID the game itself derives for an offline player name.
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + options.username()).getBytes(StandardCharsets.UTF_8));
        Map<String, String> variables = new HashMap<>();
        variables.put("auth_player_name", options.username());
        variables.put("auth_uuid", uuid.toString().replace("-", ""));
        variables.put("auth_access_token", "0");
        variables.put("auth_session", "-");
        variables.put("auth_xuid", "0");
        variables.put("clientid", "0");
        variables.put("user_type", "legacy");
        variables.put("user_properties", "{}");
        variables.put("version_name", version.get("id").getAsString());
        variables.put("version_type", version.has("type") ? version.get("type").getAsString() : "release");
        variables.put("game_directory", gameDir.toString());
        variables.put("assets_root", assetsRoot.toString());
        variables.put("game_assets", assetsRoot.toString());
        variables.put("assets_index_name", version.getAsJsonObject("assetIndex").get("id").getAsString());
        variables.put("natives_directory", nativesDir.toString());
        variables.put("library_directory", cache.resolve("libraries").toString());
        variables.put("classpath_separator", File.pathSeparator);
        variables.put("classpath", String.join(File.pathSeparator, classpath.stream().map(Path::toString).toList()));
        variables.put("launcher_name", "lich-devlaunch");
        variables.put("launcher_version", "0");
        return variables;
    }

    private static List<String> jvmArguments(JsonObject version, Map<String, String> variables) {
        List<String> arguments = new ArrayList<>();
        if (version.has("arguments") && version.getAsJsonObject("arguments").has("jvm")) {
            arguments.addAll(evaluate(version.getAsJsonObject("arguments").getAsJsonArray("jvm"), variables));
        }
        // Legacy versions (1.7.10, 1.8.9) have no JVM arguments in their JSON; the launcher supplies these.
        // A Lich profile may add only its own flags on top of a legacy parent, so check for -cp too.
        if (!arguments.contains(variables.get("classpath"))) {
            arguments.add(0, "-Djava.library.path=" + variables.get("natives_directory"));
            arguments.add(1, "-cp");
            arguments.add(2, variables.get("classpath"));
        }
        return arguments;
    }

    private static List<String> gameArguments(JsonObject version, Map<String, String> variables) {
        if (version.has("arguments") && version.getAsJsonObject("arguments").has("game")) {
            List<String> arguments = evaluate(version.getAsJsonObject("arguments").getAsJsonArray("game"), variables);
            if (!arguments.isEmpty()) {
                return arguments;
            }
        }
        List<String> arguments = new ArrayList<>();
        for (String part : version.get("minecraftArguments").getAsString().split(" ")) {
            if (!part.isEmpty()) {
                arguments.add(substitute(part, variables));
            }
        }
        return arguments;
    }

    private static List<String> loggingArgument(JsonObject version, Path logConfigsDir, Downloader downloader)
            throws IOException {
        if (!version.has("logging") || !version.getAsJsonObject("logging").has("client")) {
            return List.of();
        }
        // These configs are Mojang's Log4Shell fix for old versions, so always use them.
        JsonObject client = version.getAsJsonObject("logging").getAsJsonObject("client");
        JsonObject file = client.getAsJsonObject("file");
        Path config = downloader.fetch(file.get("url").getAsString(), logConfigsDir.resolve(file.get("id").getAsString()),
                file.get("sha1").getAsString());
        return List.of(client.get("argument").getAsString().replace("${path}", config.toString()));
    }

    /** Expands a new-style argument list: plain strings, or {rules, value} objects whose value is a string or array. */
    private static List<String> evaluate(JsonArray list, Map<String, String> variables) {
        List<String> arguments = new ArrayList<>();
        for (JsonElement element : list) {
            if (element.isJsonPrimitive()) {
                arguments.add(substitute(element.getAsString(), variables));
                continue;
            }
            JsonObject conditional = element.getAsJsonObject();
            if (!Rules.allowed(conditional.get("rules"))) {
                continue;
            }
            JsonElement value = conditional.get("value");
            if (value.isJsonArray()) {
                value.getAsJsonArray().forEach(v -> arguments.add(substitute(v.getAsString(), variables)));
            } else {
                arguments.add(substitute(value.getAsString(), variables));
            }
        }
        return arguments;
    }

    private static String substitute(String argument, Map<String, String> variables) {
        Matcher matcher = VARIABLE.matcher(argument);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            // Unknown variables stay as written, as in Mojang's launcher.
            String value = variables.getOrDefault(matcher.group(1), matcher.group());
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    static void log(String message) {
        System.out.println("[devlaunch] " + message);
    }
}
