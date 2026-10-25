package dev.lich.api.impl.v1_7_10.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.lich.api.client.HudElement;
import dev.lich.api.client.HudEvents;
import dev.lich.api.impl.v1_7_10.HudHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Hook {@code render.hud} without Forge: vanilla's HUD in the events Forge's {@code GuiIngameForge} fires
 * (see {@code HudForgeMixin}). 1.7.10 draws most of it inline in {@code renderGameOverlay}, so each element
 * is found by what starts it: a method call (wrapped), the condition that guards it (its value changed to
 * skip a cancelled element), or its first draw call. POSTs go at the next statement. The bars live in
 * {@code renderIcons}, which the profiler splits into sections: each section start ends the previous bar and
 * begins the next, and a cancelled bar's texture draws are skipped. The order of events follows vanilla's
 * drawing order, which differs from Forge's (vanilla draws the hotbar's background before the crosshair and
 * bars and its items after them, so HOTBAR's PRE and POST enclose those).
 */
@Mixin(GuiIngame.class)
public abstract class HudVanillaMixin {
    private static final String RECT = "Lnet/minecraft/client/gui/GuiIngame;drawTexturedModalRect(IIIIII)V";
    private static final String START_SECTION = "Lnet/minecraft/profiler/Profiler;startSection(Ljava/lang/String;)V";
    private static final String END_SECTION = "Lnet/minecraft/profiler/Profiler;endSection()V";
    private static final String END_START_SECTION = "Lnet/minecraft/profiler/Profiler;endStartSection(Ljava/lang/String;)V";

    @Shadow
    @Final
    private Minecraft mc;

    @Unique
    private boolean lich$hotbarCancelled;
    @Unique
    private boolean lich$jumpBarCancelled;
    @Unique
    private boolean lich$jumpBarDrawn;
    @Unique
    private boolean lich$experienceDrawn;
    @Unique
    private boolean lich$debugDrawn;
    /** The F3 lines being collected for {@link HudEvents#DEBUG_TEXT}, or {@code null} when vanilla draws them itself. */
    @Unique
    private List<String> lich$debugLeft;
    @Unique
    private List<String> lich$debugRight;
    @Unique
    private boolean lich$actionBarDrawn;
    @Unique
    private boolean lich$playerListDrawn;
    @Unique
    private HudElement lich$bar;
    @Unique
    private int lich$barTop;
    @Unique
    private boolean lich$barCancelled;

