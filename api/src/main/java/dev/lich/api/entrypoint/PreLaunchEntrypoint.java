package dev.lich.api.entrypoint;

/**
 * A {@code preLaunch} entrypoint: runs once every tweaker has run, right before the game's main method.
 * No game class has loaded yet and none may be touched here; reading config is fine.
 */
@FunctionalInterface
public interface PreLaunchEntrypoint {
    void onPreLaunch();
}
