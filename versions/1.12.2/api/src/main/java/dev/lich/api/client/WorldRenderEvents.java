package dev.lich.api.client;

import dev.lich.api.event.CancellableEvent;
import dev.lich.api.event.Event;
import dev.lich.api.hook.Hooks;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.RayTraceResult;

/** Drawing the world. */
public final class WorldRenderEvents {
    /**
     * After the world, entities, particles and weather are drawn, before the hand (hook {@code render.world}).
     * The camera transform is set up, so drawing here is in world space relative to the camera.
     */
    public static final Event<Last> LAST = Event.create(Hooks.RENDER_WORLD, "WorldRenderEvents.LAST");
    /**
     * The outline of the block the player looks at is about to be drawn (hook {@code render.blockOutline});
     * cancelling skips the vanilla outline, so a listener can draw its own.
     */
    public static final Event<BlockOutline> BLOCK_OUTLINE = Event.create(Hooks.RENDER_BLOCK_OUTLINE, "WorldRenderEvents.BLOCK_OUTLINE");

    private WorldRenderEvents() {
    }

    public record Last(float partialTicks) {
    }

    public static final class BlockOutline extends CancellableEvent {
        private final EntityPlayer player;
        private final RayTraceResult target;
        private final float partialTicks;

        public BlockOutline(EntityPlayer player, RayTraceResult target, float partialTicks) {
            this.player = player;
            this.target = target;
            this.partialTicks = partialTicks;
        }

        public EntityPlayer player() {
            return player;
        }

        public RayTraceResult target() {
            return target;
        }

        public float partialTicks() {
            return partialTicks;
        }
    }
}
