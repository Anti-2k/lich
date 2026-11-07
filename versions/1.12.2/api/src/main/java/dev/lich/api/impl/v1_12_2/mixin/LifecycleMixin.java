package dev.lich.api.impl.v1_12_2.mixin;

import dev.lich.api.client.LifecycleEvents;
import dev.lich.api.impl.Entrypoints;
import net.minecraft.client.Minecraft;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hook {@code lifecycle}, which every Lich mod gets: runs the {@code init} entrypoints once the resource
 * manager exists (window, options and resource manager are up, no resources loaded yet: where Forge gives
 * its mods pre-init), the {@code client} entrypoints when the game has started, and the shutdown event.
 */
@Mixin(Minecraft.class)
public abstract class LifecycleMixin {
    @Inject(method = "init", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/Minecraft;resourceManager:Lnet/minecraft/client/resources/IReloadableResourceManager;"))
    private void lich$init(CallbackInfo ci) {
        Entrypoints.run("init");
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void lich$client(CallbackInfo ci) {
        Entrypoints.run("client");
    }

    @Inject(method = "shutdownMinecraftApplet", at = @At("HEAD"))
    private void lich$shutdown(CallbackInfo ci) {
        LifecycleEvents.SHUTDOWN.fire(LifecycleEvents.Shutdown.INSTANCE);
    }
}