    @Inject(method = "renderGameOverlay", at = @At("HEAD"), cancellable = true)
    private void lich$allPre(float partialTicks, boolean hasScreen, int mouseX, int mouseY, CallbackInfo ci) {
        HudHooks.begin(partialTicks, new ScaledResolution(mc, mc.displayWidth, mc.displayHeight));
        lich$playerListDrawn = false;
        if (HudHooks.pre(HudElement.ALL, -1)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderGameOverlay", at = @At("RETURN"))
    private void lich$allPost(float partialTicks, boolean hasScreen, int mouseX, int mouseY, CallbackInfo ci) {
        if (lich$playerListDrawn) {
            HudHooks.post(HudElement.PLAYER_LIST, -1);
        }
        HudHooks.post(HudElement.ALL, -1);
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiIngame;renderPumpkinBlur(II)V"))
    private void lich$helmet(GuiIngame hud, int width, int height, Operation<Void> draw) {
        if (!HudHooks.pre(HudElement.HELMET, -1)) {
            draw.call(hud, width, height);
            HudHooks.post(HudElement.HELMET, -1);
        }
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiIngame;func_130015_b(FII)V"))
    private void lich$portal(GuiIngame hud, float timeInPortal, int width, int height, Operation<Void> draw) {
        if (!HudHooks.pre(HudElement.PORTAL, -1)) {
            draw.call(hud, timeInPortal, width, height);
            HudHooks.post(HudElement.PORTAL, -1);
        }
    }

    // --- the hotbar: its background, then (after the crosshair, boss bar and bars) its items ------------

    @WrapWithCondition(method = "renderGameOverlay", at = @At(value = "INVOKE", target = RECT, ordinal = 0))
    private boolean lich$hotbar(GuiIngame hud, int x, int y, int u, int v, int width, int height) {
        lich$hotbarCancelled = HudHooks.pre(HudElement.HOTBAR, -1);
        return !lich$hotbarCancelled;
    }

    @WrapWithCondition(method = "renderGameOverlay", at = @At(value = "INVOKE", target = RECT, ordinal = 1))
    private boolean lich$hotbarSelection(GuiIngame hud, int x, int y, int u, int v, int width, int height) {
        return !lich$hotbarCancelled;
    }

    @WrapWithCondition(method = "renderGameOverlay", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiIngame;renderInventorySlot(IIIF)V"))
    private boolean lich$hotbarItem(GuiIngame hud, int slot, int x, int y, float partialTicks) {
        return !lich$hotbarCancelled;
    }

    @Inject(method = "renderGameOverlay", at = @At(value = "INVOKE", target = END_SECTION, ordinal = 0),
            slice = @Slice(from = @At(value = "INVOKE_STRING", target = START_SECTION, args = "ldc=actionBar")))
    private void lich$hotbarPost(float partialTicks, boolean hasScreen, int mouseX, int mouseY, CallbackInfo ci) {
        if (!lich$hotbarCancelled) {
            HudHooks.post(HudElement.HOTBAR, -1);
        }
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE", target = RECT, ordinal = 2))
    private void lich$crosshair(GuiIngame hud, int x, int y, int u, int v, int width, int height, Operation<Void> draw) {
        if (!HudHooks.pre(HudElement.CROSSHAIRS, -1)) {
            draw.call(hud, x, y, u, v, width, height);
            HudHooks.post(HudElement.CROSSHAIRS, -1);
        }
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiIngame;renderBossHealth()V"))
    private void lich$bossHealth(GuiIngame hud, Operation<Void> draw) {
        if (!HudHooks.pre(HudElement.BOSSHEALTH, -1)) {
            draw.call(hud);
            HudHooks.post(HudElement.BOSSHEALTH, -1);
        }
    }

    // --- the horse's jump bar, or experience ---------------------------------------------------------------

    @ModifyExpressionValue(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/entity/EntityClientPlayerMP;isRidingHorse()Z"))
    private boolean lich$jumpBar(boolean riding) {
        lich$jumpBarCancelled = riding && HudHooks.pre(HudElement.JUMPBAR, -1);
        lich$jumpBarDrawn = riding && !lich$jumpBarCancelled;
        // A cancelled jump bar still takes the riding branch, so experience isn't drawn instead.
        return riding;
    }

    @WrapWithCondition(method = "renderGameOverlay", at = {
            @At(value = "INVOKE", target = RECT, ordinal = 3),
            @At(value = "INVOKE", target = RECT, ordinal = 4)})
    private boolean lich$drawJumpBar(GuiIngame hud, int x, int y, int u, int v, int width, int height) {
        return !lich$jumpBarCancelled;
    }

    @ModifyExpressionValue(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/PlayerControllerMP;gameIsSurvivalOrAdventure()Z"))
    private boolean lich$experience(boolean survival) {
        lich$experienceDrawn = survival && !HudHooks.pre(HudElement.EXPERIENCE, -1);
        return lich$experienceDrawn;
    }

    @Inject(method = "renderGameOverlay", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/settings/GameSettings;heldItemTooltips:Z"))
    private void lich$jumpBarOrExperiencePost(float partialTicks, boolean hasScreen, int mouseX, int mouseY, CallbackInfo ci) {
        if (lich$jumpBarDrawn) {
            lich$jumpBarDrawn = false;
            HudHooks.post(HudElement.JUMPBAR, -1);
        }
        if (lich$experienceDrawn) {
            lich$experienceDrawn = false;
            HudHooks.post(HudElement.EXPERIENCE, -1);
        }
    }

    // --- F3: drawn inline, line by line; with DEBUG_TEXT listeners, collected into lists and drawn after ---

    @ModifyExpressionValue(method = "renderGameOverlay", at = @At(value = "FIELD", ordinal = 0,
            target = "Lnet/minecraft/client/settings/GameSettings;showDebugInfo:Z"))
    private boolean lich$debug(boolean shown) {
        lich$debugDrawn = shown && !HudHooks.pre(HudElement.DEBUG, -1);
        boolean collect = lich$debugDrawn && HudEvents.DEBUG_TEXT.hasListeners();
        lich$debugLeft = collect ? new ArrayList<>() : null;
        lich$debugRight = collect ? new ArrayList<>() : null;
        return lich$debugDrawn;
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/FontRenderer;drawStringWithShadow(Ljava/lang/String;III)I"),
            slice = @Slice(from = @At(value = "INVOKE_STRING", target = START_SECTION, args = "ldc=debug"),
                    to = @At(value = "INVOKE_STRING", target = START_SECTION, args = "ldc=overlayMessage")))
    private int lich$debugLine(FontRenderer font, String text, int x, int y, int color, Operation<Integer> draw) {
        if (lich$debugLeft == null) {
            return draw.call(font, text, x, y, color);
        }
        lich$debugLeft.add(text);
        return 0;
    }

    /** The memory lines on the right and the position lines on the left; vanilla leaves a gap before the latter. */
    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiIngame;drawString(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;III)V"),
            slice = @Slice(from = @At(value = "INVOKE_STRING", target = START_SECTION, args = "ldc=debug"),
                    to = @At(value = "INVOKE_STRING", target = START_SECTION, args = "ldc=overlayMessage")))
    private void lich$debugLineGray(GuiIngame hud, FontRenderer font, String text, int x, int y, int color, Operation<Void> draw) {
        if (lich$debugLeft == null) {
            draw.call(hud, font, text, x, y, color);
        } else if (x != 2) {
            lich$debugRight.add(text);
        } else {
            if (lich$debugLeft.size() == 5) {
                lich$debugLeft.add(null);
            }
            lich$debugLeft.add(text);
        }
    }

    @Inject(method = "renderGameOverlay", at = @At(value = "INVOKE", target = END_SECTION, ordinal = 0),
            slice = @Slice(from = @At(value = "INVOKE_STRING", target = START_SECTION, args = "ldc=debug")))
    private void lich$debugPost(float partialTicks, boolean hasScreen, int mouseX, int mouseY, CallbackInfo ci) {
        if (lich$debugLeft != null) {
            List<String> left = lich$debugLeft;
            List<String> right = lich$debugRight;
            lich$debugLeft = lich$debugRight = null;
            HudEvents.DEBUG_TEXT.fire(new HudEvents.DebugText(left, right));
            lich$drawDebugText(left, right);
        }
        if (lich$debugDrawn) {
            HudHooks.post(HudElement.DEBUG, -1);
        }
    }

    /** The lines as Forge 1.7.10 draws its F3 text: one per 10 pixels, the right column against vanilla's margin. */
    @Unique
    private void lich$drawDebugText(List<String> left, List<String> right) {
        FontRenderer font = mc.fontRenderer;
        int width = HudHooks.resolution().getScaledWidth();
        for (int i = 0; i < left.size(); i++) {
            String line = left.get(i);
            if (line != null && !line.isEmpty()) {
                font.drawStringWithShadow(line, 2, 2 + i * 10, 0xFFFFFF);
            }
        }
        for (int i = 0; i < right.size(); i++) {
            String line = right.get(i);
            if (line != null && !line.isEmpty()) {
                font.drawStringWithShadow(line, width - font.getStringWidth(line) - 2, 2 + i * 10, 0xFFFFFF);
            }
        }
    }

    // --- the record overlay, the chat, the player list --------------------------------------------------

    @ModifyExpressionValue(method = "renderGameOverlay", at = @At(value = "FIELD", ordinal = 0,
            target = "Lnet/minecraft/client/gui/GuiIngame;recordPlayingUpFor:I"))
    private int lich$actionBar(int shownFor) {
        lich$actionBarDrawn = shownFor > 0 && !HudHooks.pre(HudElement.ACTION_BAR, -1);
        return lich$actionBarDrawn ? shownFor : 0;
    }

    @Inject(method = "renderGameOverlay", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/multiplayer/WorldClient;getScoreboard()Lnet/minecraft/scoreboard/Scoreboard;"))
    private void lich$actionBarPost(float partialTicks, boolean hasScreen, int mouseX, int mouseY, CallbackInfo ci) {
        if (lich$actionBarDrawn) {
            lich$actionBarDrawn = false;
            HudHooks.post(HudElement.ACTION_BAR, -1);
        }
    }

    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiNewChat;drawChat(I)V"))
    private void lich$chat(GuiNewChat chat, int updateCounter, Operation<Void> draw) {
        if (!HudHooks.pre(HudElement.CHAT, -1)) {
            draw.call(chat, updateCounter);
            HudHooks.post(HudElement.CHAT, -1);
        }
    }

    /**
     * The player list key, the first part of the condition that shows the list. Forge fires PLAYER_LIST only once
     * the whole condition holds, so the rest of it is checked here too; a cancelled list reads as the key being up.
     */
    @WrapOperation(method = "renderGameOverlay", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/settings/KeyBinding;getIsKeyPressed()Z"))
    private boolean lich$playerList(KeyBinding key, Operation<Boolean> pressed) {
        if (!pressed.call(key)) {
            return false;
        }
        boolean shown = !mc.isIntegratedServerRunning() || mc.thePlayer.sendQueue.playerInfoList.size() > 1
                || mc.theWorld.getScoreboard().func_96539_a(0) != null; // the list slot
        if (shown && HudHooks.pre(HudElement.PLAYER_LIST, -1)) {
            return false;
        }
        lich$playerListDrawn = shown;
        return true;
    }

    // --- the bars, inside renderIcons (func_110327_a) -----------------------------------------------------------------

    @Inject(method = "func_110327_a", at = @At(value = "INVOKE_STRING", args = "ldc=armor", target = START_SECTION))
    private void lich$armor(int width, int height, CallbackInfo ci) {
        lich$nextBar(HudElement.ARMOR, height);
    }

    @Inject(method = "func_110327_a", at = @At(value = "INVOKE_STRING", args = "ldc=health", target = END_START_SECTION))
    private void lich$health(int width, int height, CallbackInfo ci) {
        lich$nextBar(HudElement.HEALTH, height);
    }

    @Inject(method = "func_110327_a", at = @At(value = "INVOKE_STRING", args = "ldc=food", target = END_START_SECTION))
    private void lich$food(int width, int height, CallbackInfo ci) {
        lich$nextBar(HudElement.FOOD, height);
    }

    @Inject(method = "func_110327_a", at = @At(value = "INVOKE_STRING", args = "ldc=mountHealth", target = END_START_SECTION))
    private void lich$mountHealth(int width, int height, CallbackInfo ci) {
        lich$nextBar(HudElement.HEALTHMOUNT, height);
    }

    @Inject(method = "func_110327_a", at = @At(value = "INVOKE_STRING", args = "ldc=air", target = END_START_SECTION))
    private void lich$air(int width, int height, CallbackInfo ci) {
        lich$nextBar(HudElement.AIR, height);
    }

    @Inject(method = "func_110327_a", at = @At("RETURN"))
    private void lich$barsDone(int width, int height, CallbackInfo ci) {
        lich$nextBar(null, height);
    }

    @WrapWithCondition(method = "func_110327_a", at = @At(value = "INVOKE", target = RECT))
    private boolean lich$drawBar(GuiIngame hud, int x, int y, int u, int v, int width, int height) {
        return !lich$barCancelled;
    }

    @Unique
    private void lich$nextBar(HudElement next, int height) {
        if (lich$bar != null && !lich$barCancelled) {
            HudHooks.post(lich$bar, lich$barTop);
        }
        lich$bar = next;
        lich$barCancelled = false;
        if (next != null) {
            lich$barTop = HudHooks.vanillaTop(next, height);
            lich$barCancelled = HudHooks.pre(next, lich$barTop);
        }
    }
}
