package dev.lich.api.client;

import dev.lich.api.event.CancellableEvent;
import dev.lich.api.event.Event;
import dev.lich.api.hook.Hooks;
import net.minecraft.client.audio.ISound;

/** Sounds (hook {@code sound.play}). */
public final class SoundEvents {
    /** A sound about to play: set another (to change its volume, say), or cancel it. */
    public static final Event<Play> PLAY = Event.create(Hooks.SOUND_PLAY, "SoundEvents.PLAY");

    private SoundEvents() {
    }

    public static final class Play extends CancellableEvent {
        private final String name;
        private ISound sound;

        /** {@code name}: the path of the sound's location (the getter's name differs between versions). */
        public Play(ISound sound, String name) {
            this.sound = sound;
            this.name = name;
        }

        /** The sound's name, e.g. {@code ambient.weather.thunder}. */
        public String name() {
            return name;
        }

        public ISound sound() {
            return sound;
        }

        public void setSound(ISound sound) {
            this.sound = sound;
        }
    }
}
