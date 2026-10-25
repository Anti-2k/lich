package dev.lich.api.client;

import dev.lich.api.event.CancellableEvent;
import dev.lich.api.event.Event;
import dev.lich.api.hook.Hooks;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;

/** Drawing living entities (hook {@code render.entity}). */
public final class EntityRenderEvents {
    /** Before a living entity is drawn; cancelling skips it (its name tag too). */
    public static final Event<Living> PRE = Event.create(Hooks.RENDER_ENTITY, "EntityRenderEvents.PRE");
    /** After a living entity was drawn (not after a cancelled one). */
    public static final Event<Living> POST = Event.create(Hooks.RENDER_ENTITY, "EntityRenderEvents.POST");
    /** After a living entity's name tag was drawn, or would have been if it had one in view. */
    public static final Event<Living> NAMETAG_POST = Event.create(Hooks.RENDER_ENTITY, "EntityRenderEvents.NAMETAG_POST");

    private EntityRenderEvents() {
    }

    /** {@code x}, {@code y}, {@code z}: where the entity is drawn, relative to the camera. */
    public static final class Living extends CancellableEvent {
        private final EntityLivingBase entity;
        private final RendererLivingEntity<?> renderer;
        private final double x;
        private final double y;
        private final double z;

        public Living(EntityLivingBase entity, RendererLivingEntity<?> renderer, double x, double y, double z) {
            this.entity = entity;
            this.renderer = renderer;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public EntityLivingBase entity() {
            return entity;
        }

        public RendererLivingEntity<?> renderer() {
            return renderer;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }
    }
}
