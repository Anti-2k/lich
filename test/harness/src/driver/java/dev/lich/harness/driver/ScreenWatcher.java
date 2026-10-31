package dev.lich.harness.driver;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Follows the game's screen from a thread of its own. Every change becomes an event, so a run that stops somewhere
 * says on which screen. FML's startup prompts ({@code StartupQuery}: "the world was saved with mods that are missing,
 * continue?") are answered yes, as a player who wants to play would, and logged. An error screen (FML's missing-mod
 * and duplicate-mod screens, the out-of-memory screen) is remembered, so the step waiting on the game fails at once
 * instead of at its deadline.
 */
final class ScreenWatcher implements Runnable {
    /** Simple names, the same in FML's 1.7.10 ({@code cpw.mods.fml.client}) and 1.8.9 packages. */
    private static final Set<String> ERROR_SCREENS = new HashSet<>(Arrays.asList(
            "GuiModsMissing", "GuiDupesFound", "GuiCustomModLoadingErrorScreen", "GuiWrongMinecraft", "GuiSortingProblem",
            "GuiMultipleModsErrored", "GuiModsMissingForServer", "GuiAccessDenied", "GuiBackupFailed",
            "GuiMemoryErrorScreen"));

    private final Game game;
    private final Channel channel;
    private final Script script;
    private volatile String errorScreen;

    ScreenWatcher(Game game, Channel channel, Script script) {
        this.game = game;
        this.channel = channel;
        this.script = script;
    }

    /** The error screen that came up, or null. */
    String errorScreen() {
        return errorScreen;
    }

    @Override
    public void run() {
        Object last = null;
        boolean first = true;
        while (true) {
            try {
                Object screen = game.screen();
                if (first || screen != last) {
                    first = false;
                    last = screen;
                    String name = Game.name(screen);
                    channel.send("note", script.step(), Channel.field("screen", name), null);
                    if (screen != null && ERROR_SCREENS.contains(screen.getClass().getSimpleName())) {
                        errorScreen = name;
                    }
                    if (screen != null && game.fml != null && isStartupQuery(screen)) {
                        answer(screen);
                    }
                }
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                channel.send("problem", script.step(), null, "screen watcher: " + t);
                return;
            }
        }
    }

    private boolean isStartupQuery(Object screen) throws ClassNotFoundException {
        return game.type(game.fml + "client.GuiNotification").isInstance(screen);
    }

    /** Yes on a confirmation, OK on a notification: button 0 on both. */
    private void answer(Object screen) throws Exception {
        Object query = Game.get(screen, "query");
        String text = (String) query.getClass().getMethod("getText").invoke(query);
        channel.send("note", script.step(), Channel.field("answered", screen.getClass().getSimpleName()), text);
        game.click(screen, 0);
    }
}
