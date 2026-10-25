package dev.lich.api.client;

import dev.lich.api.event.Event;
import dev.lich.api.hook.Hooks;
import net.minecraft.entity.player.EntityPlayer;

/** The field of view (hook {@code render.fov}). */
public final class FovEvents {
    /**
     * The FOV multiplier the player's state asks for (sprinting, flying, Speed, a drawn bow), which the
     * view then eases towards; change {@code newFov} to change it. 1 is the FOV from the options.
     */
    public static final Event<Update> UPDATE = Event.create(Hooks.RENDER_FOV, "FovEvents.UPDATE");

    private FovEvents() {
    }

    public static final class Update {
        private final EntityPlayer player;
        private final float fov;
        private float newFov;

        public Update(EntityPlayer player, float fov) {
            this.player = player;
            this.fov = fov;
            this.newFov = fov;
        }

        public EntityPlayer player() {
            return player;
        }

        /** What the game worked out (with Forge, after Forge mods). */
        public float fov() {
            return fov;
        }

        public float newFov() {
            return newFov;
        }

        public void setNewFov(float newFov) {
            this.newFov = newFov;
        }
    }
}
