package dev.lich.api.entrypoint;

/**
 * An {@code init} entrypoint: runs while the game starts, once the window, the options and the resource
 * manager exist and before resources first load (where Forge mods get their pre-init). The place to
 * register listeners and key bindings.
 */
@FunctionalInterface
public interface InitEntrypoint {
    void onInit();
}
