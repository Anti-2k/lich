package dev.lich.harness.driver;

import net.minecraft.launchwrapper.Launch;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The running game, reached through reflection on its SRG names: the driver is loaded by the system class loader and
 * the game by LaunchClassLoader. The names used here are the same in 1.7.10 and 1.8.9 (checked against MCP's tables
 * for both), apart from the few places that ask {@link #oneEight}. Anything that changes the game runs on the client
 * thread, through the game's own task queue, which it drains once per frame: so a task that has run means a frame
 * is being drawn after it.
 */
final class Game {
    /** How long a task may wait for the client thread before the game counts as hung. */
    private static final long TASK_TIMEOUT_SECONDS = 120;

    private final ClassLoader loader;
    private final Object minecraft;
    final boolean oneEight;
    /** {@code cpw.mods.fml.} (1.7.10) or {@code net.minecraftforge.fml.} (1.8.9); null without Forge. */
    final String fml;

    private Game(ClassLoader loader, Object minecraft) {
        this.loader = loader;
        this.minecraft = minecraft;
        this.oneEight = exists("net.minecraft.util.BlockPos");
        this.fml = exists("net.minecraftforge.fml.client.FMLClientHandler") ? "net.minecraftforge.fml."
                : exists("cpw.mods.fml.client.FMLClientHandler") ? "cpw.mods.fml." : null;
    }

    /**
     * Waits until the game has started and shows its first screen. It only asks for a class the game already loaded
     * ({@code findLoadedClass}): loading {@code Minecraft} from here, before FML has registered its transformers,
     * would define it untransformed. Not through {@code cachedClasses}: FoamFix swaps that map for another one.
     * <p>
     * Which LaunchClassLoader holds the game isn't fixed either: Essential relaunches the whole launch, Lich's boot
     * included, in a class loader of its own, so the game ends up in a LaunchClassLoader that a second copy of
     * {@code Launch} knows about, not the one this driver sees. LaunchWrapper makes its class loader the game
     * thread's context class loader, so every live thread's is a candidate, besides {@code Launch.classLoader}.
     */
    static Game await() throws Exception {
        Method findLoadedClass = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
        findLoadedClass.setAccessible(true);
        Map<Class<?>, Long> firstSeen = new HashMap<>();
        while (true) {
            for (ClassLoader loader : candidateLoaders()) {
                Class<?> minecraftClass = (Class<?>) findLoadedClass.invoke(loader, "net.minecraft.client.Minecraft");
                if (minecraftClass == null) {
                    continue;
                }
                // Defined isn't initialized yet: give the client thread time to construct it itself.
                Long seen = firstSeen.get(minecraftClass);
                if (seen == null) {
                    firstSeen.put(minecraftClass, System.nanoTime());
                    continue;
                }
                if (System.nanoTime() - seen < 5_000_000_000L) {
                    continue;
                }
                Object minecraft = field(minecraftClass, "field_71432_P").get(null); // theMinecraft
                if (minecraft != null && field(minecraftClass, "field_71462_r").get(minecraft) != null) { // currentScreen
                    return new Game(minecraftClass.getClassLoader(), minecraft);
                }
            }
            Thread.sleep(250);
        }
    }

    /** {@code Launch.classLoader} and every live thread's context class loader, each once. */
    private static Set<ClassLoader> candidateLoaders() {
        Set<ClassLoader> loaders = Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
        if (Launch.classLoader != null) {
            loaders.add(Launch.classLoader);
        }
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            ClassLoader loader = thread.getContextClassLoader();
            if (loader != null) {
                loaders.add(loader);
            }
        }
        return loaders;
    }

    // ---- Screens and frames

    Object screen() throws Exception {
        return get(minecraft, "field_71462_r"); // currentScreen
    }

    static String name(Object screen) {
        return screen == null ? "none" : screen.getClass().getName();
    }

    void display(final Object screen) throws Exception {
        final Method display = method(minecraft.getClass(), "func_147108_a", type("net.minecraft.client.gui.GuiScreen"));
        onClient(() -> display.invoke(minecraft, screen)); // displayGuiScreen
    }

    /**
     * Makes a screen on the client thread and shows it: screens' constructors may touch the game (the creative
     * inventory opens its container, {@code GuiConnecting} leaves the world and connects).
     */
    Object displayNew(final Constructor<?> constructor, final Object... args) throws Exception {
        final Method display = method(minecraft.getClass(), "func_147108_a", type("net.minecraft.client.gui.GuiScreen"));
        final Object[] screen = new Object[1];
        onClient(() -> {
            screen[0] = constructor.newInstance(args);
            display.invoke(minecraft, screen[0]);
        });
        return screen[0];
    }

    /** Waits until the client thread has drawn {@code count} more frames. */
    void frames(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            onClient(() -> {
            });
        }
    }

    /** The frame rate cap ({@code limitFramerate}); 260 and up is unlimited. */
    int fpsLimit() throws Exception {
        return (Integer) get(settings(), "field_74350_i");
    }

    void setFpsLimit(int fps) throws Exception {
        field(settings().getClass(), "field_74350_i").set(settings(), fps);
    }

    private Object settings() throws Exception {
        return get(minecraft, "field_71474_y"); // gameSettings
    }

    /**
     * Clicks the button with this id: {@code actionPerformed}, as a mouse click calls it. On the calling thread, for
     * FML's startup prompts: the client thread waits in a loop of its own while they're up and runs no tasks.
     */
    void click(Object screen, int id) throws Exception {
        Class<?> buttonClass = type("net.minecraft.client.gui.GuiButton");
        for (Object button : (List<?>) get(screen, "field_146292_n")) { // buttonList
            if ((Integer) get(button, "field_146127_k") == id) { // id
                method(screen.getClass(), "func_146284_a", buttonClass).invoke(screen, button); // actionPerformed
                return;
            }
        }
        throw new IllegalStateException("No button " + id + " on " + name(screen));
    }

    // ---- FML's mod list

    /** Opens FML's mod list over {@code parent}; null without Forge. */
    Object openModList(Object parent) throws Exception {
        if (fml == null) {
            return null;
        }
        return displayNew(type(fml + "client.GuiModList").getConstructor(type("net.minecraft.client.gui.GuiScreen")), parent);
    }

    List<?> mods(Object modList) throws Exception {
        return (List<?>) get(modList, "mods");
    }

    void selectMod(final Object modList, final int index) throws Exception {
        final Method select = method(modList.getClass(), "selectModIndex", int.class);
        onClient(() -> select.invoke(modList, index));
    }

    static String modId(Object mod) throws Exception {
        return (String) mod.getClass().getMethod("getModId").invoke(mod);
    }

    /**
     * Shows the mod's config screen over {@code parent}, made as the mod list's Config button makes it; false if it
     * has none.
     */
    boolean openConfig(Object mod, Object parent) throws Exception {
        Class<?> handler = type(fml + "client.FMLClientHandler");
        Object instance = handler.getMethod("instance").invoke(null);
        Object factory = handler.getMethod("getGuiFactoryFor", type(fml + "common.ModContainer")).invoke(instance, mod);
        if (factory == null) {
            return false;
        }
        Class<?> screen = (Class<?>) factory.getClass().getMethod("mainConfigGuiClass").invoke(factory);
        if (screen == null) {
            return false;
        }
        Constructor<?> constructor = screen.getConstructor(type("net.minecraft.client.gui.GuiScreen"));
        constructor.setAccessible(true);
        displayNew(constructor, parent);
        return true;
    }

    // ---- Multiplayer

    /** Connects to a server as the multiplayer screen does. */
    void connect(String host, int port) throws Exception {
        displayNew(type("net.minecraft.client.multiplayer.GuiConnecting")
                        .getConstructor(type("net.minecraft.client.gui.GuiScreen"), minecraft.getClass(), String.class, int.class),
                screen(), minecraft, host, port);
    }

    /** In the world of a remote server, with no screen in front (the terrain screen gone). */
    boolean inRemoteWorld() throws Exception {
        return get(minecraft, "field_71441_e") != null && get(minecraft, "field_71439_g") != null && server() == null
                && screen() == null;
    }

    /** The reason on the disconnected screen, if that's up; else null. */
    String disconnectedReason() throws Exception {
        Object screen = screen();
        if (screen == null || !screen.getClass().getName().equals("net.minecraft.client.gui.GuiDisconnected")) {
            return null;
        }
        Object message = get(screen, "field_146304_f");
        try {
            return (String) method(message.getClass(), "func_150260_c").invoke(message); // getUnformattedText
        } catch (NoSuchMethodException e) {
            return String.valueOf(message);
        }
    }

    // ---- Singleplayer

    /** Creates a world in creative with cheats on, and starts its integrated server. */
    void createWorld(String folder, String name, long seed, String worldTypeName) throws Exception {
        Class<?> worldType = type("net.minecraft.world.WorldType");
        Object type = worldType.getMethod("func_77130_a", String.class).invoke(null, worldTypeName); // parseWorldType
        if (type == null) {
            throw new StepFailed("No world type named " + worldTypeName);
        }
        Class<?> settingsClass = type("net.minecraft.world.WorldSettings");
        Class<?> gameType = type("net.minecraft.world.WorldSettings$GameType");
        Constructor<?> constructor = settingsClass.getConstructor(long.class, gameType, boolean.class, boolean.class, worldType);
        Object settings = constructor.newInstance(seed, enumConstant(gameType, "CREATIVE"), true, false, type);
        settingsClass.getMethod("func_77166_b").invoke(settings); // enableCommands
        launch(folder, name, settings);
    }

    /** Opens an existing world, as the world list does. */
    void loadWorld(String folder, String name) throws Exception {
        launch(folder, name, null);
    }

    private void launch(final String folder, final String name, final Object settings) throws Exception {
        final Method launch = method(minecraft.getClass(), "func_71371_a", // launchIntegratedServer
                String.class, String.class, type("net.minecraft.world.WorldSettings"));
        onClient(() -> launch.invoke(minecraft, folder, name, settings));
    }

    /** Whether the player is in a world that the integrated server is still running. */
    boolean inWorld() throws Exception {
        if (get(minecraft, "field_71441_e") == null || get(minecraft, "field_71439_g") == null) { // theWorld, thePlayer
            return false;
        }
        Object server = server();
        return server != null && (Boolean) method(server.getClass(), "func_71278_l").invoke(server); // isServerRunning
    }

    boolean hasWorld() throws Exception {
        return get(minecraft, "field_71441_e") != null;
    }

    Object server() throws Exception {
        return method(minecraft.getClass(), "func_71401_C").invoke(minecraft); // getIntegratedServer
    }

    /** Lets every player use commands, as opening the world to LAN with cheats on does: a pack's own map may not. */
    void allowCommands() throws Exception {
        Object server = server();
        Object players = method(server.getClass(), "func_71203_ab").invoke(server); // getConfigurationManager
        method(players.getClass(), "func_72387_b", boolean.class).invoke(players, true); // setCommandsAllowedForAll
    }

    double[] playerXZ() throws Exception {
        Object player = get(minecraft, "field_71439_g");
        return new double[]{(Double) get(player, "field_70165_t"), (Double) get(player, "field_70161_v")}; // posX, posZ
    }

    /** Sends a chat line as the player, so a command runs on the integrated server the way a typed one does. */
    void chat(String message) throws Exception {
        chat(Collections.singletonList(message));
    }

    /** Sends several chat lines in one frame. */
    void chat(final List<String> messages) throws Exception {
        onClient(() -> {
            Object player = get(minecraft, "field_71439_g");
            Method send = method(player.getClass(), "func_71165_d", String.class); // sendChatMessage
            for (String message : messages) {
                send.invoke(player, message);
            }
        });
    }

    /**
     * What the pause menu's "Save and Quit to Title" (or "Disconnect") does. Returns the integrated server, which
     * then saves and stops, or null in multiplayer.
     */
    Object saveAndQuit() throws Exception {
        final Object server = server();
        final Class<?> worldClient = type("net.minecraft.client.multiplayer.WorldClient");
        final Class<?> guiScreen = type("net.minecraft.client.gui.GuiScreen");
        final Class<?> mainMenu = type("net.minecraft.client.gui.GuiMainMenu");
        onClient(() -> {
            Object world = get(minecraft, "field_71441_e");
            if (world != null) {
                method(world.getClass(), "func_72882_A").invoke(world); // sendQuittingDisconnectingPacket
            }
            method(minecraft.getClass(), "func_71403_a", worldClient).invoke(minecraft, (Object) null); // loadWorld
            method(minecraft.getClass(), "func_147108_a", guiScreen) // displayGuiScreen
                    .invoke(minecraft, mainMenu.getConstructor().newInstance());
        });
        return server;
    }

    static boolean stopped(Object server) throws Exception {
        return (Boolean) method(server.getClass(), "func_71241_aa").invoke(server); // isServerStopped
    }

    /** The overworld's loaded chunks on the server; -1 without an integrated server. */
    int serverChunks() throws Exception {
        Object overworld = overworld();
        if (overworld == null) {
            return -1;
        }
        Object chunks = get(overworld, "field_73059_b"); // theChunkProviderServer
        return (Integer) method(chunks.getClass(), "func_73152_e").invoke(chunks); // getLoadedChunkCount
    }

    /**
     * How many chunks within {@code radius} of the player's chunk aren't ready yet: on the server, not loaded or not
     * populated (the mods' worldgen hasn't run on it); on the client, the player's own chunk not received. 0 when all
     * are; -1 without an integrated server. A chunk is only asked for once the server says it's loaded: asking for one
     * that isn't would generate it from this thread.
     */
    int chunksNotReady(int radius) throws Exception {
        Object overworld = overworld();
        Object world = get(minecraft, "field_71441_e");
        if (overworld == null || world == null) {
            return -1;
        }
        double[] at = playerXZ();
        int cx = (int) Math.floor(at[0]) >> 4;
        int cz = (int) Math.floor(at[1]) >> 4;
        Object server = get(overworld, "field_73059_b"); // theChunkProviderServer
        Method exists = method(server.getClass(), "func_73149_a", int.class, int.class); // chunkExists
        Method provide = method(server.getClass(), "func_73154_d", int.class, int.class); // provideChunk
        int missing = 0;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (!(Boolean) exists.invoke(server, x, z)
                        || !(Boolean) get(provide.invoke(server, x, z), "field_76646_k")) { // isTerrainPopulated
                    missing++;
                }
            }
        }
        Object client = method(world.getClass(), "func_72863_F").invoke(world); // getChunkProvider
        Object own = method(client.getClass(), "func_73154_d", int.class, int.class).invoke(client, cx, cz);
        if ((Boolean) method(own.getClass(), "func_76621_g").invoke(own)) { // isEmpty: the client's blank chunk
            missing++;
        }
        return missing;
    }

    /** The chunks the client has; -1 without a world. */
    int clientChunks() throws Exception {
        Object world = get(minecraft, "field_71441_e");
        if (world == null) {
            return -1;
        }
        Object chunks = method(world.getClass(), "func_72863_F").invoke(world); // getChunkProvider
        return (Integer) method(chunks.getClass(), "func_73152_e").invoke(chunks); // getLoadedChunkCount
    }

    /** The integrated server's overworld; null without one, or while it hasn't loaded its worlds yet. */
    private Object overworld() throws Exception {
        Object server = server();
        Object[] worlds = server == null ? null : (Object[]) get(server, "field_71305_c"); // worldServers
        return worlds == null || worlds.length == 0 ? null : worlds[0];
    }

    /**
     * The overworld's loaded chunks, entities and tile entities, the server's mean tick time, the client's chunks and
     * the heap in use, as {@code key=value} fields.
     */
    String stats() throws Exception {
        StringBuilder out = new StringBuilder();
        Object server = server();
        Object overworld = overworld();
        if (overworld != null) {
            int entities = ((List<?>) get(overworld, "field_72996_f")).size(); // loadedEntityList
            int tileEntities = ((List<?>) get(overworld, "field_147482_g")).size(); // loadedTileEntityList
            long[] tickTimes = (long[]) get(server, "field_71311_j"); // tickTimeArray, nanoseconds
            long sum = 0;
            for (long t : tickTimes) {
                sum += t;
            }
            out.append("chunks=").append(serverChunks()).append(" entities=").append(entities)
                    .append(" tileEntities=").append(tileEntities)
                    .append(" mspt=").append(Math.round(sum / (double) tickTimes.length / 1e5) / 10.0).append(' ');
        }
        Runtime runtime = Runtime.getRuntime();
        out.append("clientChunks=").append(clientChunks())
                .append(" heapMb=").append((runtime.totalMemory() - runtime.freeMemory()) >> 20);
        return out.toString();
    }

    /** Lets the game shut down the way its window's close button does. */
    void shutdown() throws Exception {
        method(minecraft.getClass(), "func_71400_g").invoke(minecraft);
    }

    // ---- Creative inventory

    Object openCreative() throws Exception {
        return displayNew(type("net.minecraft.client.gui.inventory.GuiContainerCreative")
                .getConstructor(type("net.minecraft.entity.player.EntityPlayer")), get(minecraft, "field_71439_g"));
    }

    /** Every creative tab, in order (Forge's later pages included). */
    List<Object> creativeTabs() throws Exception {
        List<Object> tabs = new ArrayList<>();
        for (Object tab : (Object[]) field(type("net.minecraft.creativetab.CreativeTabs"), "field_78032_a").get(null)) {
            if (tab != null) {
                tabs.add(tab);
            }
        }
        return tabs;
    }

    static String tabLabel(Object tab) throws Exception {
        return (String) method(tab.getClass(), "func_78013_b").invoke(tab); // getTabLabel
    }

    /**
     * Shows {@code tab}, as clicking it does, and returns how many scroll positions its items need (1 if they fit).
     * Forge's tab pages: the page the tab is on is selected too.
     */
    int selectTab(final Object creative, final Object tab) throws Exception {
        final Method select = method(creative.getClass(), "func_147050_b", type("net.minecraft.creativetab.CreativeTabs"));
        final int[] pages = new int[1];
        onClient(() -> {
            try {
                Field page = field(creative.getClass(), "tabPage");
                page.set(null, tab.getClass().getMethod("getTabPage").invoke(tab));
            } catch (NoSuchFieldException | NoSuchMethodException ignored) {
                // Vanilla's single page.
            }
            select.invoke(creative, tab);
            int items = ((List<?>) get(get(creative, "field_147002_h"), "field_148330_a")).size(); // inventorySlots.itemList
            int hiddenRows = (items + 8) / 9 - 5;
            pages[0] = hiddenRows <= 0 ? 1 : (hiddenRows + 4) / 5 + 1;
        });
        return pages[0];
    }

    /** Scrolls the creative inventory to {@code position} (0 top, 1 bottom), as its scroll bar does. */
    void scroll(final Object creative, final float position) throws Exception {
        onClient(() -> {
            field(creative.getClass(), "field_147067_x").set(creative, position); // currentScroll
            Object container = get(creative, "field_147002_h"); // inventorySlots
            method(container.getClass(), "func_148329_a", float.class).invoke(container, position); // scrollTo
        });
    }

    // ---- Blocks

    /** Every registered block's name, as {@code /setblock} takes it. */
    List<String> blockNames() throws Exception {
        Object registry = field(type("net.minecraft.block.Block"), "field_149771_c").get(null); // blockRegistry
        Collection<?> keys = (Collection<?>) method(registry.getClass(), "func_148742_b").invoke(registry); // getKeys
        List<String> names = new ArrayList<>();
        for (Object key : keys) {
            names.add(String.valueOf(key));
        }
        Collections.sort(names);
        return names;
    }

    /** The overworld's tile entities inside the box, counted by class. */
    Map<String, Integer> tileEntitiesIn(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) throws Exception {
        Map<String, Integer> found = new LinkedHashMap<>();
        Object overworld = overworld();
        if (overworld == null) {
            return found;
        }
        for (Object te : new ArrayList<Object>((List<?>) get(overworld, "field_147482_g"))) { // loadedTileEntityList
            int x;
            int y;
            int z;
            if (oneEight) {
                Object pos = get(te, "field_174879_c"); // pos
                x = (Integer) method(pos.getClass(), "func_177958_n").invoke(pos);
                y = (Integer) method(pos.getClass(), "func_177956_o").invoke(pos);
                z = (Integer) method(pos.getClass(), "func_177952_p").invoke(pos);
            } else {
                x = (Integer) get(te, "field_145851_c"); // xCoord
                y = (Integer) get(te, "field_145848_d");
                z = (Integer) get(te, "field_145849_e");
            }
            if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                String name = te.getClass().getName();
                Integer count = found.get(name);
                found.put(name, count == null ? 1 : count + 1);
            }
        }
        return found;
    }

    // ---- The client thread

    interface Task {
        void run() throws Exception;
    }

    /**
     * Runs {@code task} on the client thread and waits for it ({@code func_152344_a}: the scheduled-task queue). What
     * the game throws from a call the task made is a {@link StepFailed}; anything else is the driver's own failure.
     */
    void onClient(final Task task) throws Exception {
        Runnable runnable = () -> {
            try {
                task.run();
            } catch (InvocationTargetException e) {
                throw new TaskFailed(e.getCause(), true);
            } catch (Exception e) {
                throw new TaskFailed(e, false);
            }
        };
        Future<?> future = (Future<?>) method(minecraft.getClass(), "func_152344_a", Runnable.class).invoke(minecraft, runnable);
        try {
            future.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TaskFailed)) {
                throw new StepFailed("The game failed on the client thread: " + e.getCause(), e.getCause());
            }
            TaskFailed failed = (TaskFailed) e.getCause();
            if (failed.byGame) {
                throw new StepFailed("The game failed on the client thread: " + failed.getCause(), failed.getCause());
            }
            throw new IllegalStateException("The driver failed on the client thread: " + failed.getCause(), failed.getCause());
        } catch (TimeoutException e) {
            throw new StepFailed("The client thread ran no task for " + TASK_TIMEOUT_SECONDS + " s");
        }
    }

    private static final class TaskFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final boolean byGame;

        TaskFailed(Throwable cause, boolean byGame) {
            super(cause);
            this.byGame = byGame;
        }
    }

    // ---- Reflection

    Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    private boolean exists(String name) {
        try {
            type(name);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumConstant(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    static Object get(Object target, String name) throws Exception {
        return field(target.getClass(), name).get(target);
    }

    /** A field declared by {@code type} or one of its superclasses (mods subclass the game's classes). */
    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Declared further up.
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Declared further up.
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
