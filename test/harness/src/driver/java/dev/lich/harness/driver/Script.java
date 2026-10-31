package dev.lich.harness.driver;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The steps the driver plays, in the order {@code lich.harness.steps} lists them (the harness picks which). Each step
 * waits on what the game does, not on a clock: a teleport is done when the chunks around the player stop arriving,
 * not after 20 seconds. The few fixed waits left are short dwells, so that what was just loaded also ticks and
 * renders for a moment. Every step sends {@code start <step>}, then {@code end <step>} with its numbers, or
 * {@code fail <step> -- reason}, which ends the script.
 */
final class Script {
    /** Blocks that aren't meant to be placed: no item, or they break or replace themselves at once. */
    private static final Set<String> TECHNICAL_BLOCKS = new HashSet<>(Arrays.asList(
            "minecraft:air", "minecraft:portal", "minecraft:end_portal", "minecraft:piston_head",
            "minecraft:piston_extension", "minecraft:fire", "minecraft:lit_furnace", "minecraft:flowing_water",
            "minecraft:flowing_lava", "minecraft:water", "minecraft:lava", "minecraft:double_stone_slab",
            "minecraft:double_wooden_slab", "minecraft:double_stone_slab2", "minecraft:barrier",
            "minecraft:command_block", "minecraft:mob_spawner", "minecraft:tnt", "minecraft:dragon_egg"));
    /**
     * Chunks around the player that must be loaded and populated before a place counts as arrived at. View distance
     * is 8 (both profiles' options), and a chunk is populated once its neighbours exist, so 6 is what's sure to come.
     */
    private static final int SETTLE_RADIUS = 6;
    private static final int GALLERY_Y = 200;
    private static final int GALLERY_ROW = 32;
    /** A chat line holds 100 characters. */
    private static final int CHAT_LIMIT = 100;
    /** The frame cap while the world runs, which leaves the integrated server the CPU; unlimited in menus. */
    private static final int WORLD_FPS = 30;
    private static final int MENU_FPS = 260;

    private final Channel channel;
    private final File gameDir;
    private Game game;
    private ScreenWatcher watcher;
    private volatile String step = "boot";
    private String folder;
    private double[] spawn;
    /** The gallery's tile entities before the save, by class: the reload must bring the same ones back. */
    private Map<String, Integer> galleryTileEntities;
    private int[] galleryBox;

    Script(Channel channel, File gameDir) {
        this.channel = channel;
        this.gameDir = gameDir;
    }

    String step() {
        return step;
    }

    /** The steps, then {@code exit}, which the harness follows to the end itself: the driver goes with the game. */
    void run() {
        String[] steps = System.getProperty("lich.harness.steps", "boot").split(",");
        try {
            for (String name : steps) {
                step = name;
                channel.send("start", name, null, null);
                channel.send("end", name, run(name), null);
            }
            step = "exit";
            channel.send("start", "exit", null, null);
            game.shutdown();
        } catch (Throwable t) {
            String screen = "";
            try {
                screen = game == null ? "" : " (screen " + Game.name(game.screen()) + ")";
            } catch (Throwable ignored) {
                // Only for the message.
            }
            // Anything but a StepFailed is the driver's own bug: the harness says so instead of blaming the pack.
            boolean byGame = t instanceof StepFailed;
            channel.send("fail", step, byGame ? null : "driver=true", (byGame ? t.getMessage() : t.toString()) + screen + at(t));
            channel.trace(t);
        }
    }

    private String run(String name) throws Exception {
        switch (name) {
            case "boot":
                return boot();
            case "mods":
                return mods();
            case "server":
                return server();
            case "world":
                return world();
            case "spawn":
                return settle(5);
            case "tour":
                return tour();
            case "inventory":
                return inventory();
            case "gallery":
                return gallery();
            case "hold":
                return hold();
            case "save":
                return save();
            case "reload":
                return reload();
            default:
                throw new IllegalArgumentException("Unknown step " + name);
        }
    }

    /** The first screen is up and the game loop runs; then until mods' own first screens have settled. */
    private String boot() throws Exception {
        game = Game.await();
        watcher = new ScreenWatcher(game, channel, this);
        Thread thread = new Thread(watcher, "Lich harness screens");
        thread.setDaemon(true);
        thread.start();
        game.frames(1);
        // Mods may open a screen of their own right after the first one (news, changelogs, a config preset).
        Object screen = game.screen();
        long start = System.nanoTime();
        long changed = start;
        while (System.nanoTime() - changed < 2_000_000_000L && seconds(start) < 15) {
            checkErrorScreen();
            Thread.sleep(250);
            if (game.screen() != screen) {
                screen = game.screen();
                changed = System.nanoTime();
            }
        }
        checkErrorScreen();
        game.setFpsLimit(MENU_FPS);
        return Channel.field("screen", Game.name(screen)) + " forge=" + (game.fml != null) + " " + game.stats();
    }

