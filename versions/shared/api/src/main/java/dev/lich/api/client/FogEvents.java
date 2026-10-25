package dev.lich.api.client;

import dev.lich.api.event.Event;
import dev.lich.api.hook.Hooks;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;

/** Fog (hook {@code render.fog}). */
public final class FogEvents {
    /**
     * The exponential fog density the game is about to set (in water, lava, clouds, or Forge's own density
     * hook); change it to change the fog.
     */
    public static final Event<Density> DENSITY = Event.create(Hooks.RENDER_FOG, "FogEvents.DENSITY");
    /**
     * The distance fog at the edge of the render distance was just set up (not in water, lava or blindness);
     * listeners may set their own fog start and end with {@code GlStateManager}.
     */
    public static final Event<Distance> DISTANCE = Event.create(Hooks.RENDER_FOG, "FogEvents.DISTANCE");
    /** The fog and sky colour of this frame; change it to recolour the fog. */
    public static final Event<Color> COLOR = Event.create(Hooks.RENDER_FOG, "FogEvents.COLOR");

    private FogEvents() {
    }

    /** {@code block}: the block at the camera, which decides the kind of fog. */
    public static final class Density {
        private final Entity entity;
        private final Block block;
        private final float partialTicks;
        private float density;

        public Density(Entity entity, Block block, float partialTicks, float density) {
            this.entity = entity;
            this.block = block;
            this.partialTicks = partialTicks;
            this.density = density;
        }

        public Entity entity() {
            return entity;
        }

        public Block block() {
            return block;
        }

        public float partialTicks() {
            return partialTicks;
        }

        public float density() {
            return density;
        }

        public void setDensity(float density) {
            this.density = density;
        }
    }

    /** {@code fogMode} is -1 for the sky's fog, 0 for the terrain's; {@code farPlaneDistance} is the render distance in blocks. */
    public record Distance(Entity entity, Block block, float partialTicks, int fogMode, float farPlaneDistance) {
    }

    /** Colour components from 0 to 1. */
    public static final class Color {
        private final Entity entity;
        private final Block block;
        private final float partialTicks;
        private float red;
        private float green;
        private float blue;

        public Color(Entity entity, Block block, float partialTicks, float red, float green, float blue) {
            this.entity = entity;
            this.block = block;
            this.partialTicks = partialTicks;
            this.red = red;
            this.green = green;
            this.blue = blue;
        }

        public Entity entity() {
            return entity;
        }

        public Block block() {
            return block;
        }

        public float partialTicks() {
            return partialTicks;
        }

        public float red() {
            return red;
        }

        public float green() {
            return green;
        }

        public float blue() {
            return blue;
        }

        public void set(float red, float green, float blue) {
            this.red = red;
            this.green = green;
            this.blue = blue;
        }
    }
}
