package dev.lich.api.impl.v1_12_2.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lich.api.client.HudElement;
import dev.lich.api.impl.v1_12_2.HudHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiBossOverlay;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiOverlayDebug;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.gui.GuiSpectator;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hook {@code render.hud} without Forge: vanilla's HUD in the events Forge's {@code GuiIngameForge} fires
 * (see {@code HudForgeMixin}). Parts that are a method call are wrapped; the crosshair, the action bar and
 * the title by the condition that draws them, their POST at the next statement. The bars live in two
 * methods, {@code renderPlayerStats} and {@code renderMountHealth}, which the profiler splits into sections: each
 * section start ends the previous bar and begins the next, and a cancelled bar's texture draws are skipped.
 */
@Mixin(GuiIngame.class)
public abstract class HudVanillaMixin {
    @Shadow
    @Final
    protected Minecraft mc;

    @Unique
    private boolean lich$actionBarDrawn;
    @Unique
    private boolean lich$titleDrawn;
    @Unique
    private HudElement lich$bar;
    @Unique
    private int lich$barTop;
    @Unique
    private boolean lich$barCancelled;

    @Inject(method = "renderGameOverlay", at = @At("HEAD"), cancellable = true)
    private void lich$allPre(float partialTicks, CallbackInfo ci) {
        HudHooks.begin(partialTicks, new ScaledResolution(mc));
        if (HudHooks.pre(HudElement.ALL, -1)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderGameOverlay", at = @At("RETURN"))
    private void lich$allPost(float partialTicks, CallbackInfo ci) {
        HudHooks.post(HudElement.ALL, -1);
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiIngame;renderPumpkinOverlay(Lnet/minecraft/client/gui/ScaledResolution;)V"))
    private void lich$helmet(GuiIngame hud, ScaledResolution resolution, Operation<Void> draw) {
        if (!HudHooks.pre(HudElement.HELMET, -1)) {
            draw.call(hud, resolution);
            HudHooks.post(HudElement.HELMET, -1);
        }
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiIngame;renderPortal(FLnet/minecraft/client/gui/ScaledResolution;)V"))
    private void lich$portal(GuiIngame hud, float timeInPortal, ScaledResolution resolution, Operation<Void> draw) {
        if (!HudHooks.pre(HudElement.PORTAL, -1)) {
            draw.call(hud, timeInPortal, resolution);
            HudHooks.post(HudElement.PORTAL, -1);
        }
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiIngame;renderHotbar(Lnet/minecraft/client/gui/ScaledResolution;F)V"))
    private void lich$hotbar(GuiIngame hud, ScaledResolution resolution, float partialTicks, Operation<Void> draw) {
        if (!HudHooks.pre(HudElement.HOTBAR, -1)) {
            draw.call(hud, resolution, partialTicks);
            HudHooks.post(HudElement.HOTBAR, -1);
        }
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiSpectator;renderTooltip(Lnet/minecraft/client/gui/ScaledResolution;F)V"))
    private void lich$spectatorHotbar(GuiSpectator spectator, ScaledResolution resolution, float partialTicks, Operation<Void> draw) {
        if (!HudHooks.pre(HudElement.HOTBAR, -1)) {
            draw.call(spectator, resolution, partialTicks);
            HudHooks.post(HudElement.HOTBAR, -1);
        }
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiIngame;renderAttackIndicator(FLnet/minecraft/client/gui/ScaledResolution;)V"))
    private void lich$crosshair(GuiIngame hud, float partialTicks, ScaledResolution resolution, Operation<Void> draw) {
        if (!HudHooks.pre(HudElement.CROSSHAIRS, -1)) {
            draw.call(hud, partialTicks, resolution);
            HudHooks.post(HudElement.CROSSHAIRS, -1);
        }
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiBossOverlay;renderBossHealth()V"))
    private void lich$bossHealth(GuiBossOverlay boss, Operation<Void> draw) {
        if (!HudHooks.pre(HudElement.BOSSHEALTH, -1)) {
            draw.call(boss);
            HudHooks.post(HudElement.BOSSHEALTH, -1);
        }
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiIngame;renderExpBar(Lnet/minecraft/client/gui/ScaledResolution;I)V"))
    private void lich$experience(GuiIngame hud, ScaledResolution resolution, int x, Operation<Void> draw) {
        if (!HudHooks.pre(HudElement.EXPERIENCE, -1)) {
            draw.call(hud, resolution, x);
            HudHooks.post(HudElement.EXPERIENCE, -1);
        }
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiIngame;renderHorseJumpBar(Lnet/minecraft/client/gui/ScaledResolution;I)V"))
    private void lich$jumpBar(GuiIngame hud, ScaledResolution resolution, int x, Operation<Void> draw) {
        if (!HudHooks.pre(HudElement.JUMPBAR, -1)) {
            draw.call(hud, resolution, x);
            HudHooks.post(HudElement.JUMPBAR, -1);
        }
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiOverlayDebug;renderDebugInfo(Lnet/minecraft/client/gui/ScaledResolution;)V"))
    private void lich$debug(GuiOverlayDebug debug, ScaledResolution resolution, Operation<Void> draw) {
        if (!HudHooks.pre(HudElement.DEBUG, -1)) {
            draw.call(debug, resolution);
            HudHooks.post(HudElement.DEBUG, -1);
        }
    }

    @ModifyExpressionValue(method = "renderGameOverlay", at = @At(value = "FIELD", ordinal = 0,
            target = "Lnet/minecraft/client/gui/GuiIngame;overlayMessageTime:I"))
    private int lich$actionBar(int shownFor) {
        lich$actionBarDrawn = shownFor > 0 && !HudHooks.pre(HudElement.ACTION_BAR, -1);
        return lich$actionBarDrawn ? shownFor : 0;
    }

    @ModifyExpressionValue(method = "renderGameOverlay", at = @At(value = "FIELD", ordinal = 0,
            target = "Lnet/minecraft/client/gui/GuiIngame;titlesTimer:I"))
    private int lich$title(int shownFor) {
        if (lich$actionBarDrawn) {
            lich$actionBarDrawn = false;
            HudHooks.post(HudElement.ACTION_BAR, -1);
        }
        lich$titleDrawn = shownFor > 0 && !HudHooks.pre(HudElement.TITLE, -1);
        return lich$titleDrawn ? shownFor : 0;
    }

    @Inject(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/WorldClient;getScoreboard()Lnet/minecraft/scoreboard/Scoreboard;"))
    private void lich$titlePost(float partialTicks, CallbackInfo ci) {
        if (lich$titleDrawn) {
            lich$titleDrawn = false;
            HudHooks.post(HudElement.TITLE, -1);
        }
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiNewChat;drawChat(I)V"))
    private void lich$chat(GuiNewChat chat, int updateCounter, Operation<Void> draw) {
        if (!HudHooks.pre(HudElement.CHAT, -1)) {
            draw.call(chat, updateCounter);
            HudHooks.post(HudElement.CHAT, -1);
        }
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiPlayerTabOverlay;renderPlayerlist(ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreObjective;)V"))
    private void lich$playerList(GuiPlayerTabOverlay list, int width, Scoreboard scoreboard, ScoreObjective objective, Operation<Void> draw) {
        if (!HudHooks.pre(HudElement.PLAYER_LIST, -1)) {
            draw.call(list, width, scoreboard, objective);
            HudHooks.post(HudElement.PLAYER_LIST, -1);
        }
    }

    // --- the bars, inside renderPlayerStats ------------------------------------------------------------

    @Inject(method = "renderPlayerStats", at = @At(value = "INVOKE_STRING", args = "ldc=armor",
            target = "Lnet/minecraft/profiler/Profiler;startSection(Ljava/lang/String;)V"))
    private void lich$armor(ScaledResolution resolution, CallbackInfo ci) {
        lich$nextBar(HudElement.ARMOR, resolution);
    }

    @Inject(method = "renderPlayerStats", at = @At(value = "INVOKE_STRING", args = "ldc=health",
            target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V"))
    private void lich$health(ScaledResolution resolution, CallbackInfo ci) {
        lich$nextBar(HudElement.HEALTH, resolution);
    }

    @Inject(method = "renderPlayerStats", at = @At(value = "INVOKE_STRING", args = "ldc=food",
            target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V"))
    private void lich$food(ScaledResolution resolution, CallbackInfo ci) {
        lich$nextBar(HudElement.FOOD, resolution);
    }

    @Inject(method = "renderMountHealth", at = @At(value = "INVOKE_STRING", args = "ldc=mountHealth",
            target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V"))
    private void lich$mountHealth(ScaledResolution resolution, CallbackInfo ci) {
        lich$nextBar(HudElement.HEALTHMOUNT, resolution);
    }

    @Inject(method = "renderPlayerStats", at = @At(value = "INVOKE_STRING", args = "ldc=air",
            target = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V"))
    private void lich$air(ScaledResolution resolution, CallbackInfo ci) {
        lich$nextBar(HudElement.AIR, resolution);
    }

    @Inject(method = {"renderPlayerStats", "renderMountHealth"}, at = @At("RETURN"))
    private void lich$barsDone(ScaledResolution resolution, CallbackInfo ci) {
        lich$nextBar(null, resolution);
    }

    @WrapWithCondition(method = {"renderPlayerStats", "renderMountHealth"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiIngame;drawTexturedModalRect(IIIIII)V"))
    private boolean lich$drawBar(GuiIngame hud, int x, int y, int u, int v, int width, int height) {
        return !lich$barCancelled;
    }

    @Unique
    private void lich$nextBar(HudElement next, ScaledResolution resolution) {
        if (lich$bar != null && !lich$barCancelled) {
            HudHooks.post(lich$bar, lich$barTop);
        }
        lich$bar = next;
        lich$barCancelled = false;
        if (next != null) {
            lich$barTop = HudHooks.vanillaTop(next, resolution);
            lich$barCancelled = HudHooks.pre(next, lich$barTop);
        }
    }
}