    /**
     * FML's mod list: each mod selected in turn (its logo and description drawn), and each config screen opened for
     * two frames. A config screen that can't be made is a problem of that mod, not the end of the run.
     */
    private String mods() throws Exception {
        Object parent = game.screen();
        Object list = game.openModList(parent);
        if (list == null) {
            return "mods=0";
        }
        game.frames(2);
        List<?> mods = new ArrayList<Object>(game.mods(list));
        int configs = 0;
        for (int i = 0; i < mods.size(); i++) {
            checkErrorScreen();
            String id = Game.modId(mods.get(i));
            channel.send("at", "mods", Channel.field("mod", id), null);
            game.selectMod(list, i);
            game.frames(2);
            channel.send("at", "mods", Channel.field("mod", id) + " config=true", null);
            try {
                if (!game.openConfig(mods.get(i), list)) {
                    continue;
                }
            } catch (Throwable t) {
                channel.send("problem", "mods", Channel.field("mod", id), "config screen: " + cause(t));
                game.display(list);
                continue;
            }
            game.frames(2);
            game.display(list);
            game.frames(1);
            configs++;
        }
        game.display(parent);
        game.frames(1);
        return "mods=" + mods.size() + " configs=" + configs;
    }

    /** Joins the test server, stays, and leaves as the Disconnect button does. */
    private String server() throws Exception {
        String[] address = System.getProperty("lich.harness.server", "127.0.0.1:25565").split(":");
        String host = address[0];
        int port = Integer.parseInt(address[1]);
        // The harness starts the server while the game boots; it may still be starting.
        long start = System.nanoTime();
        while (!listening(host, port)) {
            if (seconds(start) > 180) {
                throw new StepFailed("The test server isn't listening on " + port + " after 180 s");
            }
            Thread.sleep(1000);
        }
        game.connect(host, port);
        start = System.nanoTime();
        while (!game.inRemoteWorld()) {
            failIfDisconnected();
            checkErrorScreen();
            if (seconds(start) > 120) {
                throw new StepFailed("Not in the server's world 120 s after connecting");
            }
            Thread.sleep(250);
        }
        long joined = seconds(start);
        game.setFpsLimit(WORLD_FPS);
        int stay = Integer.getInteger("lich.harness.serverStay", 15);
        for (int i = 0; i < stay * 4; i++) {
            Thread.sleep(250);
            failIfDisconnected();
        }
        String stats = game.stats();
        game.saveAndQuit();
        start = System.nanoTime();
        while (game.hasWorld()) {
            if (seconds(start) > 60) {
                throw new StepFailed("Still in the server's world 60 s after disconnecting");
            }
            Thread.sleep(250);
        }
        game.setFpsLimit(MENU_FPS);
        return "joinSeconds=" + joined + " stayed=" + stay + " " + stats;
    }

