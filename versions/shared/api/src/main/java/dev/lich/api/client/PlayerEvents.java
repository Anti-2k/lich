package dev.lich.api.client;

import dev.lich.api.event.Event;
import dev.lich.api.hook.Hooks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

/** The player's actions, observed only: listeners can't change or stop them (DOCS.md §6). */
public final class PlayerEvents {
    /**
     * The player attacks {@code target} (hook {@code player.attack}): the attack has been sent to the server,
     * and the client is about to apply it locally. Not fired in spectator mode, where there's no attack.
     */
    public static final Event<Attack> ATTACK = Event.create(Hooks.PLAYER_ATTACK, "PlayerEvents.ATTACK");

    private PlayerEvents() {
    }

    public record Attack(EntityPlayer player, Entity target) {
    }
}
