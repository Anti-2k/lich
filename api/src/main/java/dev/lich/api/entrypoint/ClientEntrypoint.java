package dev.lich.api.entrypoint;

/**
 * A {@code client} entrypoint: runs once the game has finished starting (resources loaded, every Forge
 * mod initialised), right before the first frame of the title screen.
 */
@FunctionalInterface
public interface ClientEntrypoint {
    void onClientReady();
}
