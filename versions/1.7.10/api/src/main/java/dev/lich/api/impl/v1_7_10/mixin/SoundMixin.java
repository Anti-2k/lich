package dev.lich.api.impl.v1_7_10.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lich.api.client.SoundEvents;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundManager;
import org.spongepowered.asm.mixin.Mixin;

/** Hook {@code sound.play}: every sound goes through {@code SoundManager.playSound}, with and without Forge. */
@Mixin(SoundManager.class)
public abstract class SoundMixin {
    @WrapMethod(method = "playSound")
    private void lich$play(ISound sound, Operation<Void> play) {
        if (sound != null && SoundEvents.PLAY.hasListeners()) {
            SoundEvents.Play event = SoundEvents.PLAY.fire(new SoundEvents.Play(sound, sound.getPositionedSoundLocation().getResourcePath()));
            if (event.isCanceled() || event.sound() == null) {
                return;
            }
            sound = event.sound();
        }
        play.call(sound);
    }
}
