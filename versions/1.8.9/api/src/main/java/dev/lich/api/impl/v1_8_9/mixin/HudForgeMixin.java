package dev.lich.api.impl.v1_8_9.mixin;

import dev.lich.api.client.HudElement;
import dev.lich.api.impl.v1_8_9.HudHooks;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.GuiIngameForge;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hook {@code render.hud} with Forge, whose {@code GuiIngameForge} draws the HUD instead of vanilla's
 * {@code GuiIngame}: it already asks {@code pre(ElementType)} and tells {@code post(ElementType)} around
 * every element, so Lich's events ride on those, after Forge's own (an element a Forge mod cancelled is
 * cancelled for Lich too). The title, the action bar and the chat have their own methods, without Forge's
 * pre and post. Bar rows come from Forge's {@code left_height}/{@code right_height}.
 */
@Mixin(value = GuiIngameForge.class, remap = false)
public abstract class HudForgeMixin extends GuiIngame {
    @Shadow
    private ScaledResolution res;
    @Shadow
    private RenderGameOverlayEvent eventParent;

    @Unique
    private final int[] lich$tops = new int[HudElement.values().length];

    private HudForgeMixin() {
        super(null);
    }

    @Inject(method = "pre", at = @At("RETURN"), cancellable = true)
    private void lich$pre(RenderGameOverlayEvent.ElementType type, CallbackInfoReturnable<Boolean> cir) {
        HudElement element = lich$element(type);
        if (element == null || cir.getReturnValueZ()) {
            return;
        }
        int top = switch (element) {
            case HEALTH, ARMOR -> res.getScaledHeight() - GuiIngameForge.left_height;
            case FOOD, AIR, HEALTHMOUNT -> res.getScaledHeight() - GuiIngameForge.right_height;
            default -> -1;
        };
        lich$tops[element.ordinal()] = top;
        if (HudHooks.pre(element, eventParent.partialTicks, eventParent.resolution, top)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "post", at = @At("HEAD"))
    private void lich$post(RenderGameOverlayEvent.ElementType type, CallbackInfo ci) {
        HudElement element = lich$element(type);
        if (element != null) {
            HudHooks.post(element, lich$tops[element.ordinal()]);
        }
    }

    @Inject(method = "renderTitle", at = @At("HEAD"), cancellable = true)
    private void lich$titlePre(int width, int height, float partialTicks, CallbackInfo ci) {
        lich$ownElementPre(HudElement.TITLE, lich$timers().lich$titlesTimer() > 0, ci);
    }

    @Inject(method = "renderTitle", at = @At("RETURN"))
    private void lich$titlePost(int width, int height, float partialTicks, CallbackInfo ci) {
        if (lich$timers().lich$titlesTimer() > 0) {
            HudHooks.post(HudElement.TITLE, -1);
        }
    }

    @Inject(method = "renderRecordOverlay", at = @At("HEAD"), cancellable = true)
    private void lich$actionBarPre(int width, int height, float partialTicks, CallbackInfo ci) {
        lich$ownElementPre(HudElement.ACTION_BAR, lich$timers().lich$recordPlayingUpFor() > 0, ci);
    }

    @Inject(method = "renderRecordOverlay", at = @At("RETURN"))
    private void lich$actionBarPost(int width, int height, float partialTicks, CallbackInfo ci) {
        if (lich$timers().lich$recordPlayingUpFor() > 0) {
            HudHooks.post(HudElement.ACTION_BAR, -1);
        }
    }

    /** Forge fires its own chat event instead of {@code pre(CHAT)}, but does call {@code post(CHAT)}. */
    @Inject(method = "renderChat", at = @At("HEAD"), cancellable = true)
    private void lich$chatPre(int width, int height, CallbackInfo ci) {
        lich$ownElementPre(HudElement.CHAT, true, ci);
    }

    @Unique
    private void lich$ownElementPre(HudElement element, boolean shown, CallbackInfo ci) {
        if (shown && HudHooks.pre(element, eventParent.partialTicks, eventParent.resolution, -1)) {
            ci.cancel();
        }
    }

    /** Vanilla's timers are private; Forge's access transformer widens them, but Lich compiles against vanilla. */
    @Unique
    private GuiIngameAccessor lich$timers() {
        return (GuiIngameAccessor) this;
    }

    /** Lich's element for Forge's; {@code TEXT} (Forge's text overlay) has none, Lich has its F3 text event. */
    @Unique
    private static HudElement lich$element(RenderGameOverlayEvent.ElementType type) {
        return type == RenderGameOverlayEvent.ElementType.TEXT ? null : HudElement.valueOf(type.name());
    }
}
