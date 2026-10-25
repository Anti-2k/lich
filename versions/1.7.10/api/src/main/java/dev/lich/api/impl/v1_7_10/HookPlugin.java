package dev.lich.api.impl.v1_7_10;

import dev.lich.api.hook.Hooks;
import dev.lich.loader.LichLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Weaves only the hooks the loaded mods asked for: each of Lich's Mixins belongs to one hook, and applies
 * only if that hook is enabled and the Mixin fits the game (some hooks need one Mixin for vanilla's
 * classes and another for Forge's replacements of them).
 */
public final class HookPlugin implements IMixinConfigPlugin {
    private enum Game { ANY, VANILLA, FORGE }

    private record Rule(String hook, Game game) {
    }

    private static final Map<String, Rule> RULES = Map.ofEntries(
            Map.entry("LifecycleMixin", new Rule(LichLoader.LIFECYCLE_HOOK, Game.ANY)),
            Map.entry("ClientTickMixin", new Rule(Hooks.CLIENT_TICK, Game.ANY)),
            Map.entry("FrameMixin", new Rule(Hooks.RENDER_FRAME, Game.ANY)),
            Map.entry("MouseInputMixin", new Rule(Hooks.INPUT_MOUSE, Game.ANY)),
            Map.entry("ScreenMixin", new Rule(Hooks.SCREEN, Game.ANY)),
            Map.entry("ChatMixin", new Rule(Hooks.CHAT_RECEIVE, Game.ANY)),
            Map.entry("SoundMixin", new Rule(Hooks.SOUND_PLAY, Game.ANY)),
            Map.entry("WorldLastMixin", new Rule(Hooks.RENDER_WORLD, Game.ANY)),
            Map.entry("BlockOutlineMixin", new Rule(Hooks.RENDER_BLOCK_OUTLINE, Game.ANY)),
            Map.entry("FogMixin", new Rule(Hooks.RENDER_FOG, Game.ANY)),
            Map.entry("FovMixin", new Rule(Hooks.RENDER_FOV, Game.ANY)),
            Map.entry("EntityRenderMixin", new Rule(Hooks.RENDER_ENTITY, Game.ANY)),
            Map.entry("ItemTooltipMixin", new Rule(Hooks.ITEM_TOOLTIP, Game.ANY)),
            Map.entry("AttackMixin", new Rule(Hooks.PLAYER_ATTACK, Game.ANY)),
            Map.entry("HudVanillaMixin", new Rule(Hooks.RENDER_HUD, Game.VANILLA)),
            Map.entry("HudForgeMixin", new Rule(Hooks.RENDER_HUD, Game.FORGE)),
            Map.entry("GuiIngameAccessor", new Rule(Hooks.RENDER_HUD, Game.FORGE)),
            Map.entry("TooltipVanillaMixin", new Rule(Hooks.RENDER_TOOLTIP, Game.VANILLA)),
            Map.entry("TooltipForgeMixin", new Rule(Hooks.RENDER_TOOLTIP, Game.FORGE)));

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        Rule rule = RULES.get(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
        if (rule == null) {
            throw new IllegalStateException("Lich hook Mixin " + mixinClassName + " has no rule in HookPlugin");
        }
        boolean forge = LichLoader.isForgePresent();
        boolean fits = switch (rule.game()) {
            case ANY -> true;
            case VANILLA -> !forge;
            case FORGE -> forge;
        };
        return fits && LichLoader.isHookEnabled(rule.hook());
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