    private static boolean listening(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void failIfDisconnected() throws Exception {
        String reason = game.disconnectedReason();
        if (reason != null) {
            throw new StepFailed("Disconnected: " + reason);
        }
    }

    /** Creates the world (or opens the map the pack ships) and waits until the player is in it. */
    private String world() throws Exception {
        String save = System.getProperty("lich.harness.save");
        long start = System.nanoTime();
        if (save == null) {
            folder = "harness";
            long seed = Long.getLong("lich.harness.seed", 1L);
            String type = System.getProperty("lich.harness.worldType", "default");
            channel.send("note", "world", Channel.field("seed", seed) + " " + Channel.field("type", type), null);
            game.createWorld(folder, "Harness", seed, type);
        } else {
            folder = save;
            channel.send("note", "world", Channel.field("save", save), null);
            game.loadWorld(folder, folder);
        }
        awaitWorld();
        game.allowCommands();
        game.setFpsLimit(WORLD_FPS);
        spawn = game.playerXZ();
        return "loadSeconds=" + seconds(start);
    }

    private void awaitWorld() throws Exception {
        long start = System.nanoTime();
        while (!game.inWorld()) {
            checkErrorScreen();
            if (seconds(start) > 300) {
                throw new StepFailed("Not in the world 300 s after starting its server");
            }
            Thread.sleep(250);
        }
    }

    /**
     * Waits until the world around the player is there: every chunk within {@value #SETTLE_RADIUS} chunks loaded and
     * populated on the server (so the mods' worldgen ran on it), and the player's chunk on the client; at most 120 s.
     * Then stays {@code dwell} seconds more, so what came in also ticks and renders. Not "until the chunk counts stop
     * changing": after a teleport the server unloads the old area a few chunks a tick, long after the new one is ready.
     */
    private String settle(int dwell) throws Exception {
        long start = System.nanoTime();
        int missing = -1;
        while (seconds(start) < 120) {
            checkInWorld();
            missing = game.chunksNotReady(SETTLE_RADIUS);
            if (missing == 0) {
                break;
            }
            Thread.sleep(250);
        }
        long settleMillis = (System.nanoTime() - start) / 1_000_000L;
        stay(dwell);
        return "settled=" + (missing == 0) + " settleSeconds=" + settleMillis / 1000 + "." + settleMillis % 1000 / 100
                + (missing > 0 ? " chunksMissing=" + missing : "") + " " + game.stats();
    }

    /** Teleports to {@code lich.harness.tourStops} points around spawn, so new chunks generate in many biomes. */
    private String tour() throws Exception {
        int stops = Integer.getInteger("lich.harness.tourStops", 8);
        int radius = Integer.getInteger("lich.harness.tourRadius", 1000);
        int unsettled = 0;
        for (int i = 0; i < stops; i++) {
            double angle = 2 * Math.PI * i / stops;
            long x = Math.round(spawn[0] + radius * Math.cos(angle));
            long z = Math.round(spawn[1] + radius * Math.sin(angle));
            channel.send("at", "tour", "stop=" + (i + 1) + "/" + stops + " x=" + x + " z=" + z, null);
            teleport(x, z);
            if (settle(2).startsWith("settled=false")) {
                unsettled++;
            }
        }
        teleport(Math.round(spawn[0]), Math.round(spawn[1]));
        settle(2);
        return "stops=" + stops + " unsettled=" + unsettled + " " + game.stats();
    }

    /** From high up: the player falls to whatever generates below, land or sea (creative: no damage). */
    private void teleport(long x, long z) throws Exception {
        game.chat("/tp " + x + " 250 " + z);
        long start = System.nanoTime();
        while (true) {
            double[] at = game.playerXZ();
            if (Math.abs(at[0] - x) < 32 && Math.abs(at[1] - z) < 32) {
                return;
            }
            checkInWorld();
            if (seconds(start) > 30) {
                throw new StepFailed("The player isn't at " + x + ", " + z + " 30 s after /tp");
            }
            Thread.sleep(250);
        }
    }

    /**
     * The creative inventory: every tab shown, and the search tab, which lists every item, scrolled page by page, so
     * every item is drawn once.
     */
    private String inventory() throws Exception {
        game.setFpsLimit(MENU_FPS);
        Object creative = game.openCreative();
        game.frames(2);
        int tabs = 0;
        int pages = 0;
        for (Object tab : game.creativeTabs()) {
            checkInWorld();
            String label = Game.tabLabel(tab);
            channel.send("at", "inventory", Channel.field("tab", label), null);
            int positions = game.selectTab(creative, tab);
            game.frames(1);
            tabs++;
            pages++;
            if (label.equals("search")) {
                for (int p = 1; p < positions; p++) {
                    game.scroll(creative, p / (float) (positions - 1));
                    game.frames(1);
                    pages++;
                }
            }
        }
        game.display(null);
        game.frames(1);
        game.setFpsLimit(WORLD_FPS);
        return "tabs=" + tabs + " pages=" + pages;
    }

    /**
     * One of every block, placed above spawn in rows of {@value #GALLERY_ROW}, two apart, through {@code /setblock}
     * as a player would; left there for the save, so the reload can check their tile entities came back.
     */
    private String gallery() throws Exception {
        Set<String> excluded = new HashSet<>(TECHNICAL_BLOCKS);
        String extra = System.getProperty("lich.harness.galleryExclude", "");
        if (!extra.isEmpty()) {
            excluded.addAll(Arrays.asList(extra.split(",")));
        }
        int baseX = (int) Math.floor(spawn[0]) - GALLERY_ROW;
        int baseZ = (int) Math.floor(spawn[1]) - GALLERY_ROW;
        int placed = 0;
        int skipped = 0;
        List<String> batch = new ArrayList<>();
        for (String block : game.blockNames()) {
            if (excluded.contains(block)) {
                continue;
            }
            int x = baseX + placed % GALLERY_ROW * 2;
            int z = baseZ + placed / GALLERY_ROW % GALLERY_ROW * 2;
            int y = GALLERY_Y + placed / (GALLERY_ROW * GALLERY_ROW) * 4;
            String command = "/setblock " + x + " " + y + " " + z + " " + block + " 0";
            if (command.length() > CHAT_LIMIT) {
                skipped++;
                continue;
            }
            batch.add(command);
            placed++;
            // A batch per frame; the last block sent is where a crash happened, near enough.
            if (batch.size() == 32) {
                channel.send("at", "gallery", Channel.field("block", block), null);
                game.chat(batch);
                batch.clear();
                checkInWorld();
            }
        }
        if (!batch.isEmpty()) {
            game.chat(batch);
        }
        int layers = placed / (GALLERY_ROW * GALLERY_ROW) + 1;
        galleryBox = new int[]{baseX, GALLERY_Y, baseZ, baseX + GALLERY_ROW * 2, GALLERY_Y + layers * 4, baseZ + GALLERY_ROW * 2};
        game.chat("/tp " + (baseX + GALLERY_ROW) + " " + (GALLERY_Y + layers * 4 + 8) + " " + (baseZ + GALLERY_ROW));
        stay(10);
        galleryTileEntities = galleryTileEntities();
        return "blocks=" + placed + " tooLong=" + skipped + " galleryTileEntities=" + total(galleryTileEntities) + " "
                + game.stats();
    }

    private Map<String, Integer> galleryTileEntities() throws Exception {
        int[] b = galleryBox;
        return game.tileEntitiesIn(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    private String hold() throws Exception {
        stay(Integer.getInteger("lich.harness.hold", 20));
        return game.stats();
    }

    /** Save and quit, as the pause menu does; then the world's files must be there. */
    private String save() throws Exception {
        long start = System.nanoTime();
        awaitStopped(game.saveAndQuit());
        File world = new File(new File(gameDir, "saves"), folder);
        File[] regions = new File(world, "region").listFiles((dir, name) -> name.endsWith(".mca") || name.endsWith(".mcr"));
        if (!new File(world, "level.dat").isFile() || regions == null || regions.length == 0) {
            throw new StepFailed("The save left no level.dat or region files in " + world);
        }
        return "saveSeconds=" + seconds(start) + " regionFiles=" + regions.length;
    }

    /** Opens the world again: it must load what was saved, the gallery's tile entities included. */
    private String reload() throws Exception {
        long start = System.nanoTime();
        game.loadWorld(folder, folder);
        awaitWorld();
        game.allowCommands();
        game.setFpsLimit(WORLD_FPS);
        long loaded = seconds(start);
        String settled = settle(5);
        StringBuilder result = new StringBuilder("loadSeconds=" + loaded + " " + settled);
        if (galleryTileEntities != null) {
            Map<String, Integer> now = galleryTileEntities();
            List<String> lost = new ArrayList<>();
            for (Map.Entry<String, Integer> e : galleryTileEntities.entrySet()) {
                Integer after = now.get(e.getKey());
                if (after == null || after < e.getValue()) {
                    lost.add(e.getKey());
                }
            }
            result.append(" galleryTileEntities=").append(total(now)).append('/').append(total(galleryTileEntities));
            if (!lost.isEmpty()) {
                channel.send("problem", "reload", null, "tile entities the reload didn't bring back: " + lost);
            }
        }
        awaitStopped(game.saveAndQuit());
        game.setFpsLimit(MENU_FPS);
        return result.toString();
    }

    // ---- Waiting

    /** Stays in the world, checking four times a second that it's still there. */
    private void stay(int seconds) throws Exception {
        for (int i = 0; i < seconds * 4; i++) {
            Thread.sleep(250);
            checkInWorld();
        }
    }

    private void checkInWorld() throws Exception {
        checkErrorScreen();
        if (!game.inWorld()) {
            throw new StepFailed("Left the world unexpectedly");
        }
    }

    private void checkErrorScreen() {
        String screen = watcher == null ? null : watcher.errorScreen();
        if (screen != null) {
            throw new StepFailed("Error screen " + screen);
        }
    }

    /** The integrated server saves the world as it stops, after the client has left. */
    private static void awaitStopped(Object server) throws Exception {
        if (server == null) {
            throw new StepFailed("No integrated server to stop");
        }
        long start = System.nanoTime();
        while (!Game.stopped(server)) {
            if (seconds(start) > 300) {
                throw new StepFailed("The integrated server didn't stop within 300 s of leaving the world");
            }
            Thread.sleep(250);
        }
    }

    private static long seconds(long since) {
        return (System.nanoTime() - since) / 1_000_000_000L;
    }

    private static int total(Map<String, Integer> counts) {
        int sum = 0;
        for (int n : counts.values()) {
            sum += n;
        }
        return sum;
    }

    /** " at <the driver's frame>", where in the driver it went wrong: the reason line has no stack trace. */
    private static String at(Throwable t) {
        for (StackTraceElement frame : t.getStackTrace()) {
            if (frame.getClassName().startsWith("dev.lich.harness.driver.")) {
                return " at " + frame.getClassName().substring("dev.lich.harness.driver.".length()) + "."
                        + frame.getMethodName() + ":" + frame.getLineNumber();
            }
        }
        return "";
    }

    private static String cause(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root == t ? String.valueOf(t) : t + " caused by " + root;
    }
}
